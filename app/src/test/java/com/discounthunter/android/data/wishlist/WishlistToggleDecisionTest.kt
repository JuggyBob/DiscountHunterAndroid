package com.discounthunter.android.data.wishlist

import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.WishlistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wishlist mutation decisions.
 *
 * The bug this pins down: the previous implementation picked the entry to drop with
 * `matches(it.key, it.titleKey)`, which is true for every saved entry, so [WishlistRepository.toggle]
 * always removed the *first* stored game — the user never got a second entry on the wishlist.
 */
class WishlistToggleDecisionTest {

    private fun entry(title: String, appId: Int?, addedAt: Long) = WishlistEntry(
        key = com.discounthunter.android.core.text.TitleKey.exact(StoreId.STEAM, appId, null, title),
        titleKey = com.discounthunter.android.core.text.TitleKey.normalize(title),
        title = title,
        store = StoreId.STEAM,
        appId = appId,
        addedAt = addedAt,
    )

    @Test
    fun `two different games can both be on the wishlist`() {
        val first = entry("Elden Ring", 1245620, 1)
        val second = entry("Hades", 1145360, 2)

        val (l1, nowSaved1) = decisionAdd(emptyList(), first)
        val (l2, nowSaved2) = decisionAdd(l1, second)

        assertTrue(nowSaved1)
        assertTrue(nowSaved2)
        assertEquals(2, l2.size)
    }

    @Test
    fun `toggling a second game does not remove the first`() {
        val first = entry("Elden Ring", 1245620, 1)
        val second = entry("Hades", 1145360, 2)

        val (l1, _) = decisionAdd(emptyList(), first)
        val (l2, _) = decisionAdd(l1, second)

        assertEquals(2, l2.size)
        assertTrue(l2.any { it.key == first.key })
        assertTrue(l2.any { it.key == second.key })
    }

    @Test
    fun `toggling the same game removes it`() {
        val first = entry("Elden Ring", 1245620, 1)
        val second = entry("Hades", 1145360, 2)

        val (l1, _) = decisionAdd(emptyList(), first)
        val (l2, _) = decisionAdd(l1, second)
        val (l3, nowSaved3) = decisionAdd(l2, second)

        assertTrue(!nowSaved3)
        assertEquals(1, l3.size)
        assertTrue(l3.any { it.key == first.key })
        assertTrue(l3.none { it.key == second.key })
    }

    @Test
    fun `removal only drops the matching game`() {
        val a = entry("Elden Ring", 1245620, 1)
        val b = entry("Hades", 1145360, 2)
        val c = entry("Doom", 379720, 3)

        val (after, removed) = removeDecisionWrapper(listOf(a, b, c), b.key, b.titleKey)
        assertTrue(removed)
        assertEquals(listOf(a, c), after)
    }

    @Test
    fun `title matching adds a game only once`() {
        // Saving a long distinctive title on a second store lights it up — and toggling that second
        // entry must remove it rather than create a second row, because the distinctive title key
        // recognises them as the same game.
        val title = "Elden Ring Shadow of the Erdtree"
        val steam = WishlistEntry(
            key = com.discounthunter.android.core.text.TitleKey.exact(StoreId.STEAM, 1245620, null, title),
            titleKey = com.discounthunter.android.core.text.TitleKey.normalize(title),
            title = title,
            store = StoreId.STEAM,
            appId = 1245620,
            addedAt = 1,
        )
        val gog = WishlistEntry(
            key = com.discounthunter.android.core.text.TitleKey.exact(StoreId.GOG, null, "elden_ring", title),
            titleKey = com.discounthunter.android.core.text.TitleKey.normalize(title),
            title = title,
            store = StoreId.GOG,
            gogSlug = "elden_ring",
            addedAt = 2,
        )

        val (l1, _) = decisionAdd(emptyList(), steam)
        val (l2, nowSaved) = decisionAdd(l1, gog)

        // The second toggle is a *removal*, not an addition.
        assertEquals(0, l2.size)
        assertEquals(false, nowSaved)
    }

    @Test
    fun `a short title never cross-matches between stores`() {
        // "elden ring" is only 10 chars (< 12), so two distinct store entries must both be kept.
        val title = "Elden Ring"
        val steam = entry(title, 1245620, 1)
        val (l1, _) = decisionAdd(emptyList(), steam)
        val gog = WishlistEntry(
            key = com.discounthunter.android.core.text.TitleKey.exact(StoreId.GOG, null, "elden_ring", title),
            titleKey = com.discounthunter.android.core.text.TitleKey.normalize(title),
            title = title,
            store = StoreId.GOG,
            gogSlug = "elden_ring",
            addedAt = 2,
        )
        val (l2, nowSaved) = decisionAdd(l1, gog)
        assertEquals(2, l2.size)
        assertTrue(nowSaved)
    }

    // ------------------------------------------------ helpers

    /** Calls through to the pure decision function. */
    private fun decisionAdd(
        current: List<WishlistEntry>,
        entry: WishlistEntry,
    ): Pair<List<WishlistEntry>, Boolean> =
        toggleDecision(current, entry, entry.addedAt)

    private fun removeDecisionWrapper(
        current: List<WishlistEntry>,
        exactKey: String,
        titleKey: String,
    ): Pair<List<WishlistEntry>, Boolean> =
        removeDecision(current, exactKey, titleKey)

    private fun toggles(): List<Pair<List<WishlistEntry>, Boolean>> = emptyList()
}
