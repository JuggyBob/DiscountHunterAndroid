package com.discounthunter.android.alerts

import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.WishlistEntry

/**
 * Works out what changed since the last check.
 *
 * ## The rule
 *
 * A check produces the same list of deals over and over, so "what is on sale" is not the question.
 * The question is **"what is different from last time"**, and there are exactly two kinds of
 * difference worth telling someone about:
 *
 *  * a game on the wishlist that has **started** being discounted, and
 *  * a game that has **become free**.
 *
 * Both are transitions, not states. A wishlisted game that is still 50% off next hour is not news,
 * and announcing it again would train the user to swipe the notification away without reading it.
 *
 * ## Absence is not evidence
 *
 * When a wishlisted game is missing from the feed, its fingerprint is **left alone**. The feed only
 * holds the top few dozen discounts per store, so a game dropping out of it says nothing about
 * whether it is still discounted. Clearing the record on absence would mean the game could
 * announce itself again the next time it appeared, unchanged.
 *
 * The record is only cleared when the game is **seen and is not discounted** — that is the one
 * observation that actually proves the sale ended, and therefore that a later discount is news.
 *
 * ## The first run
 *
 * The very first check has nothing to compare against, so it announces nothing. It records what it
 * saw instead, and from then on only changes are announced.
 *
 * That is the honest reading of "new free games" and "goes on sale": without a previous
 * observation, nothing can be shown to have *become* anything. It also avoids a first-run flood of
 * one notification per free game currently on offer, which is how a feature like this gets turned
 * off before it is ever used.
 */
object SaleDetector {

    /**
     * Compares [deals] against [state] and the user's [wishlist].
     *
     * [matches] decides whether a deal is the same game as a wishlist entry. It is injected so the
     * rule lives in exactly one place — [com.discounthunter.android.data.wishlist.representsSame]
     * — and so this function stays pure and testable without a repository.
     */
    fun detect(
        wishlist: List<WishlistEntry>,
        deals: List<Deal>,
        state: WatchState,
        matches: (entry: WishlistEntry, deal: Deal) -> Boolean,
    ): Detection {
        val firstRun = !state.initialised
        val announced = state.announcedSales.toMutableMap()
        val seenFree = LinkedHashSet(state.seenFreeGames)
        val sales = mutableListOf<SaleAlert>()
        val freeGames = mutableListOf<FreeGameAlert>()

        // ---- wishlist games that have started being discounted ----
        for (entry in wishlist) {
            val deal = deals.firstOrNull { matches(entry, it) } ?: continue

            if (deal.isFree) {
                // A free game is announced by the free-game path below, which covers wishlisted
                // games too. Announcing it here as well would tell the user twice about the same
                // thing, once as "is free" and once as "100% off" — and the second one is the
                // less useful of the two.
                //
                // The record is cleared, so that if the game later becomes a *paid* discount, that
                // is announced as a sale.
                announced.remove(entry.key)
                continue
            }

            if (deal.savingsPercent <= 0.0) {
                // Seen, and not discounted: the sale has ended, so a later one is news again.
                announced.remove(entry.key)
                continue
            }

            val fingerprint = fingerprint(deal)
            if (announced[entry.key] == fingerprint) continue

            announced[entry.key] = fingerprint
            if (!firstRun) {
                sales += SaleAlert(
                    identityKey = entry.key,
                    title = deal.title.ifBlank { entry.title },
                    store = deal.store,
                    identity = deal.identity ?: entry.identityOrNull(),
                    price = deal.salePrice,
                    originalPrice = deal.normalPrice,
                    discountPercent = deal.savingsPercent,
                    currencyCode = deal.currencyCode,
                    storeUrl = deal.storeUrl,
                    thumbUrl = deal.thumbUrl,
                )
            }
        }

        // ---- games that have become free ----
        for (deal in deals) {
            if (!deal.isFree) continue
            val key = deal.id
            if (!seenFree.add(key)) continue
            if (!firstRun) {
                freeGames += FreeGameAlert(
                    identityKey = key,
                    title = deal.title,
                    store = deal.store,
                    identity = deal.identity,
                    storeUrl = deal.storeUrl,
                    thumbUrl = deal.thumbUrl,
                )
            }
        }

        // Bound the free-game memory. The insertion order is preserved, so the oldest ids are the
        // ones dropped.
        val trimmed = if (seenFree.size > WatchState.MAX_SEEN_FREE_GAMES) {
            seenFree.toList().takeLast(WatchState.MAX_SEEN_FREE_GAMES).toSet()
        } else {
            seenFree
        }

        return Detection(
            sales = sales,
            freeGames = freeGames,
            nextState = WatchState(
                initialised = true,
                announcedSales = announced,
                seenFreeGames = trimmed,
            ),
        )
    }

    /**
     * Identifies a sale.
     *
     * The price and the percentage are both in it, so a re-listed game at a different price
     * announces itself again — which is the point — while the same sale seen twice does not.
     */
    private fun fingerprint(deal: Deal): String =
        "${deal.id}|${deal.salePrice}|${deal.savingsPercent}"

    /** The wishlist entry's own identity, when it has one. */
    private fun WishlistEntry.identityOrNull() =
        com.discounthunter.android.core.model.GameIdentity(
            store = store,
            id = appId?.toString() ?: gogSlug,
        ).takeIf { it.id.isNotBlank() }
}
