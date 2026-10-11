package com.discounthunter.android.data.wishlist

import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.WishlistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wishlist matching.
 *
 * The desktop build compared title keys by *prefix*, so saving "Portal 2" also lit up "Portal" and
 * vice versa — the same "wrong game" family of defect as the screenshot bug, in the wishlist. These
 * tests pin the stricter rules the Android build uses.
 */
class WishlistKeyTest {

    private fun entry(
        title: String,
        store: StoreId = StoreId.STEAM,
        appId: Int? = 1245620,
        gogSlug: String = "",
        thumbUrl: String = "",
        url: String = "https://store.steampowered.com/app/1245620/",
        addedAt: Long = 1_700_000_000_000L,
    ) = WishlistEntry(
        key = keyFor(title, store, appId, gogSlug),
        titleKey = titleKeyFor(title),
        title = title,
        store = store,
        appId = appId,
        gogSlug = gogSlug,
        thumbUrl = thumbUrl,
        url = url,
        addedAt = addedAt,
    )

    private fun keyFor(title: String, store: StoreId, appId: Int?, gogSlug: String) =
        com.discounthunter.android.core.text.TitleKey.exact(store, appId, gogSlug, title)

    private fun titleKeyFor(title: String) =
        com.discounthunter.android.core.text.TitleKey.normalize(title)

    // ---------------------------------------------------------------- exact keys

    @Test
    fun `the same store id always matches, whatever the title`() {
        // The store id is the identity. A store that renames a game must still match the saved entry.
        val original = entry("Elden Ring", appId = 1245620)
        val renamed = entry("ELDEN RING (Renamed)", appId = 1245620)

        assertEquals(original.key, renamed.key)
    }

    @Test
    fun `a different store id is a different game`() {
        val a = entry("Elden Ring", appId = 1245620)
        val b = entry("Elden Ring", appId = 2415780)

        assertTrue(a.key != b.key)
    }

    @Test
    fun `the same game on two stores does not share an exact key`() {
        // Exact keys are per-store by design; the title key is what crosses stores.
        val steam = entry("Elden Ring", StoreId.STEAM, appId = 1245620)
        val gog = entry("Elden Ring", StoreId.GOG, appId = null, gogSlug = "elden_ring")

        assertTrue(steam.key != gog.key)
        assertEquals(steam.titleKey, gog.titleKey)
    }

    @Test
    fun `an app id beats a slug beats a title`() {
        assertEquals(
            "s1:a1245620",
            com.discounthunter.android.core.text.TitleKey.exact(StoreId.STEAM, 1245620, "slug", "Elden Ring"),
        )
        assertEquals(
            "s7:gelden_ring",
            com.discounthunter.android.core.text.TitleKey.exact(StoreId.GOG, null, "elden_ring", "Elden Ring"),
        )
        assertEquals(
            "teldenring",
            com.discounthunter.android.core.text.TitleKey.exact(StoreId.STEAM, null, null, "Elden Ring"),
        )
        assertEquals("", com.discounthunter.android.core.text.TitleKey.exact(StoreId.STEAM, null, null, ""))
    }

    @Test
    fun `key shapes are unambiguous`() {
        // The prefixes exist so a title key can never be mistaken for an app id or a slug.
        assertTrue(com.discounthunter.android.core.text.TitleKey.exact(StoreId.STEAM, 1, null, "x").startsWith("s1:a"))
        assertTrue(com.discounthunter.android.core.text.TitleKey.exact(StoreId.GOG, null, "x", "x").startsWith("s7:g"))
        assertTrue(com.discounthunter.android.core.text.TitleKey.exact(StoreId.STEAM, null, null, "X").startsWith("t"))
    }

    @Test
    fun `a slug with a path prefix is cleaned`() {
        // GOG slugs appear as `en/<slug>`; only the last segment identifies the product.
        assertEquals(
            "s7:ggoldenearena",
            com.discounthunter.android.core.text.TitleKey.exact(StoreId.GOG, null, "en/goldenearena", "x"),
        )
    }

    @Test
    fun `a numeric steam slug is treated as an app id`() {
        // The generic identity hands Steam's app id over in the slug slot; it must still be keyed
        // as an app id so it can never collide with a GOG slug that happens to be the same digits.
        assertEquals(
            "s1:a1245620",
            com.discounthunter.android.core.text.TitleKey.exact(StoreId.STEAM, null, "1245620", "Elden Ring"),
        )
        // A GOG slug that looks numeric keeps the slug form: it is not an app id there.
        assertEquals(
            "s7:g12345",
            com.discounthunter.android.core.text.TitleKey.exact(StoreId.GOG, null, "12345", "x"),
        )
    }

    // ---------------------------------------------------------------- title keys

    @Test
    fun `the same title normalises to the same key across stores`() {
        assertEquals(
            titleKeyFor("Elden Ring"),
            titleKeyFor("  elden ring  "),
        )
        assertEquals(
            titleKeyFor("Baldur's Gate 3"),
            titleKeyFor("Baldurs Gate 3"),
        )
    }

    @Test
    fun `an edition qualifier does not change the title key`() {
        assertEquals(titleKeyFor("Elden Ring"), titleKeyFor("Elden Ring (Deluxe Edition)"))
    }

    @Test
    fun `a different title produces a different key`() {
        assertTrue(titleKeyFor("Elden Ring") != titleKeyFor("Metal Eden"))
    }

    // ---------------------------------------------------------------- prefix safety

    /**
     * The behaviour the desktop build got wrong.
     *
     * It matched title keys by *prefix*. "portal2" does start with "portal", so saving one of those
     * games lit up the other — two different products following each other. The keys are therefore
     * compared for equality only, which is why these two must produce genuinely different keys.
     */
    @Test
    fun `a prefix title produces a different key from its sequel`() {
        val portal = titleKeyFor("Portal")
        val portalTwo = titleKeyFor("Portal 2")

        assertTrue(portal != portalTwo)
        // Note the prefix relationship the desktop matcher relied on — and which no longer matters,
        // because matching is equality-based.
        assertTrue("portal2 does start with portal, which is exactly the trap", portalTwo.startsWith(portal))
    }

    @Test
    fun `short titles are never title matched`() {
        // Below the distinctive-length floor, only the exact key can match.
        val short = titleKeyFor("Hades")
        assertTrue(short.length < WishlistRepository.DISTINCTIVE_LENGTH)
        assertTrue(short != titleKeyFor("Hades II"))
    }

    @Test
    fun `long titles only match exactly`() {
        val a = titleKeyFor("Baldur's Gate 3")
        val b = titleKeyFor("Baldur's Gate 2")

        assertTrue(a.length >= WishlistRepository.DISTINCTIVE_LENGTH)
        assertTrue(a != b)
    }

    @Test
    fun `an edition qualifier yields the same key as the base game`() {
        // Deliberate: these name the same product, so following one should flag the other.
        assertEquals(titleKeyFor("Baldur's Gate 3"), titleKeyFor("Baldur's Gate 3 Remastered Deluxe"))
    }

    // ---------------------------------------------------------------- slugs from URLs

    @Test
    fun `slugs are extracted from store urls`() {
        assertEquals(
            "elden_ring",
            com.discounthunter.android.core.text.TitleKey.slugFromUrl(
                "https://www.gog.com/en/game/elden_ring?utm=1",
            ),
        )
        assertEquals("", com.discounthunter.android.core.text.TitleKey.slugFromUrl("https://www.gog.com/"))
        assertEquals("", com.discounthunter.android.core.text.TitleKey.slugFromUrl(null))
        assertEquals(
            "",
            com.discounthunter.android.core.text.TitleKey.slugFromUrl("https://example.com/file.html"),
        )
    }

    // ---------------------------------------------------------------- entry factories

    @Test
    fun `an entry built from a deal carries its identity`() {
        val deal = com.discounthunter.android.core.model.Deal(
            id = "steam_1245620",
            title = "Elden Ring",
            thumbUrl = "https://cdn.steamstatic.com/header.jpg",
            salePrice = 39.99,
            normalPrice = 59.99,
            savingsPercent = 33.0,
            store = StoreId.STEAM,
            identity = com.discounthunter.android.core.model.GameIdentity.steam(1245620),
            storeUrl = "https://store.steampowered.com/app/1245620/",
        )

        val saved = WishlistEntry.fromDeal(deal, 1_700_000_000_000L)

        assertEquals("s1:a1245620", saved.key)
        assertEquals("eldenring", saved.titleKey)
        assertEquals(1245620, saved.appId)
        assertEquals(StoreId.STEAM, saved.store)
        assertEquals(1_700_000_000_000L, saved.addedAt)
    }

    @Test
    fun `an entry built from a reference is openable again`() {
        val reference = com.discounthunter.android.core.model.GameReference(
            identity = requireNotNull(com.discounthunter.android.core.model.GameIdentity.gog("elden_ring")),
            title = "Elden Ring",
        )

        val saved = WishlistEntry.fromReference(reference, 1L)

        assertEquals("s7:gelden_ring", saved.key)
        assertEquals("elden_ring", saved.gogSlug)
        assertEquals(StoreId.GOG, saved.store)
        assertEquals("https://www.gog.com/en/game/elden_ring", saved.url)
    }

    @Test
    fun `a blank title still yields a usable key`() {
        val blank = titleKeyFor("")
        assertEquals("", blank)
        // An entry with neither key cannot be matched, and must be dropped rather than kept.
        assertTrue(blank.isEmpty())
    }
}
