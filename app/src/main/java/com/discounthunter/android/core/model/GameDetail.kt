package com.discounthunter.android.core.model

import com.discounthunter.android.core.text.TitleKey

data class PriceOverview(
    val finalPrice: Double,
    val initialPrice: Double,
    val discountPercent: Int,
    val currency: String,
) {
    val hasDiscount: Boolean get() = discountPercent > 0 && initialPrice > finalPrice
}

/** One row of the "best price across stores" table. */
data class StoreOffer(
    val store: StoreId,
    val price: Double,
    val retailPrice: Double,
    val savingsPercent: Double,
    val url: String,
    /** Present for Epic rows so the store deep link can be built. */
    val slug: String = "",
    /**
     * The currency this offer is actually quoted in, when the store said.
     *
     * Blank means the store re-quotes for the requested region and the numbers are already in the
     * user's chosen currency — true for Steam, which is asked for `cc=GB` and answers in pounds.
     *
     * GOG publishes in US dollars regardless of region, so its rows carry `"USD"` and the UI
     * converts them. Without this the offers table labelled a dollar price with a euro sign, which
     * is how one game ended up showing three different prices in three places.
     */
    val currencyCode: String = "",
) {
    val isFree: Boolean get() = price <= Deal.FREE_EPSILON

    val showsRetailPrice: Boolean get() = retailPrice > price + Deal.PRICE_EPSILON

    val savingsLabel: String
        get() = if (isFree) "-100%" else "-${savingsPercent.toInt().coerceIn(0, 100)}%"
}

/**
 * Everything a game's detail page needs, merged from the stores that actually answered.
 *
 * [identity] is the identity the page is *about*. Every entry in [screenshots] and [trailers] is
 * guaranteed to be owned by [acceptedIdentities], which is the set the assembler positively
 * verified — either the game is natively on that store, or the store's own search returned a
 * result that [com.discounthunter.android.core.text.TitleMatcher] proved is the same product.
 */
data class GameDetail(
    val identity: GameIdentity?,
    /** Every identity positively matched to this game, across all stores. */
    val acceptedIdentities: Set<GameIdentity>,
    val title: String,
    val heroUrl: String,
    val shortDescription: String,
    val aboutText: String,
    val minimumRequirements: String,
    val recommendedRequirements: String,
    val developers: List<String>,
    val publishers: List<String>,
    val releaseDate: String,
    val genres: List<String>,
    val categories: List<String>,
    val features: List<String>,
    val price: PriceOverview?,
    val isFreeToPlay: Boolean,
    val screenshots: List<Screenshot>,
    val trailers: List<Trailer>,
    val offers: List<StoreOffer>,
    /** Human-readable names of the sources that contributed, shown on the detail page. */
    val sources: List<String>,
    val storeUrls: Map<StoreId, String>,
    val steamAppId: Int?,
    val gogSlug: String,
    val epicSlug: String,
    val epicUpcoming: Boolean,
) {
    /**
     * The cheapest offer, compared in a single currency.
     *
     * ## Why this takes a target
     *
     * Comparing raw `price` across stores only works when they all quote the same currency. They do
     * not: a Steam row asked for `cc=GB` comes back in pounds while a GOG row is always in dollars,
     * so `minBy(price)` was comparing 4.99 pounds against 5.50 dollars and calling the pounds
     * cheaper — which is how a game's "Best:" price disagreed between the wishlist, the feed and
     * the detail page, each of which picked a different winner from the same list.
     *
     * Both sides are converted into [target] first. When no rate is available the raw amount is
     * used, which is the best that can be done without inventing a number.
     */
    fun bestOffer(target: String = "USD", fx: FxRates? = null): StoreOffer? =
        offers.minWithOrNull(
            compareBy(
                { if (it.isFree) 0 else 1 },
                { comparablePrice(it, target, fx) },
                { it.store.ordinal },
            ),
        )

    /** An offer's price expressed in [target], so mixed-currency rows can be ranked together. */
    private fun comparablePrice(offer: StoreOffer, target: String, fx: FxRates?): Double =
        fx?.let { it.convert(offer.price, offer.currencyCode, target) } ?: offer.price

    /**
     * True when at least one store actually told us something about this game.
     *
     * A detail page must never render as a bare title with nothing under it. When every store
     * either has no record of the game or failed to answer, this is false and the page says so
     * explicitly rather than implying the game simply has no screenshots.
     */
    val hasAnyContent: Boolean
        get() = shortDescription.isNotBlank() || aboutText.isNotBlank() ||
            screenshots.isNotEmpty() || trailers.isNotEmpty() ||
            developers.isNotEmpty() || publishers.isNotEmpty() ||
            releaseDate.isNotBlank() || genres.isNotEmpty() || categories.isNotEmpty() ||
            features.isNotEmpty() || price != null || offers.isNotEmpty()
    /** The store a star pressed on this page is attributed to. */
    val primaryStore: StoreId
        get() = when {
            steamAppId != null && steamAppId > 0 -> StoreId.STEAM
            gogSlug.isNotBlank() -> StoreId.GOG
            epicSlug.isNotBlank() -> StoreId.EPIC
            else -> StoreId.STEAM
        }

    val webUrl: String
        get() = StoreId.MAJOR.firstNotNullOfOrNull { storeUrls[it] }.orEmpty()

    val wishlistKey: String
        get() = identity?.let { TitleKey.exact(it.store, null, it.id, title) }
            ?: TitleKey.exact(primaryStore, steamAppId, gogSlug.ifBlank { epicSlug }, title)

    val titleKey: String get() = TitleKey.normalize(title)

    /** Best-effort reference used to open this game again from the wishlist. */
    fun toReference(): GameReference = GameReference(
        identity = identity ?: GameIdentity(primaryStore, steamAppId?.toString() ?: gogSlug.ifBlank { epicSlug }),
        title = title,
        thumbUrl = heroUrl,
    )
}

/**
 * A stable pointer to a game, handed to the detail screen when the user taps a row.
 *
 * Navigation carries this rather than the display title, so the detail page always knows *which*
 * game it is about even while it is still loading, and so a stale title can never be mistaken for
 * the game's identity.
 */
data class GameReference(
    val identity: GameIdentity,
    val title: String,
    val thumbUrl: String = "",
    /** The store URL the user tapped, when the row had one. */
    val originUrl: String = "",
) {
    val store: StoreId get() = identity.store

    /** Best URL we can offer before (or without) loading the full detail. */
    val fallbackUrl: String get() = originUrl.ifBlank {
        when (identity.store) {
            StoreId.STEAM -> "https://store.steampowered.com/app/${identity.id}/"
            StoreId.GOG -> "https://www.gog.com/en/game/${identity.id}"
            StoreId.EPIC -> "https://store.epicgames.com/en-US/p/${identity.id}"
        }
    }
}
