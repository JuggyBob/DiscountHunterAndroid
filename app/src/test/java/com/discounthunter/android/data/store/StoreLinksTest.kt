package com.discounthunter.android.data.store

import com.discounthunter.android.core.model.StoreId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Store hand-off decisions.
 *
 * Pure logic only — probing and `startActivity` need a Context, but the *rule* of where a link
 * should go, and in what order to try, does not. That is where the bugs live.
 */
class StoreLinksTest {

    private val steamUrl = "https://store.steampowered.com/app/1245620/"
    private val gogUrl = "https://www.gog.com/en/game/elden_ring"
    private val epicUrl = "https://store.epicgames.com/en-US/p/jotun"
    private val youtubeUrl = "https://www.youtube.com/watch?v=abc123"

    /** The first place a hand-off should go — what a single-target implementation would return. */
    private fun first(
        url: String,
        store: StoreId?,
        preferStoreApp: Boolean = false,
        storeAppInstalled: Boolean = false,
    ) = StoreLinks.handOffAttempts(url, store, preferStoreApp, storeAppInstalled).firstOrNull()

    // ---------------------------------------------------------------- deep links

    @Test
    fun `a steam page becomes a steam app deep link`() {
        assertEquals(
            "steam://url/StoreAppPage/1245620",
            StoreLinks.deepLinkFor(StoreId.STEAM, steamUrl),
        )
    }

    @Test
    fun `an epic page becomes an epic launcher deep link`() {
        assertEquals(
            "com.epicgames.launcher://store/p/jotun",
            StoreLinks.deepLinkFor(StoreId.EPIC, epicUrl),
        )
    }

    @Test
    fun `gog has no deep link because galaxy publishes none`() {
        assertNull(StoreLinks.deepLinkFor(StoreId.GOG, gogUrl))
    }

    @Test
    fun `a page from a different store is not routed into this store's app`() {
        // Opening a GOG page in the Steam client would be nonsense.
        assertNull(StoreLinks.deepLinkFor(StoreId.STEAM, gogUrl))
        assertNull(StoreLinks.deepLinkFor(StoreId.EPIC, steamUrl))
    }

    @Test
    fun `an unrecognised url has no deep link`() {
        assertNull(StoreLinks.deepLinkFor(StoreId.STEAM, youtubeUrl))
        assertNull(StoreLinks.deepLinkFor(StoreId.STEAM, ""))
    }

    // ---------------------------------------------------------------- hand-off

    @Test
    fun `a hand-off with no store still opens in the browser`() {
        // The bug this pins down: this used to be `requireNotNull(store)`, which crashed the app
        // the first time a user tapped a trailer's hand-off button.
        val target = first(youtubeUrl, store = null, preferStoreApp = true, storeAppInstalled = true)

        assertEquals(youtubeUrl, target?.url)
        assertFalse(target!!.viaStoreApp)
        assertEquals("browser", target.storeName)
    }

    @Test
    fun `the store app is tried first when it is installed and preferred`() {
        val target = first(steamUrl, StoreId.STEAM, preferStoreApp = true, storeAppInstalled = true)

        assertEquals("steam://url/StoreAppPage/1245620", target?.url)
        assertTrue(target!!.viaStoreApp)
        assertEquals("Steam", target.storeName)
    }

    @Test
    fun `the browser is used when the store app is not installed`() {
        val target = first(steamUrl, StoreId.STEAM, preferStoreApp = true, storeAppInstalled = false)

        assertEquals(steamUrl, target?.url)
        assertFalse(target!!.viaStoreApp)
    }

    @Test
    fun `the browser is used when the store app is not preferred`() {
        val target = first(steamUrl, StoreId.STEAM, preferStoreApp = false, storeAppInstalled = true)

        assertEquals(steamUrl, target?.url)
        assertFalse(target!!.viaStoreApp)
    }

    @Test
    fun `a deep link is not forced when the store cannot produce one`() {
        val target = first(gogUrl, StoreId.GOG, preferStoreApp = true, storeAppInstalled = true)

        assertEquals(gogUrl, target?.url)
        assertFalse(target!!.viaStoreApp)
    }

    // ---------------------------------------------------------------- fallback order

    @Test
    fun `the web page is always offered after the store app`() {
        // This is what makes "Get deal" work on a phone with no store app: the store app is tried,
        // and if it cannot handle the link the public page is tried next rather than giving up.
        val attempts = StoreLinks.handOffAttempts(
            url = steamUrl,
            store = StoreId.STEAM,
            preferStoreApp = true,
            storeAppInstalled = true,
        )

        assertEquals(2, attempts.size)
        assertEquals("steam://url/StoreAppPage/1245620", attempts[0].url)
        assertTrue(attempts[0].viaStoreApp)
        assertEquals(steamUrl, attempts[1].url)
        assertFalse(attempts[1].viaStoreApp)
    }

    @Test
    fun `only the browser is offered when the store app cannot produce a deep link`() {
        val attempts = StoreLinks.handOffAttempts(gogUrl, StoreId.GOG, preferStoreApp = true, storeAppInstalled = true)

        assertEquals(1, attempts.size)
        assertEquals(gogUrl, attempts.single().url)
    }

    @Test
    fun `only the browser is offered when there is no store app`() {
        val attempts = StoreLinks.handOffAttempts(steamUrl, StoreId.STEAM, preferStoreApp = true, storeAppInstalled = false)

        assertEquals(listOf(steamUrl), attempts.map { it.url })
    }

    @Test
    fun `a store-native id with no scheme is expanded into a page url`() {
        assertEquals(
            "https://store.steampowered.com/app/1245620/",
            first("1245620", StoreId.STEAM)?.url,
        )
        assertEquals(
            "https://www.gog.com/en/game/elden_ring",
            first("elden_ring", StoreId.GOG)?.url,
        )
        assertEquals(
            "https://store.epicgames.com/en-US/p/jotun",
            first("jotun", StoreId.EPIC)?.url,
        )
    }

    @Test
    fun `there is nothing to open when the url is blank or unrecognisable`() {
        assertTrue(StoreLinks.handOffAttempts("", StoreId.STEAM, preferStoreApp = true, storeAppInstalled = true).isEmpty())
        assertTrue(StoreLinks.handOffAttempts("   ", null, false, false).isEmpty())
        assertTrue(StoreLinks.handOffAttempts("not-a-url", null, false, false).isEmpty())
    }
}
