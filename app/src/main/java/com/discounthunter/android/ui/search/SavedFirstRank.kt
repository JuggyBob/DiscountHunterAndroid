package com.discounthunter.android.ui.search

import com.discounthunter.android.core.model.SearchResult
import com.discounthunter.android.core.model.WishlistEntry

/**
 * Moves saved games to the top of search results, most recently saved first.
 *
 * ## Why search results move at all
 *
 * The deals feed already floats wishlisted entries to the top in every sort order, so a saved game
 * keeps following the user around the app. Search did not, and that read as the app forgetting:
 * you search for a DLC, star it at the bottom of twenty results, and nothing happens — no feedback
 * that it was saved, no change on screen. The row's `animateItem()` was already wired and had
 * nothing to animate, because the order never changed.
 *
 * Saving is a strong signal of intent, so saved results lead. Among themselves they are ordered
 * newest-first, which is what makes the DLC you just starred visibly rise to the top. Everything
 * else keeps the relevance order the stores gave it, because among unsaved results the store's own
 * ranking is still the best answer available.
 *
 * ## Why the deals feed does *not* do this
 *
 * The same rule applied to the feed would reorder ninety grid rows on every star tap, and placement
 * animations that are interrupted that often are a crash risk rather than a nicety (DEV_NOTES
 * §4.30). Search is bounded at 25 rows and the user is usually looking at a handful, so the
 * animations involved are finite. The difference is scale, not taste.
 *
 * Matching is injected rather than reached for so the rule can be tested without a repository, and
 * so there is exactly one definition of "this row is saved" — the same one the row's star uses.
 */
internal object SavedFirstRank {

    fun rank(
        rows: List<SearchResult>,
        saved: List<WishlistEntry>,
        matches: (exactKey: String, titleKey: String) -> Boolean,
    ): List<SearchResult> {
        // Nothing can move if there is nothing saved, or nothing to reorder. Returning the same
        // instance matters: Compose compares list identity, and handing back an equal-but-new list
        // would recompose the whole results column on every settings emission.
        if (rows.size < 2 || saved.isEmpty()) return rows

        // Indexed under *both* keys a row can be matched by. Indexing only the exact key was a real
        // defect: a game saved on GOG matches a Steam row by normalised title, that row moved to the
        // top of the list, and then found no timestamp under its own key and stayed where it was —
        // so the cross-store save, the case titleKey exists for, was the one case that did not work.
        val savedAt = HashMap<String, Long>(saved.size * 2)
        for (entry in saved) {
            // Earliest wins. When two stores matched one title the user may hold two entries for
            // it; the earliest timestamp is the one they meant, and a later duplicate must not push
            // the row up the list as though it had been saved more recently.
            for (key in listOf(entry.key, entry.titleKey)) {
                val previous = savedAt[key]
                if (previous == null || entry.addedAt < previous) savedAt[key] = entry.addedAt
            }
        }

        val decorated = rows.mapIndexed { index, result ->
            val stamp = if (matches(result.wishlistKey, result.titleKey)) {
                savedAt[result.wishlistKey] ?: savedAt[result.titleKey]
            } else {
                null
            }
            Ranked(result, index, stamp)
        }

        val ordered = decorated.sortedWith(
            compareByDescending<Ranked> { it.savedAt != null }
                // Newest saved first: the row the user just starred lands at the very top.
                .thenByDescending { it.savedAt ?: 0L }
                // Everything else — and saved rows sharing a timestamp — keeps relevance order.
                .thenBy { it.index },
        )
        return ordered.map { it.result }
    }

    private data class Ranked(
        val result: SearchResult,
        val index: Int,
        val savedAt: Long?,
    )
}
