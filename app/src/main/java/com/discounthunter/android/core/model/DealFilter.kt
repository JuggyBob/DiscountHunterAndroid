package com.discounthunter.android.core.model

/** Minimum discount a deal must have to survive the filter. */
enum class DiscountFilter(val label: String, val minimumPercent: Double) {
    ALL("All discounts", 0.0),
    FREE_ONLY("Free only", 100.0),
    OVER_75("75% and up", 75.0),
    OVER_50("50% and up", 50.0),
    OVER_25("25% and up", 25.0),
}

/** The order the deal list is presented in. */
enum class SortOrder(val label: String) {
    BIGGEST_DISCOUNT("Biggest discount"),
    LOWEST_PRICE("Lowest price"),
    TITLE_ASCENDING("Title (A\u2013Z)"),
}

/**
 * Local, in-memory narrowing and ordering of an already-loaded feed.
 *
 * All of it runs on data that is already in memory, so changing a filter never triggers a network
 * request. Sorting is total: every comparator ends in a stable tiebreaker on the store-native deal
 * id, so the same feed always renders in exactly the same order.
 */
object DealFilter {

    fun apply(
        deals: List<Deal>,
        enabledStores: Set<StoreId>,
        discount: DiscountFilter,
        titleQuery: String,
        sort: SortOrder,
        isWishlisted: (Deal) -> Boolean,
    ): List<Deal> {
        val needle = titleQuery.trim().lowercase()

        val filtered = deals.filter { deal ->
            deal.store in enabledStores &&
                matchesDiscount(deal, discount) &&
                (needle.isEmpty() || deal.title.lowercase().contains(needle))
        }

        // Saved games float to the top (that is the point of saving them), then free games, then
        // the user's chosen order within each band.
        val banded = when (sort) {
            SortOrder.LOWEST_PRICE -> filtered.sortedWith(
                compareBy<Deal> { if (isWishlisted(it)) 0 else 1 }
                    .thenBy { if (it.isFree) 0 else 1 }
                    .thenBy { it.salePrice }
                    .thenByDescending { it.savingsPercent }
                    .thenBy { it.id },
            )
            SortOrder.TITLE_ASCENDING -> filtered.sortedWith(
                compareBy<Deal> { if (isWishlisted(it)) 0 else 1 }
                    .thenBy { if (it.isFree) 0 else 1 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    .thenBy { it.id },
            )
            SortOrder.BIGGEST_DISCOUNT -> filtered.sortedWith(
                compareBy<Deal> { if (isWishlisted(it)) 0 else 1 }
                    .thenBy { if (it.isFree) 0 else 1 }
                    .thenByDescending { it.savingsPercent }
                    .thenBy { it.salePrice }
                    .thenBy { it.id },
            )
        }
        return banded
    }

    fun matchesDiscount(deal: Deal, filter: DiscountFilter): Boolean = when (filter) {
        DiscountFilter.ALL -> true
        DiscountFilter.FREE_ONLY -> deal.isFree
        // A free game satisfies every threshold, so `>= 25` includes freebies as a user expects.
        else -> deal.isFree || deal.savingsPercent >= filter.minimumPercent
    }
}
