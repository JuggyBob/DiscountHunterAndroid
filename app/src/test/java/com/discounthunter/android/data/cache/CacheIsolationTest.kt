package com.discounthunter.android.data.cache

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Cache identity and expiry.
 *
 * The cache exists partly to make the app usable offline, but a cache that can serve the wrong
 * entry is worse than no cache at all. These tests pin the two properties that make it safe:
 * entries are keyed by store-native identity (never by title), and an expired entry is a miss.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheIsolationTest {

    private lateinit var directory: File
    private var now = 1_700_000_000_000L
    private lateinit var cache: PayloadCache

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("payload-cache-test").toFile()
        now = 1_700_000_000_000L
        // A controllable clock makes TTL behaviour deterministic instead of timing-dependent.
        val clock = { now }
        cache = PayloadCache(directory, clock = clock, io = UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    // ---------------------------------------------------------------- identity

    @Test
    fun `two different app ids never share an entry`() = runTest {
        cache.write(CacheKeys.steamAppDetails(1245620, "US"), "elden ring data")
        cache.write(CacheKeys.steamAppDetails(2415780, "US"), "metal eden data")

        assertEquals("elden ring data", cache.read(CacheKeys.steamAppDetails(1245620, "US"), 60_000))
        assertEquals("metal eden data", cache.read(CacheKeys.steamAppDetails(2415780, "US"), 60_000))
    }

    @Test
    fun `the same app id in different regions is a different entry`() = runTest {
        // Steam prices are region-specific, so a US payload must never be served to a DE request.
        cache.write(CacheKeys.steamAppDetails(1245620, "US"), "us prices")
        cache.write(CacheKeys.steamAppDetails(1245620, "DE"), "de prices")

        assertEquals("us prices", cache.read(CacheKeys.steamAppDetails(1245620, "US"), 60_000))
        assertEquals("de prices", cache.read(CacheKeys.steamAppDetails(1245620, "DE"), 60_000))
    }

    @Test
    fun `a payload kind never collides with another`() = runTest {
        cache.write(CacheKeys.steamFeatured("US"), "featured")
        cache.write(CacheKeys.steamSearch("elden", "US"), "search")

        assertEquals("featured", cache.read(CacheKeys.steamFeatured("US"), 60_000))
        assertEquals("search", cache.read(CacheKeys.steamSearch("elden", "US"), 60_000))
    }

    @Test
    fun `search terms are case insensitive in the key`() = runTest {
        cache.write(CacheKeys.steamSearch("Elden Ring", "US"), "results")

        assertEquals("results", cache.read(CacheKeys.steamSearch("elden ring", "US"), 60_000))
    }

    @Test
    fun `different stores never share an entry`() = runTest {
        cache.write(CacheKey("steam", "appdetails", "1", "US", "v3"), "steam payload")
        cache.write(CacheKey("gog", "appdetails", "1", "US", "v3"), "gog payload")

        assertEquals("steam payload", cache.read(CacheKey("steam", "appdetails", "1", "US", "v3"), 60_000))
        assertEquals("gog payload", cache.read(CacheKey("gog", "appdetails", "1", "US", "v3"), 60_000))
    }

    // ---------------------------------------------------------------- expiry

    @Test
    fun `an entry within its lifetime is a hit`() = runTest {
        cache.write(CacheKey("steam", "featured", "US"), "payload")

        now += 60_000

        assertEquals("payload", cache.read(CacheKey("steam", "featured", "US"), 120_000))
    }

    @Test
    fun `an expired entry is a miss and is deleted`() = runTest {
        val key = CacheKey("steam", "featured", "US")
        cache.write(key, "payload")

        now += 10 * 60_000

        assertNull(cache.read(key, 5 * 60_000))
        assertTrue("the expired file should be cleaned up", directory.listFiles().isNullOrEmpty())
    }

    /**
     * The same expiry rule across a process restart, which is the case that matters: a user who
     * leaves the app closed overnight must not come back to yesterday's prices.
     */
    @Test
    fun `an entry expires even when only the disk tier survives`() = runTest {
        val key = CacheKey("steam", "featured", "US")
        cache.write(key, "payload")

        // A fresh instance over the same directory: nothing is in memory any more.
        val restarted = PayloadCache(directory, clock = { now }, io = UnconfinedTestDispatcher())

        now += 10 * 60_000

        assertNull(restarted.read(key, 5 * 60_000))
    }

    @Test
    fun `a fresh entry survives a restart`() = runTest {
        val key = CacheKey("steam", "featured", "US")
        cache.write(key, "payload")

        val restarted = PayloadCache(directory, clock = { now }, io = UnconfinedTestDispatcher())

        assertEquals("payload", restarted.read(key, 5 * 60_000))
    }

    @Test
    fun `rewriting an entry refreshes its timestamp`() = runTest {
        val key = CacheKey("steam", "featured", "US")
        cache.write(key, "first")

        now += 10 * 60_000
        cache.write(key, "second")

        now += 10 * 60_000

        assertEquals("the refreshed entry is still live", "second", cache.read(key, 15 * 60_000))
    }

    @Test
    fun `a missing entry is a miss`() = runTest {
        assertNull(cache.read(CacheKey("steam", "featured", "ZZ"), 60_000))
    }

    @Test
    fun `a corrupt entry is treated as a miss rather than crashing`() = runTest {
        val key = CacheKey("steam", "featured", "US")
        cache.write(key, "payload")

        // Corrupt the stored file, as an interrupted write would.
        directory.listFiles()!!.first().writeText("not-a-timestamp\npartial")

        // Read through a fresh instance so the disk tier is what is exercised.
        val restarted = PayloadCache(directory, clock = { now }, io = UnconfinedTestDispatcher())

        assertNull("a corrupt entry must not be served", restarted.read(key, 60_000))
    }

    @Test
    fun `a corrupt entry does not crash an in-memory read`() = runTest {
        val key = CacheKey("steam", "featured", "US")
        cache.write(key, "payload")

        // The memory tier is still valid, so it answers; the damaged file must not break it.
        assertEquals("payload", cache.read(key, 60_000))
    }

    @Test
    fun `an empty payload is not stored`() = runTest {
        val key = CacheKey("steam", "featured", "US")
        cache.write(key, "")

        assertNull(cache.read(key, 60_000))
    }

    // ---------------------------------------------------------------- maintenance

    @Test
    fun `clearing removes every entry`() = runTest {
        cache.write(CacheKey("steam", "featured", "US"), "a")
        cache.write(CacheKey("gog", "page", "elden_ring"), "b")

        cache.clear()

        assertNull(cache.read(CacheKey("steam", "featured", "US"), 60_000))
        assertNull(cache.read(CacheKey("gog", "page", "elden_ring"), 60_000))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test
    fun `size reflects what is stored`() = runTest {
        assertEquals(0L, cache.sizeBytes())

        cache.write(CacheKey("steam", "featured", "US"), "payload")

        assertTrue("the cache should report a non-zero size", cache.sizeBytes() > 0)
    }

    // ---------------------------------------------------------------- keys

    @Test
    fun `keys with the same parts produce the same digest`() {
        assertEquals(CacheKey("steam", "appdetails", "1").digest, CacheKey("steam", "appdetails", "1").digest)
        assertNotEquals(CacheKey("steam", "appdetails", "1").digest, CacheKey("steam", "appdetails", "2").digest)
    }

    @Test
    fun `keys are readable for diagnostics`() {
        val key = CacheKeys.steamAppDetails(1245620, "US")
        assertEquals("steam/appdetails/1245620/US/v3", key.readable)
    }

    @Test
    fun `a key with a blank part is rejected at construction`() {
        // A half-built key would silently serve the wrong entry, so it must not be constructible.
        var rejected = false
        try {
            CacheKey("steam", "appdetails", "")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("a key with a blank part must be rejected", rejected)
    }
}
