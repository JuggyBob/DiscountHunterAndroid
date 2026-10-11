package com.discounthunter.android.data.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Tests the single outbound HTTP path against a real socket.
 *
 * These exist because the size-capping logic in `readResult` used to be wrong in a way no unit test
 * could see: `BufferedSource.readUtf8(byteCount)` demands *exactly* `byteCount` bytes and throws
 * `EOFException` on every response shorter than the cap, so every store page came back as a network
 * error. Only a real request against a real (short) body catches that.
 */
class HttpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HttpClient(okHttp(maxRetries = 0), maxRetries = 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun okHttp(maxRetries: Int) = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(maxRetries > 0)
        .build()

    private fun url(path: String = "/feed") = server.url(path).toString()

    @Test
    fun `reads a body far shorter than the response cap`() = runTest {
        // 11 bytes against a 6 MB cap: the case that used to throw EOFException.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val result = client.getText(url())

        assertEquals("""{"ok":true}""", (result as Outcome.Success).value)
    }

    @Test
    fun `reads a large body up to the cap`() = runTest {
        val big = "x".repeat(512 * 1024)
        server.enqueue(MockResponse().setBody(big))

        val result = client.getText(url())

        assertEquals(512 * 1024, ((result as Outcome.Success).value).length)
    }

    @Test
    fun `a body of exactly one byte is still a success`() = runTest {
        server.enqueue(MockResponse().setBody("x"))

        assertEquals("x", (client.getText(url()) as Outcome.Success).value)
    }

    @Test
    fun `empty body is reported as malformed rather than succeeding with nothing`() = runTest {
        server.enqueue(MockResponse().setBody(""))

        val result = client.getText(url())

        assertEquals(Outcome.Reason.MALFORMED, (result as Outcome.Failure).reason)
    }

    @Test
    fun `oversized body is rejected instead of being read into memory`() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat((HttpClient.MAX_RESPONSE_BYTES + 1).toInt())))

        val result = client.getText(url())

        assertEquals(Outcome.Reason.MALFORMED, (result as Outcome.Failure).reason)
    }

    @Test
    fun `a body right at the cap is still accepted`() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(HttpClient.MAX_RESPONSE_BYTES.toInt())))

        assertTrue(client.getText(url()) is Outcome.Success)
    }

    @Test
    fun `http error status is surfaced with a readable message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("nope"))

        val failure = client.getText(url()) as Outcome.Failure

        assertEquals(Outcome.Reason.HTTP, failure.reason)
        assertEquals(503, failure.httpCode)
        assertTrue(failure.message.contains("503"))
    }

    @Test
    fun `rate limiting is distinguished from other http failures and is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        val retrying = HttpClient(okHttp(maxRetries = 1), maxRetries = 1)
        val failure = retrying.getText(url()) as Outcome.Failure

        assertEquals(Outcome.Reason.RATE_LIMITED, failure.reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a transient connection drop is retried once`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("second try"))

        val retrying = HttpClient(okHttp(maxRetries = 1), maxRetries = 1)
        val result = retrying.getText(url())

        assertEquals("second try", (result as Outcome.Success).value)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retries are bounded`() = runTest {
        repeat(5) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }

        val retrying = HttpClient(okHttp(maxRetries = 1), maxRetries = 1)
        val result = retrying.getText(url())

        assertTrue(result is Outcome.Failure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a 404 is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody(""))

        val retrying = HttpClient(okHttp(maxRetries = 1), maxRetries = 1)
        val failure = retrying.getText(url("/missing")) as Outcome.Failure

        assertEquals(Outcome.Reason.HTTP, failure.reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `malformed url fails without touching the network`() = runTest {
        val result = client.getText("not a url")

        assertEquals(Outcome.Reason.NETWORK, (result as Outcome.Failure).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a closed port is reported as a network failure, not a crash`() = runTest {
        val dead = MockWebServer()
        dead.start()
        val address = dead.url("/").toString()
        dead.shutdown()

        val result = client.getText(address)

        assertTrue(result is Outcome.Failure)
        assertEquals(Outcome.Reason.NETWORK, (result as Outcome.Failure).reason)
    }

    @Test
    fun `json is parsed and a broken payload is reported as malformed`() = runTest {
        server.enqueue(MockResponse().setBody("""{"a":1}"""))
        server.enqueue(MockResponse().setBody("""not json at all"""))

        val good = client.getJson(url())
        val bad = client.getJson(url())

        assertTrue(good is Outcome.Success)
        assertEquals(Outcome.Reason.MALFORMED, (bad as Outcome.Failure).reason)
    }
}
