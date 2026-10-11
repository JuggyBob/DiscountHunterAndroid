package com.discounthunter.android.core.model

import com.discounthunter.android.core.text.TitleKey

/** Where a deal sits in the Epic giveaway schedule. */
enum class ScheduleStatus { NONE, CURRENT, UPCOMING }

/**
 * A single discounted entry from one storefront.
 *
 * Every deal carries [identity] — the store-native id — when the storefront publishes one. That
 * id, not the title, is what identifies the game everywhere else in the app (navigation, caching,
 * wishlist matching and media ownership).
 */
data class Deal(
    /** Store-specific deal id, e.g. `steam_1245620`. Unique within a feed. */
    val id: String,
    val title: String,
    val thumbUrl: String,
    val salePrice: Double,
    val normalPrice: Double,
    val savingsPercent: Double,
    val store: StoreId,
    val identity: GameIdentity?,
    /** Deep link to the store's own page for this exact product. */
    val storeUrl: String,
    val scheduleStatus: ScheduleStatus = ScheduleStatus.NONE,
    /**
     * The currency this deal's price is actually quoted in, e.g. `"USD"`.
     *
     * Blank means "assume the user's chosen currency", which is right for Steam and Epic because
     * both genuinely re-quote per region. It is **not** right for GOG: the catalogue endpoint
     * ignores a `currency` parameter and keeps returning US dollars, so relabelling a `$0.49` GOG
     * price as `€0.49` states a false price rather than converting it. Where the store tells us the
     * real currency we carry it, and the UI renders that instead of the global setting.
     */
    val currencyCode: String = "",
) {
    val isFree: Boolean get() = savingsPercent >= 99.5 || salePrice <= FREE_EPSILON

    val isUpcoming: Boolean get() = scheduleStatus == ScheduleStatus.UPCOMING

    /** The old price is only worth showing when it is meaningfully higher. */
    val showsOriginalPrice: Boolean
        get() = !isFree && normalPrice > salePrice + PRICE_EPSILON

    /** `-75%`, or `Free` semantics are expressed through [isFree]. */
    val savingsLabel: String
        get() = if (isFree) "-100%" else "-${savingsPercent.toInt().coerceIn(0, 100)}%"

    /** Store-independent wishlist key: exact when an id is known, otherwise the title. */
    val wishlistKey: String
        get() = identity?.let { TitleKey.exact(it.store, null, it.id, title) }
            ?: TitleKey.exact(store, null, null, title)

    val titleKey: String get() = TitleKey.normalize(title)

    companion object {
        const val FREE_EPSILON = 0.001
        const val PRICE_EPSILON = 0.005
    }
}

/**
 * The result of a feed refresh: the deals that loaded plus non-fatal warnings describing which
 * sources were unreachable. Partial data is always better than nothing, so a failing store
 * degrades the feed instead of failing it.
 */
data class DealFeed(
    val deals: List<Deal>,
    val warnings: List<String>,
) {
    val isEmpty: Boolean get() = deals.isEmpty()

    companion object {
        val EMPTY = DealFeed(emptyList(), emptyList())
    }
}
