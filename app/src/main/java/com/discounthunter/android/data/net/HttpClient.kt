package com.discounthunter.android.data.net

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The single outbound HTTP path for every store.
 *
 * One [OkHttpClient] is shared by all traffic so connections, the thread pool and the dispatcher
 * limits are global rather than per-call-site. That keeps socket usage bounded while still letting
 * the feed refresh query three stores in parallel.
 *
 * Behaviour worth noting:
 *  * every request runs off the main thread and is cancellable, so a superseded search really does
 *    stop instead of racing the newer one to the UI;
 *  * a single transient failure is retried once with a short backoff (stores routinely drop the
 *    first connection on a cold start);
 *  * `429` is surfaced as [Outcome.Reason.RATE_LIMITED] rather than retried, so the UI can say so;
 *  * responses are capped in size so a runaway store page cannot exhaust memory.
 */
class HttpClient(
    private val client: OkHttpClient,
    private val maxRetries: Int = 1,
) {

    suspend fun getText(url: String): Outcome<String> = get(url) { body -> body }

    /** Fetches and parses a JSON payload. Malformed JSON is reported as [Outcome.Reason.MALFORMED]. */
    suspend fun getJson(url: String): Outcome<kotlinx.serialization.json.JsonElement> =
        when (val result = get(url) { body -> body }) {
            is Outcome.Failure -> result
            is Outcome.Success -> com.discounthunter.android.core.text.Json.parse(result.value)
                ?.let { Outcome.Success(it) }
                ?: Outcome.Failure(
                    Outcome.Reason.MALFORMED,
                    "The store returned a response the app could not read.",
                )
        }

    private suspend fun <T> get(url: String, reader: (String) -> T): Outcome<T> {
        val parsed = url.toHttpUrlOrNull()
            ?: return Outcome.Failure(Outcome.Reason.NETWORK, "Malformed address.")

        var attempt = 0
        var last: Outcome.Failure = Outcome.Failure(Outcome.Reason.NETWORK, "Request failed.")

        while (attempt <= maxRetries) {
            when (val result = execute(parsed, reader)) {
                is Outcome.Success -> return result
                is Outcome.Failure -> {
                    last = result
                    // Only transient problems are worth a second try; a 404 or a rate limit is not.
                    if (!result.isTransient || attempt == maxRetries) return result
                    attempt++
                    delay(250L * attempt)
                }
            }
        }
        return last
    }

    private suspend fun <T> execute(url: HttpUrl, reader: (String) -> T): Outcome<T> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val call = client.newCall(request)
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { call.cancel() } }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeSafely(classify(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { result ->
                            continuation.resumeSafely(readResult(result, reader))
                        }
                    }
                })
            }
        }

    private fun <T> readResult(response: Response, reader: (String) -> T): Outcome<T> {
        if (!response.isSuccessful) {
            val reason = when (response.code) {
                429 -> Outcome.Reason.RATE_LIMITED
                else -> Outcome.Reason.HTTP
            }
            return Outcome.Failure(
                reason,
                httpMessage(response.code),
                response.code,
            )
        }

        val declared = response.body?.contentLength() ?: -1L
        if (declared > MAX_RESPONSE_BYTES) {
            return Outcome.Failure(
                Outcome.Reason.MALFORMED,
                "The store sent a response that is too large to read.",
            )
        }

        return try {
            // peekBody caps the read at one byte past the limit, so an over-sized payload is
            // rejected instead of exhausting the heap, while a short body is read whole.
            //
            // Deliberately *not* BufferedSource.readUtf8(byteCount): that demands exactly
            // byteCount bytes and throws EOFException on every response shorter than the cap,
            // which would turn every store page into a network error.
            val text = response.peekBody(MAX_RESPONSE_BYTES + 1).string()
            if (text.isEmpty()) {
                Outcome.Failure(Outcome.Reason.MALFORMED, "The store returned an empty response.")
            } else {
                Outcome.Success(reader(text))
            }
        } catch (e: IOException) {
            classify(e)
        }
    }

    private fun classify(e: IOException): Outcome.Failure = when (e) {
        is SocketTimeoutException -> Outcome.Failure(Outcome.Reason.TIMEOUT, "The request timed out.")
        is InterruptedIOException -> Outcome.Failure(Outcome.Reason.CANCELLED, "Cancelled.")
        is UnknownHostException -> Outcome.Failure(
            Outcome.Reason.NETWORK,
            "No internet connection.",
        )
        else -> Outcome.Failure(Outcome.Reason.NETWORK, "Could not reach the store.")
    }

    private fun httpMessage(code: Int): String = when (code) {
        403 -> "The store refused the request."
        404 -> "Not found on the store."
        429 -> "The store is rate limiting requests. Try again shortly."
        in 500..599 -> "The store is having trouble (HTTP $code)."
        else -> "The store replied with HTTP $code."
    }

    /** Resumes exactly once, tolerating a cancellation that races the callback. */
    private fun <T> CancellableContinuation<Outcome<T>>.resumeSafely(value: Outcome<T>) {
        if (isActive) resume(value)
    }

    private suspend fun delay(millis: Long) = kotlinx.coroutines.delay(millis)

    companion object {
        /**
         * Cap on a single response. Steam and GOG game pages are the big ones; 6 MB is comfortably
         * above any real payload and well below what would threaten a phone's heap.
         */
        const val MAX_RESPONSE_BYTES = 6L * 1024 * 1024

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36 DiscountHunter/1.0"

        fun buildDefault(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            // Bounded parallelism: enough to refresh three stores at once, low enough to stay
            // polite and to keep memory flat on a large list.
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 8
                maxRequestsPerHost = 4
            })
            .cookieJar(StoreCookieJar())
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * A minimal cookie jar holding only the age-gate cookies Steam requires.
 *
 * Steam answers a cookie-less request for an adult title with an age-check page instead of the
 * product page, which would silently cost us the game's requirements and trailers. Registering the
 * four cookies the desktop app also used keeps store-page scraping working. No store session or
 * credential is ever stored.
 */
private class StoreCookieJar : CookieJar {

    private val cookies: MutableList<Cookie> = mutableListOf()

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
        cookies.filter { it.matches(url) }.toList()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(this.cookies) {
            val host = url.host
            if (host.endsWith("steampowered.com") || host.endsWith("steamcommunity.com")) {
                AGE_GATE_COOKIES.forEach { (name, value) ->
                    this.cookies.removeAll { it.name == name && it.domain == host }
                    this.cookies += Cookie.Builder().name(name).value(value).domain(host).path("/").build()
                }
            }
        }
    }

    private companion object {
        val AGE_GATE_COOKIES = linkedMapOf(
            "birthtime" to "568022801",
            "mature_content" to "1",
            "wants_mature_content" to "1",
            "lastagecheckage" to "1-January-1990",
        )
    }
}
