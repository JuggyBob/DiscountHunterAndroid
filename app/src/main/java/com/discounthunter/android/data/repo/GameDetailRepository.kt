package com.discounthunter.android.data.repo

import com.discounthunter.android.core.model.GameDetail
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.GameReference
import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.PriceOverview
import com.discounthunter.android.core.model.ScheduleStatus
import com.discounthunter.android.core.model.Screenshot
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.StoreOffer
import com.discounthunter.android.core.model.Trailer
import com.discounthunter.android.core.text.TextTools
import com.discounthunter.android.core.text.TitleMatcher
import com.discounthunter.android.data.epic.EpicService
import com.discounthunter.android.data.gog.GogProduct
import com.discounthunter.android.data.net.valueOrNull
import com.discounthunter.android.data.source.EpicSource
import com.discounthunter.android.data.source.GogSource
import com.discounthunter.android.data.source.SteamSource
import com.discounthunter.android.data.steam.SteamAppDetails
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/**
 * Assembles one game's detail page from every store that can be reached.
 *
 * ## The media-correctness contract
 *
 * This class is the only place screenshots and trailers enter the app, and it maintains one
 * invariant:
 *
 * > Every media item in the returned [GameDetail] is owned by an identity in
 * > [GameDetail.acceptedIdentities].
 *
 * An identity enters that set in exactly two ways:
 *
 *  1. **Natively** — the user opened the game *on that store*, so the store's own id is
 *     authoritative and no title comparison is involved at all.
 *  2. **By proof** — the store was searched by title and a result was accepted only when
 *     [TitleMatcher.isSameGame] confirms it is the same product.
 *
 * Sources that fail to be proven are simply not consulted, and any media they did supply is
 * filtered out. That makes "game A displays game B's screenshots" structurally impossible rather
 * than merely unlikely, and it is exactly the guarantee the desktop build lacked: it accepted a
 * 0.5 edit-distance match, under which `Elden Ring` matched `Metal Eden` (score 0.5556).
 *
 * Every screenshot and trailer is additionally stamped by its *source* with that source's
 * identity, so the final filter is a simple set membership test.
 */
class GameDetailRepository(
    private val steam: SteamSource,
    private val gog: GogSource,
    private val epic: EpicSource,
) {

    private companion object {
        const val MAX_SCREENSHOTS = 16
        const val MAX_TRAILERS = 10
        const val MAX_ABOUT_CHARS = 14_000
        const val PAGE_SCREENSHOT_HINT = 6

        /** Free first, then cheapest; ties broken by store so the order is stable. */
        val OFFER_ORDER: Comparator<StoreOffer> =
            compareBy({ if (it.price <= Deal.FREE_EPSILON) 0 else 1 }, { it.price }, { it.store.ordinal })
    }

    /**
     * Loads everything known about the game behind [reference].
     *
     * Never throws for a partial answer: a title with no Steam app, no screenshots and no trailer
     * still returns a usable page, and unreachable stores simply do not appear in
     * [GameDetail.sources].
     */
    suspend fun load(reference: GameReference, country: String): GameDetail = supervisorScope {
        val accepted = LinkedHashSet<GameIdentity>()
        if (reference.identity.isUsable) accepted += reference.identity

        // Which stores actually *answered* for this game, as opposed to which identity the user
        // happened to open. These are different questions, and conflating them is what left the
        // detail page empty: `accepted` is pre-seeded with the opened identity, so the fallback
        // that re-queries a store by title was skipped for every game opened from that store —
        // including the ones whose id no longer resolves, which is exactly when it is needed.
        val verified = mutableSetOf<StoreId>()

        val sources = mutableListOf<String>()
        val storeUrls = LinkedHashMap<StoreId, String>()
        if (reference.fallbackUrl.isNotBlank()) storeUrls[reference.store] = reference.fallbackUrl

        val offers = mutableListOf<StoreOffer>()

        // ---- native store: no title matching involved, the id is authoritative ----
        var resolvedTitle = reference.title
        var heroUrl = reference.thumbUrl
        var shortDescription = ""
        var aboutText = ""
        var developers = emptyList<String>()
        var publishers = emptyList<String>()
        var releaseDate = ""
        var genres = emptyList<String>()
        var categories = emptyList<String>()
        var features = emptyList<String>()
        var minimumRequirements = ""
        var recommendedRequirements = ""
        var steamPrice: PriceOverview? = null
        var isFreeToPlay = false
        var gogSlug = ""
        var epicSlug = ""
        var epicUpcoming = false

        val nativeScreenshots = mutableListOf<Screenshot>()
        val nativeTrailers = mutableListOf<Trailer>()

        when (reference.store) {
            StoreId.STEAM -> {
                val details = steam.fetchAppDetails(reference.identity, country).valueOrNull()
                if (details != null && details.hasContent) {
                    accepted += details.identity
                    resolvedTitle = details.name
                    heroUrl = details.headerImage.ifBlank { heroUrl }
                    shortDescription = details.shortDescription
                    aboutText = details.detailedDescription
                    developers = details.developers
                    publishers = details.publishers
                    releaseDate = details.releaseDate
                    genres = details.genres
                    categories = details.categories
                    steamPrice = details.price
                    isFreeToPlay = details.isFree
                    minimumRequirements = TextTools.cleanRequirements(details.requirementsMinimum)
                    recommendedRequirements = TextTools.cleanRequirements(details.requirementsRecommended)
                    nativeScreenshots += details.screenshots
                    nativeTrailers += details.trailers
                    storeUrls[StoreId.STEAM] = details.storeUrl
                    sources += "Steam"
                    verified += StoreId.STEAM
                    offers += steamOffer(details)

                    // Steam's JSON API publishes no playable trailer for newer titles, and sometimes
                    // no screenshots at all, so the store page fills those gaps.
                    if (needsStorePage(details)) {
                        val page = steam.fetchPageDetails(reference.identity)
                        if (page.hasContent) {
                            sources += "Steam store page"
                            nativeScreenshots += page.screenshots
                            nativeTrailers += page.trailers
                            if (shortDescription.isBlank()) shortDescription = page.shortDescription
                            if (minimumRequirements.isBlank()) {
                                minimumRequirements = TextTools.cleanRequirements(page.requirementsMinimum)
                            }
                            if (recommendedRequirements.isBlank()) {
                                recommendedRequirements =
                                    TextTools.cleanRequirements(page.requirementsRecommended)
                            }
                        }
                    }
                }
            }

            StoreId.GOG -> {
                // The slug came from a GOG row, so it is authoritative — but the page's own title is
                // still checked, because a slug can have been retired and re-used.
                val pageTitle = gog.probePageTitle(reference.identity.id)
                if (pageTitle.isNotBlank() &&
                    (TitleMatcher.isSameGame(reference.title, pageTitle) ||
                        TitleMatcher.isSameGame(resolvedTitle, pageTitle))
                ) {
                    val details = gog.fetchGameDetails(reference.identity)
                    if (details.hasContent) {
                        accepted += reference.identity
                        resolvedTitle = pageTitle
                        sources += "GOG"
                        verified += StoreId.GOG
                        nativeScreenshots += details.screenshots
                        nativeTrailers += details.trailers
                        features = details.features
                        minimumRequirements = TextTools.cleanRequirements(details.requirementsMinimum)
                        recommendedRequirements =
                            TextTools.cleanRequirements(details.requirementsRecommended)
                        aboutText = details.descriptionHtml.ifBlank { details.overviewHtml }
                        storeUrls[StoreId.GOG] = gog.pageUrl(reference.identity)
                        gogSlug = reference.identity.id
                    }
                }
                // A GOG product price is needed for the offers table.
                gog.search(resolvedTitle, 6).valueOrNull().orEmpty()
                    .firstOrNull { it.identity.id == reference.identity.id }
                    ?.let { product ->
                        offers += gogOffer(product)
                        if (heroUrl.isBlank()) heroUrl = product.thumbUrl
                    }
            }

            StoreId.EPIC -> {
                val giveaway = epic.fetchFreeGames().valueOrNull().orEmpty()
                    .firstOrNull { it.slug == reference.identity.id }
                if (giveaway != null) {
                    accepted += reference.identity
                    resolvedTitle = giveaway.title
                    if (heroUrl.isBlank()) heroUrl = giveaway.thumbUrl
                    shortDescription = giveaway.description
                    aboutText = giveaway.description
                    epicSlug = giveaway.slug
                    epicUpcoming = giveaway.status == ScheduleStatus.UPCOMING
                    storeUrls[StoreId.EPIC] = giveaway.url
                    sources += "Epic feed"
                    verified += StoreId.EPIC
                    offers += StoreOffer(
                        store = StoreId.EPIC,
                        price = 0.0,
                        retailPrice = giveaway.normalPrice,
                        savingsPercent = 100.0,
                        url = giveaway.url,
                        slug = giveaway.slug,
                    )
                }
            }
        }

        // The identity the page is about: prefer the one the user opened.
        val primaryIdentity = reference.identity.takeIf { it.isUsable && it in accepted }
            ?: accepted.firstOrNull()

        // ---- cross-store enrichment, only where the game is not already native ----
        val searchTerm = resolvedTitle.ifBlank { reference.title }
        val crossScreenshots = mutableListOf<Screenshot>()
        val crossTrailers = mutableListOf<Trailer>()

        if (verified.none { it == StoreId.STEAM } && searchTerm.isNotBlank()) {
            val match = steam.search(searchTerm, country).valueOrNull().orEmpty()
                .filter { TitleMatcher.isSameGame(searchTerm, it.name) }
                .maxByOrNull { TitleMatcher.similarity(searchTerm, it.name) }

            if (match != null) {
                // Only now is this app id allowed to contribute media.
                accepted += match.identity
                sources += "Steam"
                storeUrls[StoreId.STEAM] = steam.storeUrl(match.identity)

                val detailsDeferred = async { steam.fetchAppDetails(match.identity, country).valueOrNull() }
                val pageDeferred = async { steam.fetchPageDetails(match.identity) }
                val gogMatchDeferred = async { findGogProduct(searchTerm) }

                val details = detailsDeferred.await()
                val page = pageDeferred.await()
                val gogProduct = gogMatchDeferred.await()

                if (details != null && details.hasContent) {
                    crossScreenshots += details.screenshots
                    crossTrailers += details.trailers
                    if (developers.isEmpty()) developers = details.developers
                    if (publishers.isEmpty()) publishers = details.publishers
                    if (releaseDate.isBlank()) releaseDate = details.releaseDate
                    if (genres.isEmpty()) genres = details.genres
                    if (steamPrice == null) steamPrice = details.price
                    if (minimumRequirements.isBlank()) {
                        minimumRequirements = TextTools.cleanRequirements(details.requirementsMinimum)
                    }
                    if (recommendedRequirements.isBlank()) {
                        recommendedRequirements =
                            TextTools.cleanRequirements(details.requirementsRecommended)
                    }
                    offers += steamOffer(details)
                }
                if (page.hasContent) {
                    crossScreenshots += page.screenshots
                    crossTrailers += page.trailers
                    if (minimumRequirements.isBlank()) {
                        minimumRequirements = TextTools.cleanRequirements(page.requirementsMinimum)
                    }
                    if (recommendedRequirements.isBlank()) {
                        recommendedRequirements =
                            TextTools.cleanRequirements(page.requirementsRecommended)
                    }
                }
                if (gogProduct != null) {
                    offers += gogOffer(gogProduct)
                    storeUrls[StoreId.GOG] = gog.pageUrl(gogProduct.identity)
                    gogSlug = gogProduct.identity.id
                }
            }
        }

        if (verified.none { it == StoreId.GOG } && searchTerm.isNotBlank()) {
            val product = findGogProduct(searchTerm)
            if (product != null) {
                accepted += product.identity
                gogSlug = product.identity.id
                sources += "GOG"
                storeUrls[StoreId.GOG] = gog.pageUrl(product.identity)
                offers += gogOffer(product)

                val details = gog.fetchGameDetails(product.identity)
                if (details.hasContent) {
                    crossScreenshots += details.screenshots
                    crossTrailers += details.trailers
                    if (features.isEmpty()) features = details.features
                    if (aboutText.isBlank()) {
                        aboutText = details.descriptionHtml.ifBlank { details.overviewHtml }
                    }
                    if (minimumRequirements.isBlank()) {
                        minimumRequirements = TextTools.cleanRequirements(details.requirementsMinimum)
                    }
                    if (recommendedRequirements.isBlank()) {
                        recommendedRequirements =
                            TextTools.cleanRequirements(details.requirementsRecommended)
                    }
                }
            }
        }

        if (verified.none { it == StoreId.EPIC } && searchTerm.isNotBlank()) {
            val giveaway = epic.fetchFreeGames().valueOrNull().orEmpty()
                .firstOrNull { TitleMatcher.isSameGame(searchTerm, it.title) }
            val giveawayIdentity = giveaway?.identity
            if (giveaway != null && giveawayIdentity != null) {
                giveaway.identity?.let { accepted += it }
                epicSlug = giveaway.slug
                storeUrls[StoreId.EPIC] = giveaway.url
                sources += "Epic feed"
                offers += StoreOffer(
                    store = StoreId.EPIC,
                    price = 0.0,
                    retailPrice = giveaway.normalPrice,
                    savingsPercent = 100.0,
                    url = giveaway.url,
                    slug = giveaway.slug,
                )
            }
        }

        // ---- media merge, filtered by ownership ----
        val screenshots = (nativeScreenshots + crossScreenshots)
            .filter { it.owner in accepted }
            .distinctBy { it.fullUrl }
            .take(MAX_SCREENSHOTS)

        val trailers = (nativeTrailers + crossTrailers)
            .filter { it.owner in accepted && it.isUsable }
            .distinctBy { it.name to it.kind }
            .take(MAX_TRAILERS)

        if (offers.isEmpty() && reference.fallbackUrl.isNotBlank()) {
            offers += StoreOffer(
                store = reference.store,
                price = 0.0,
                retailPrice = 0.0,
                savingsPercent = 0.0,
                url = reference.fallbackUrl,
            )
        }

        // One row per store, cheapest wins.
        //
        // The same store can legitimately be added twice — the native branch adds an offer even
        // when its title probe failed, and cross-store enrichment adds another when the title
        // matches — which rendered two identical "Where to buy" boxes for a single shop.
        val dedupedOffers = offers
            .groupBy { it.store }
            .map { (_, rows) -> rows.minWithOrNull(OFFER_ORDER) ?: rows.first() }
            .sortedWith(OFFER_ORDER)

        val finalTitle = resolvedTitle.ifBlank { reference.title.ifBlank { "Game details" } }
        if (heroUrl.isBlank()) {
            heroUrl = screenshots.firstOrNull()?.fullUrl.orEmpty()
        }

        GameDetail(
            identity = primaryIdentity,
            acceptedIdentities = accepted.toSet(),
            title = finalTitle,
            heroUrl = heroUrl,
            shortDescription = shortDescription,
            aboutText = TextTools.ellipsize(TextTools.htmlToPlainText(aboutText), MAX_ABOUT_CHARS),
            minimumRequirements = minimumRequirements,
            recommendedRequirements = recommendedRequirements,
            developers = developers,
            publishers = publishers,
            releaseDate = releaseDate,
            genres = genres,
            categories = categories,
            features = features,
            price = steamPrice,
            isFreeToPlay = isFreeToPlay,
            screenshots = screenshots,
            trailers = trailers,
            offers = dedupedOffers,
            sources = sources.distinct(),
            storeUrls = storeUrls,
            steamAppId = accepted.firstOrNull { it.store == StoreId.STEAM }?.id?.toIntOrNull(),
            gogSlug = gogSlug,
            epicSlug = epicSlug,
            epicUpcoming = epicUpcoming,
        )
    }

    // ------------------------------------------------------------------ helpers

    /** The store page is only worth downloading when the API left a real gap. */
    private fun needsStorePage(details: SteamAppDetails): Boolean {
        val hasPlayableTrailer = details.trailers.any { it.canPlayInApp }
        return details.screenshots.size < PAGE_SCREENSHOT_HINT ||
            details.requirementsMinimum.isBlank() ||
            !hasPlayableTrailer
    }

    /** The GOG product for [title], accepted only when the matcher proves it is the same game. */
    private suspend fun findGogProduct(title: String): GogProduct? =
        if (title.isBlank()) {
            null
        } else {
            gog.search(title, 6).valueOrNull().orEmpty()
                .filter { TitleMatcher.isSameGame(title, it.title) }
                .maxByOrNull { TitleMatcher.similarity(title, it.title) }
        }

    private fun steamOffer(details: SteamAppDetails): StoreOffer {
        val price = details.price
        return StoreOffer(
            store = StoreId.STEAM,
            price = if (details.isFree) 0.0 else price?.finalPrice ?: 0.0,
            retailPrice = price?.initialPrice ?: 0.0,
            savingsPercent = price?.discountPercent?.toDouble()
                ?: if (details.isFree) 100.0 else 0.0,
            url = details.storeUrl,
        )
    }

    private fun gogOffer(product: GogProduct): StoreOffer = StoreOffer(
        store = StoreId.GOG,
        price = product.price,
        retailPrice = if (product.basePrice > 0) product.basePrice else product.price,
        savingsPercent = product.discountPercent,
        url = product.url,
        // GOG publishes in dollars whatever region we ask for, so this row has to say so or the
        // offers table will label a dollar amount with the user's currency symbol.
        currencyCode = product.currencyCode.ifBlank { "USD" },
    )
}
