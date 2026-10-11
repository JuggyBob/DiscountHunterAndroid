package com.discounthunter.android.data.source

import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.EpicGiveaway
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.Screenshot
import com.discounthunter.android.core.model.ScheduleStatus
import com.discounthunter.android.data.gog.GogGameDetails
import com.discounthunter.android.data.gog.GogProduct
import com.discounthunter.android.data.net.Outcome
import com.discounthunter.android.data.steam.SteamAppDetails
import com.discounthunter.android.data.steam.SteamPageDetails
import com.discounthunter.android.data.steam.SteamSearchHit

/**
 * In-memory store sources for tests.
 *
 * These exist so the media-ownership guarantee can be tested directly: a fake can be told to return
 * *the wrong game* from Steam's search, which is exactly the situation that produced the reported
 * "Elden Ring showing Metal Eden's screenshots" defect — and the situation that cannot be reproduced
 * on demand against the live store.
 */
class FakeSteam(
    /** Search results, matched by lower-cased query. */
    private val searchByQuery: Map<String, List<SteamSearchHit>> = emptyMap(),
    private val detailsByAppId: Map<String, SteamAppDetails> = emptyMap(),
    private val pageByAppId: Map<String, SteamPageDetails> = emptyMap(),
    private val deals: List<Deal> = emptyList(),
    /** When set, [search] returns this instead of consulting [searchByQuery]. */
    private val searchOutcome: Outcome<List<SteamSearchHit>>? = null,
    /** When set, [search] throws it — a store that fails loudly rather than answering. */
    private val searchThrows: Throwable? = null,
) : SteamSource {

    /** App ids whose details were requested, in order. */
    val requestedDetails = mutableListOf<String>()

    /** Queries this source was asked, in order. */
    val requestedSearches = mutableListOf<String>()

    override suspend fun fetchDeals(limit: Int, country: String): Outcome<List<Deal>> =
        Outcome.Success(deals.take(limit))

    override suspend fun search(term: String, country: String): Outcome<List<SteamSearchHit>> {
        requestedSearches += term
        searchThrows?.let { throw it }
        searchOutcome?.let { return it }
        return Outcome.Success(searchByQuery[term.lowercase()] ?: emptyList())
    }

    override suspend fun fetchAppDetails(
        identity: GameIdentity,
        country: String,
    ): Outcome<SteamAppDetails?> {
        requestedDetails += identity.id
        return Outcome.Success(detailsByAppId[identity.id])
    }

    override suspend fun fetchPageDetails(identity: GameIdentity): SteamPageDetails =
        pageByAppId[identity.id] ?: SteamPageDetails()

    override fun storeUrl(identity: GameIdentity) = "https://store.steampowered.com/app/${identity.id}/"
}

class FakeGog(
    private val searchByQuery: Map<String, List<GogProduct>> = emptyMap(),
    private val pageTitles: Map<String, String> = emptyMap(),
    private val detailsBySlug: Map<String, GogGameDetails> = emptyMap(),
    private val deals: List<GogProduct> = emptyList(),
    /** When set, [search] throws it. */
    private val searchThrows: Throwable? = null,
) : GogSource {

    val requestedSearches = mutableListOf<String>()
    val requestedDetails = mutableListOf<String>()

    override suspend fun fetchDeals(limit: Int): Outcome<List<GogProduct>> = Outcome.Success(deals)

    override suspend fun search(term: String, limit: Int): Outcome<List<GogProduct>> {
        requestedSearches += term
        searchThrows?.let { throw it }
        return Outcome.Success(searchByQuery[term.lowercase()] ?: emptyList())
    }

    override suspend fun probePageTitle(slug: String): String = pageTitles[slug].orEmpty()

    override suspend fun fetchGameDetails(identity: GameIdentity): GogGameDetails {
        requestedDetails += identity.id
        return detailsBySlug[identity.id] ?: GogGameDetails()
    }

    override fun pageUrl(identity: GameIdentity) = "https://www.gog.com/en/game/${identity.id}"

    override fun toDeal(product: GogProduct) = Deal(
        id = "gog_${product.identity.id}",
        title = product.title,
        thumbUrl = product.thumbUrl,
        salePrice = product.price,
        normalPrice = if (product.basePrice > 0) product.basePrice else product.price,
        savingsPercent = product.discountPercent,
        store = com.discounthunter.android.core.model.StoreId.GOG,
        identity = product.identity,
        storeUrl = product.url,
    )
}

class FakeEpic(
    private val giveaways: List<EpicGiveaway> = emptyList(),
    /** When set, [fetchFreeGames] returns this instead of [giveaways]. */
    private val giveawaysOutcome: Outcome<List<EpicGiveaway>>? = null,
) : EpicSource {
    override suspend fun fetchFreeGames(country: String): Outcome<List<EpicGiveaway>> =
        giveawaysOutcome ?: Outcome.Success(giveaways)

    override fun pageUrl(identity: GameIdentity) = "https://store.epicgames.com/en-US/p/${identity.id}"
}

// ------------------------------------------------------------------ builders

fun steamHit(
    appId: Int,
    name: String,
    price: Double? = null,
): SteamSearchHit = SteamSearchHit(
    identity = requireNotNull(GameIdentity.steam(appId)) { "appId must be positive: " },
    name = name,
    tinyImage = "https://cdn.steamstatic.com/tiny_$appId.jpg",
    capsuleImage = "https://cdn.steamstatic.com/capsule_$appId.jpg",
    price = price,
)

fun steamScreenshot(appId: Int, index: Int): Screenshot = Screenshot(
    owner = requireNotNull(GameIdentity.steam(appId)) { "appId must be positive: " },
    thumbUrl = "https://cdn.steamstatic.com/ss_${appId}_$index.thumb.jpg",
    fullUrl = "https://cdn.steamstatic.com/ss_${appId}_$index.full.jpg",
)

fun steamTrailer(appId: Int, name: String = "Launch Trailer") =
    com.discounthunter.android.core.model.Trailer(
        owner = requireNotNull(GameIdentity.steam(appId)) { "appId must be positive: " },
        kind = com.discounthunter.android.core.model.TrailerKind.DIRECT_VIDEO,
        name = name,
        thumbUrl = "https://cdn.steamstatic.com/trailer_$appId.jpg",
        streamUrl = "https://cdn.steamstatic.com/video_$appId.mp4",
        webUrl = "https://store.steampowered.com/app/$appId/",
    )

fun steamDetails(
    appId: Int,
    name: String,
    screenshots: List<Screenshot> = emptyList(),
    trailers: List<com.discounthunter.android.core.model.Trailer> = emptyList(),
    shortDescription: String = "",
    detailedDescription: String = "",
    developers: List<String> = emptyList(),
    genres: List<String> = emptyList(),
    releaseDate: String = "",
    minimumRequirements: String = "",
    finalPrice: Double? = null,
    initialPrice: Double? = null,
    discountPercent: Int = 0,
    isFree: Boolean = false,
): SteamAppDetails {
    val identity = requireNotNull(GameIdentity.steam(appId)) { "appId must be positive: $appId" }
    return SteamAppDetails(
        identity = identity,
        name = name,
        shortDescription = shortDescription,
        detailedDescription = detailedDescription,
        headerImage = "https://cdn.steamstatic.com/header_$appId.jpg",
        capsuleImage = "https://cdn.steamstatic.com/capsule_$appId.jpg",
        developers = developers,
        publishers = emptyList(),
        releaseDate = releaseDate,
        genres = genres,
        categories = emptyList(),
        price = finalPrice?.let {
            com.discounthunter.android.core.model.PriceOverview(
                finalPrice = it,
                initialPrice = initialPrice ?: it,
                discountPercent = discountPercent,
                currency = "USD",
            )
        },
        isFree = isFree,
        requirementsMinimum = minimumRequirements,
        requirementsRecommended = "",
        screenshots = screenshots,
        trailers = trailers,
        storeUrl = "https://store.steampowered.com/app/$appId/",
    )
}

fun gogProduct(
    slug: String,
    title: String,
    price: Double = 9.99,
    basePrice: Double = 19.99,
    discountPercent: Double = 50.0,
): GogProduct = GogProduct(
    identity = requireNotNull(GameIdentity.gog(slug)) { "slug must not be blank" },
    title = title,
    thumbUrl = "https://cdn.gog.com/$slug.jpg",
    url = "https://www.gog.com/en/game/$slug",
    price = price,
    basePrice = basePrice,
    discountPercent = discountPercent,
)

fun gogScreenshot(slug: String, index: Int): Screenshot = Screenshot(
    owner = requireNotNull(GameIdentity.gog(slug)) { "slug must not be blank" },
    thumbUrl = "https://cdn.gog.com/${slug}_$index.thumb.jpg",
    fullUrl = "https://cdn.gog.com/${slug}_$index.full.jpg",
)

fun epicGiveaway(
    slug: String,
    title: String,
    description: String = "",
    normalPrice: Double = 19.99,
    status: ScheduleStatus = ScheduleStatus.CURRENT,
): EpicGiveaway = EpicGiveaway(
    title = title,
    description = description,
    thumbUrl = "https://cdn.epicgames.com/$slug.jpg",
    startMillis = 1_700_000_000_000,
    endMillis = 1_700_600_000_000,
    status = status,
    slug = slug,
    url = "https://store.epicgames.com/en-US/p/$slug",
    normalPrice = normalPrice,
)
