package com.discounthunter.android.core.model

import com.discounthunter.android.diagnostics.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Exchange rates between the app's currencies, fetched once and cached in memory.
 *
 * ## Why this exists
 *
 * Steam and Epic genuinely re-quote for a region, so selecting EUR really does change what they
 * return. GOG does not: its catalogue endpoint ignores every currency parameter tried —
 * `currency`, `currency_code`, `userSelectedCurrency`, and `locale`+`country` were each verified to
 * still answer `"currency":"USD"` — and `api.gog.com/products/{id}` carries no price at all. GOG
 * publishes in US dollars and nothing else.
 *
 * That leaves two bad options. Printing a dollar amount under a euro sign states a price the user
 * cannot buy. Printing `$0.49 USD` everywhere is honest but means the currency setting does nothing
 * for a third of the app, which is exactly the complaint that prompted this class. So the app
 * converts instead.
 *
 * ## What "converted" honestly means
 *
 * A converted price is an estimate. GOG may round its own euro price differently from
 * `USD x rate`, and Steam's regional price is not simply USD converted either — it is its own
 * pricing. So conversion applies **only where the store gave a different currency and the user
 * asked for another**, and the UI marks the result with `~` and says where the rate came from.
 *
 * ## The rates
 *
 * `frankfurter.app` republishes the European Central Bank's daily reference rates: a real published
 * source rather than a guess, with no key or account. It fails soft — if the fetch fails the app
 * keeps the raw amount and labels it with its true currency rather than inventing a number.
 */
class FxRates(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fetch: suspend (String) -> Result<String> = { url -> httpGet(url) },
) {

    /** Rates as "FROM_TO" -> multiplier. Empty until [ensure] succeeds. */
    @Volatile
    private var table: Map<String, Double> = emptyMap()

    /** The date the ECB published these rates for, shown alongside any converted price. */
    @Volatile
    var rateDate: LocalDate? = null
        private set

    private val lock = Mutex()
    private var fetchedAt: LocalDate? = null

    /**
     * Loads rates if missing or older than [maxAgeDays].
     *
     * Returns the table actually in use, which may be the previous one: a refresh that fails must
     * never blank out prices that were displaying correctly a moment ago.
     */
    suspend fun ensure(maxAgeDays: Long = 1): Map<String, Double> = withContext(io) {
        val today = LocalDate.now()
        val age = fetchedAt?.let { ChronoUnit.DAYS.between(it, today) } ?: Long.MAX_VALUE
        if (table.isNotEmpty() && age < maxAgeDays) return@withContext table

        lock.withLock {
            // Re-checked inside the lock: two screens starting at once must not both fetch.
            if (table.isEmpty()) {
                val response = fetch(RATES_URL)
                val body = response.getOrNull()
                if (body != null) {
                    val parsed = parse(body)
                    AppLog.i("FX: loaded ${parsed.size} rates for $BASE, published $rateDate")
                    if (parsed.isNotEmpty()) {
                        table = parsed
                        fetchedAt = today
                    } else {
                        AppLog.w("FX: response parsed to nothing; prices stay as quoted")
                    }
                } else {
                    AppLog.w(
                        "FX: rate fetch failed (${response.exceptionOrNull()?.message}); " +
                            "prices stay as quoted",
                    )
                }
            }
            table
        }
    }

    /**
     * Converts [amount] from [from] to [to].
     *
     * Returns [amount] unchanged when no conversion is needed or possible. Returning the input
     * rather than null keeps every call site total: a price can always be rendered, and the caller
     * decides how to label it.
     */
    fun convert(amount: Double, from: String, to: String): Double {
        val source = from.trim().uppercase()
        val target = to.trim().uppercase()
        if (source.isEmpty() || target.isEmpty() || source == target) return amount
        rateBetween(source, target)?.let { return amount * it }

        // Everything is stored against the base, so any other pair is a cross rate: convert into
        // the base and back out again. Both legs are multiplied, and each may need the reciprocal
        // of a row that exists rather than the row itself — there is no `GBP_USD`, only `USD_GBP`,
        // which is the direction that matters most here since pound prices meet dollar users.
        val intoBase = rateBetween(source, BASE) ?: return amount
        val outOfBase = rateBetween(BASE, target) ?: return amount
        return amount * intoBase * outOfBase
    }

    /** True when [from] -> [to] can currently be converted (or needs no conversion). */
    fun canConvert(from: String, to: String): Boolean {
        val source = from.trim().uppercase()
        val target = to.trim().uppercase()
        if (source.isEmpty() || target.isEmpty() || source == target) return true
        if (rateBetween(source, target) != null) return true
        return rateBetween(source, BASE) != null && rateBetween(BASE, target) != null
    }

    /** The multiplier for one pair, in either direction, or null when it is not known. */
    private fun rateBetween(from: String, to: String): Double? {
        table["${from}_$to"]?.let { return it }
        val inverse = table["${to}_$from"] ?: return null
        // A zero or absent rate cannot be divided by; returning null keeps the amount untouched
        // rather than producing Infinity, which would render as a nonsense price.
        return if (inverse == 0.0) null else 1.0 / inverse
    }

    /** True once rates have been loaded, so callers know conversion is possible. */
    val isLoaded: Boolean get() = table.isNotEmpty()

    private fun parse(body: String): Map<String, Double> {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyMap()
        runCatching { root["date"]?.jsonPrimitive?.content }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { text -> runCatching { LocalDate.parse(text) }.getOrNull() }
            ?.let { date -> rateDate = date }

        val rates = root["rates"]?.jsonObject ?: return emptyMap()
        val base = root["base"]?.jsonPrimitive?.content?.uppercase() ?: BASE
        val out = HashMap<String, Double>(rates.size)
        for ((code, value) in rates) {
            val amount = runCatching { value.jsonPrimitive.doubleOrNull }.getOrNull()
            if (amount != null) out["${base}_${code.uppercase()}"] = amount
        }
        return out
    }

    private companion object {
        const val BASE = "USD"
        const val RATES_URL = "https://api.frankfurter.app/latest?from=$BASE&to=EUR,GBP"

        /**
         * A plain HTTP GET.
         *
         * Written out rather than reusing the app's `HttpClient` because that needs an OkHttp
         * instance owned by the DI container, and this object is constructed directly by
         * ViewModels that have no container handle. Threading a client through would make one small
         * JSON body the app's most heavily wired operation.
         */
        private fun httpGet(url: String): Result<String> {
            val connection = try {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
            } catch (error: Exception) {
                return Result.failure(error)
            }
            return try {
                val code = connection.responseCode
                if (code in 200..299) {
                    Result.success(connection.inputStream.bufferedReader().use { it.readText() })
                } else {
                    Result.failure(IllegalStateException("FX service returned $code"))
                }
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                connection.disconnect()
            }
        }
    }
}
