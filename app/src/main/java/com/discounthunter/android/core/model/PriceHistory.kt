package com.discounthunter.android.core.model

import java.net.URLEncoder

/** One observed price, on a given day. */
data class PricePoint(
    /** Days since the epoch, so points sort and space correctly without a date library. */
    val epochDay: Long,
    val price: Double,
)

/**
 * What the price-history box should render for a given set of [PricePoint]s.
 *
 * This is deliberately a plain function over a list rather than a `when` written inside a composable.
 *
 * ## Why it is not a `when` in the UI
 *
 * The first version read `points.last()` *before* deciding which branch to take, which threw
 * `NoSuchElementException` for an empty list. An empty list is the **normal** case, not an edge one:
 * the box is rendered whenever the game has a store, and only games that have ever appeared in the
 * daily deal snapshots have recorded prices — so every DLC, soundtrack and obscure title took the
 * app down on open.
 *
 * Making the empty case an explicit, named, tested branch is what stops that from coming back.
 */
sealed interface PriceHistorySummary {
    /** Nothing recorded. */
    data object Empty : PriceHistorySummary

    /** A single observation, which is not a trend and is stated in words. */
    data class Single(val price: Double, val daysAgo: Long) : PriceHistorySummary

    /** Two or more observations: draw the chart. */
    data object Chart : PriceHistorySummary
}

/**
 * Chooses how to present [points], where [today] is the current day as an epoch day.
 *
 * Pure and total: it touches no list index without first proving the list is long enough.
 */
fun summarizePriceHistory(points: List<PricePoint>, today: Long): PriceHistorySummary = when {
    points.isEmpty() -> PriceHistorySummary.Empty
    points.size == 1 -> {
        val only = points.first()
        PriceHistorySummary.Single(price = only.price, daysAgo = today - only.epochDay)
    }
    else -> PriceHistorySummary.Chart
}

/**
 * A price-history database this app can send the user to.
 *
 * ## Why these are links and not data sources
 *
 * The obvious implementation is to scrape steamdb, gg.deals, psprices and the rest and draw the
 * chart from their markup. Every one of them refuses that:
 *
 * * steamdb.info, gg.deals and psprices.com sit behind Cloudflare and answer a plain HTTP client
 *   with `403` — verified from a plain request, and again with a browser user-agent.
 * * the IsThereAnyDeal API answers `403` without a paid API key.
 * * gogdb.org serves a product page with no server-rendered price table at all; the prices are
 *   fetched client-side, so there is nothing to read.
 *
 * Scraping them from a phone app would also break their terms and break itself the first time
 * they changed a template. So the app does the opposite of pretending: it draws the history it has
 * genuinely observed itself, and offers one tap to the database that has the rest.
 *
 * [stores] is the rule the detail page filters on — a database is only offered for a game that is
 * actually listed on one of those stores, so an Epic-only game is never sent to SteamDB.
 */
enum class PriceHistorySource(
    /** Shown in the dropdown. */
    val label: String,
    /** Stores this database covers. */
    val stores: Set<StoreId>,
    /** Direct link to the game's own page, when the database addresses games by store id. */
    val appUrl: ((steamAppId: Int?) -> String)? = null,
) {
    STEAMDB(
        label = "SteamDB",
        stores = setOf(StoreId.STEAM),
        appUrl = { appId -> "https://steamdb.info/app/$appId/" },
    ),
    EGDATA(
        label = "EGData",
        stores = setOf(StoreId.EPIC),
    ),
    GOGDB(
        label = "GOG Database",
        stores = setOf(StoreId.GOG),
    ),
    PSPRICES(
        label = "PSPrices",
        stores = setOf(StoreId.STEAM),
    ),
    CHEAPSHARK(
        label = "CheapShark",
        stores = setOf(StoreId.STEAM, StoreId.GOG),
    ),
    ITHEANYDEAL(
        label = "IsThereAnyDeal",
        stores = StoreId.MAJOR.toSet(),
    ),
    GGDEALS(
        label = "GG.deals",
        stores = StoreId.MAJOR.toSet(),
    ),
    DEKUDEALS(
        label = "DeKuDeals",
        stores = StoreId.MAJOR.toSet(),
    ),
}

/**
 * Builds the price-history dropdown for one game.
 *
 * A source appears only when the game is listed on at least one store that source covers, and
 * only when we can actually address the game there — a direct app link is preferred over a search
 * because it lands on the right page instead of a result list.
 */
object PriceHistoryCatalog {

    /** Every source that covers any of [stores], most specific first. */
    fun sourcesFor(stores: Set<StoreId>): List<PriceHistorySource> =
        PriceHistorySource.entries
            .filter { it.stores.any { store -> store in stores } }
            .sortedBy { it.stores.size }

    /** Where to open [source] for this game, or null when it has no usable page. */
    fun urlFor(source: PriceHistorySource, detail: GameDetail): String? {
        source.appUrl?.let { build ->
            val appId = detail.steamAppId
            if (appId != null && appId > 0 && StoreId.STEAM in source.stores) return build(appId)
            return null
        }
        val title = detail.title.trim()
        if (title.isBlank()) return null
        val query = URLEncoder.encode(title, "UTF-8")
        return when (source) {
            PriceHistorySource.EGDATA -> "https://egdata.app/search?q=$query"
            PriceHistorySource.GOGDB -> "https://gogdb.org/products?q=$query"
            PriceHistorySource.ITHEANYDEAL -> "https://isthereanydeal.com/search/?q=$query"
            PriceHistorySource.GGDEALS -> "https://gg.deals/search/?q=$query"
            PriceHistorySource.CHEAPSHARK -> "https://www.cheapshark.com/search?q=$query"
            PriceHistorySource.DEKUDEALS -> "https://www.dekudeals.com/search?q=$query"
            PriceHistorySource.PSPRICES -> "https://psprices.com/steam/search?q=$query"
            PriceHistorySource.STEAMDB -> null
        }
    }
}