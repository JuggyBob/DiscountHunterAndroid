package com.discounthunter.android.core.model

import com.discounthunter.android.core.text.TitleKey

/**
 * One merged cross-store search hit.
 *
 * Results from different stores are merged only when [com.discounthunter.android.core.text.TitleMatcher]
 * proves they are the same product. Two different games that happen to share a title (a remake and
 * its re-release, a bundle and the base game) therefore stay separate rows instead of being
 * collapsed into one card with mixed-up prices.
 */
data class SearchResult(
    val title: String,
    val thumbUrl: String,
    val steamAppId: Int? = null,
    val steamPrice: Double? = null,
    val gogSlug: String? = null,
    val gogPrice: Double? = null,
    val epicSlug: String? = null,
    val epicUrl: String? = null,
    val epicFree: Boolean = false,
    /** True when the title came from a catalogue search rather than an exact title match. */
    val isApproximate: Boolean = false,
) {
    val matchedStores: Set<StoreId> = buildSet {
        if (steamAppId != null) add(StoreId.STEAM)
        if (!gogSlug.isNullOrBlank()) add(StoreId.GOG)
        if (epicFree || !epicSlug.isNullOrBlank()) add(StoreId.EPIC)
    }

    val hasAnyStore: Boolean get() = matchedStores.isNotEmpty()

    /** Cheapest known price; 0 when a store offers the game for free. */
    val cheapestPrice: Double
        get() = when {
            epicFree -> 0.0
            steamPrice != null && gogPrice != null -> minOf(steamPrice, gogPrice)
            steamPrice != null -> steamPrice
            gogPrice != null -> gogPrice
            else -> 0.0
        }

    val hasPrice: Boolean get() = epicFree || steamPrice != null || gogPrice != null

    val isFree: Boolean get() = epicFree || (hasPrice && cheapestPrice <= Deal.FREE_EPSILON)

    /** The store a star pressed on this row is attributed to. */
    val primaryStore: StoreId
        get() = when {
            steamAppId != null -> StoreId.STEAM
            !gogSlug.isNullOrBlank() -> StoreId.GOG
            epicFree -> StoreId.EPIC
            else -> StoreId.STEAM
        }

    val identity: GameIdentity?
        get() = when (primaryStore) {
            StoreId.STEAM -> steamAppId?.let { GameIdentity.steam(it) }
            StoreId.GOG -> gogSlug?.let { GameIdentity.gog(it) }
            StoreId.EPIC -> epicSlug?.let { GameIdentity.epic(it) }
        }

    val webUrl: String
        get() = when {
            steamAppId != null -> "https://store.steampowered.com/app/$steamAppId/"
            !gogSlug.isNullOrBlank() -> "https://www.gog.com/en/game/$gogSlug"
            else -> epicUrl.orEmpty()
        }

    val reference: GameReference?
        get() = identity?.let { GameReference(it, title, thumbUrl) }

    val wishlistKey: String
        get() = identity?.let { TitleKey.exact(it.store, null, it.id, title) }
            ?: TitleKey.exact(primaryStore, steamAppId, gogSlug ?: epicSlug, title)

    val titleKey: String get() = TitleKey.normalize(title)
}

/**
 * A running or scheduled Epic giveaway. Epic publishes no playable media for non-free titles
 * (its product pages block bots), so this feeds the deal list and the calendar markers only.
 */
data class EpicGiveaway(
    val title: String,
    val description: String,
    val thumbUrl: String,
    val startMillis: Long,
    val endMillis: Long,
    val status: ScheduleStatus,
    val slug: String,
    val url: String,
    val normalPrice: Double,
) {
    val identity: GameIdentity? get() = GameIdentity.epic(slug)

    fun toDeal(): Deal? {
        val gameId = identity ?: return null
        return Deal(
            id = "epic_${gameId.id}",
            title = title,
            thumbUrl = thumbUrl,
            salePrice = 0.0,
            normalPrice = normalPrice,
            savingsPercent = 100.0,
            store = StoreId.EPIC,
            identity = gameId,
            storeUrl = url,
            scheduleStatus = status,
        )
    }
}
