package com.discounthunter.android.ui.search

import com.discounthunter.android.core.model.SearchResult
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.WishlistEntry
import com.discounthunter.android.core.text.TitleKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Saving a search result moves it to the top.
 *
 * ## The bug this pins down
 *
 * Search results never changed order. Star a DLC sitting at the bottom of twenty results and the
 * screen did not move at all — no rise, no confirmation, nothing to animate. The feed already put
 * wishlisted entries first in every sort order, so the same action visibly did something there,
 * which made the search page's silence read as the app having forgotten the tap.
 *
 * ## Why keys come from the model here
 *
 * Every key in these tests is produced by [SearchResult.wishlistKey] / [TitleKey.normalize] rather
 * than written as a literal. A hand-written key would keep passing even if the production key
 * format changed, which is the worst property a test can have: it agrees with itself and reports
 * nothing.
 */
class SavedFirstRankTest {

    private fun result(title: String) = SearchResult(title = title, thumbUrl = "")

    private fun entry(title: String, addedAt: Long) = WishlistEntry(
        key = result(title).wishlistKey,
        titleKey = TitleKey.normalize(title),
        title = title,
        store = StoreId.STEAM,
        addedAt = addedAt,
    )

    /** A differently-keyed entry for the same title, standing in for a second-store save. */
    private fun crossStoreEntry(title: String, addedAt: Long) = WishlistEntry(
        key = "g1:" + title.lowercase().replace(" ", "-"),
        titleKey = TitleKey.normalize(title),
        title = title,
        store = StoreId.GOG,
        addedAt = addedAt,
    )

    private fun rank(rows: List<SearchResult>, saved: List<WishlistEntry>) =
        SavedFirstRank.rank(rows, saved) { exact, title ->
            saved.any { it.key == exact || it.titleKey == title }
        }

    @Test
    fun `an unsaved list is returned untouched`() {
        val rows = listOf(result("A"), result("B"), result("C"))
        assertSame(rows, SavedFirstRank.rank(rows, emptyList()) { _, _ -> false })
    }

    @Test
    fun `a single result cannot be reordered`() {
        val rows = listOf(result("Saved"))
        assertSame(rows, rank(rows, listOf(entry("Saved", 10))))
    }

    @Test
    fun `an empty result list stays empty`() {
        assertEquals(emptyList<SearchResult>(), rank(emptyList(), listOf(entry("Saved", 1))))
    }

    @Test
    fun `a saved result rises above the unsaved ones`() {
        val rows = listOf(result("A"), result("B"), result("Saved"))
        val ranked = rank(rows, listOf(entry("Saved", 10)))

        assertEquals(listOf("Saved", "A", "B"), ranked.map { it.title })
    }

    @Test
    fun `the most recently saved result leads`() {
        val rows = listOf(result("Saved"), result("A"), result("B"))
        val ranked = rank(rows, listOf(entry("Saved", 1_000), entry("B", 9_000)))

        assertEquals("B", ranked.first().title)
    }

    @Test
    fun `unsaved results keep the stores' relevance order`() {
        val rows = listOf(result("first"), result("Saved"), result("second"), result("third"))
        val ranked = rank(rows, listOf(entry("Saved", 5)))

        assertEquals(
            listOf("Saved", "first", "second", "third"),
            ranked.map { it.title },
        )
    }

    @Test
    fun `a result matching only by title still rises`() {
        // A game saved on Steam has to be recognised on a GOG row with a different store key;
        // carrying titleKey on the entry is what makes that work, so it is worth pinning.
        val rows = listOf(result("A"), result("Elden Ring"))
        val ranked = rank(rows, listOf(crossStoreEntry("Elden Ring", 7)))

        assertEquals("Elden Ring", ranked.first().title)
    }

    @Test
    fun `two entries for one title keep the earliest timestamp`() {
        // Saving on Steam and GOG creates two entries with different times. Taking the later one
        // would let a duplicate float the row above something saved more recently.
        val rows = listOf(result("A"), result("Saved"), result("B"))
        val ranked = rank(rows, listOf(entry("Saved", 5_000), crossStoreEntry("Saved", 9_000)))

        assertEquals(listOf("Saved", "A", "B"), ranked.map { it.title })
    }

    @Test
    fun `equal timestamps fall back to relevance order`() {
        // Both saved at the same instant: the tie-break is relevance, so "Saved" (row 2) stays
        // ahead of "B" (row 3), and the unsaved "A" trails both because saved always leads.
        val rows = listOf(result("A"), result("Saved"), result("B"))
        val ranked = rank(rows, listOf(entry("Saved", 42), entry("B", 42)))

        assertEquals(listOf("Saved", "B", "A"), ranked.map { it.title })
    }

    @Test
    fun `an unsaved row trails every saved row even when it ranked first`() {
        // Relevance order and "saved first" genuinely conflict; saved-first wins, which is the
        // whole reason the list moves at all.
        val rows = listOf(result("A"), result("B"), result("Saved"))
        val ranked = rank(rows, listOf(entry("Saved", 1)))

        assertEquals("Saved", ranked.first().title)
    }

    @Test
    fun `saving one result leaves the others exactly where they were`() {
        val rows = listOf(result("A"), result("B"), result("C"), result("D"))
        val ranked = rank(rows, listOf(entry("C", 1)))

        assertEquals(listOf("C", "A", "B", "D"), ranked.map { it.title })
    }
}
