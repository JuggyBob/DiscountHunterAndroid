package com.discounthunter.android.data.steam

import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.PriceOverview
import com.discounthunter.android.core.model.Screenshot
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.Trailer
import com.discounthunter.android.core.model.TrailerKind
import com.discounthunter.android.core.text.Json
import com.discounthunter.android.core.text.TextTools
import com.discounthunter.android.data.cache.CacheKeys
import com.discounthunter.android.data.cache.PayloadCache
import com.discounthunter.android.data.net.HttpClient
import com.discounthunter.android.data.net.Outcome
import com.discounthunter.android.data.net.valueOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * All Steam traffic: featured deals, the discounted search listing, store search and app details.
 *
 * Every response is treated as untrusted: a malformed product is skipped rather than allowed to
 * abort the whole feed, and every payload is cached under a key naming the store, the payload kind
 * and the store-native app id.
 */
class SteamService(
    private val http: HttpClient,
    private val cache: PayloadCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private companion object {
        const val BASE = "https://store.steampowered.com"
        const val MAX_SEARCH_HITS = 20
        const val MAX_API_SCREENSHOTS = 12
        const val MAX_API_TRAILERS = 8
    }

    // ------------------------------------------------------------------ deals

    /**
     * Combines Steam's featured categories with its discounted search listing, skipping app ids
     * already present. Both sources are useful: featured categories are curated and include the
     * daily deal, while the listing adds breadth.
     */
    suspend fun fetchDeals(limit: Int, country: String): Outcome<List<Deal>> {
        val featured = fetchFeatured(country)
        val specials = fetchSpecials(limit, country)

        val merged = LinkedHashMap<Int, Deal>()
        featured.valueOrNull()?.forEach { deal ->
            val appId = deal.identity?.id?.toIntOrNull() ?: return@forEach
            merged.putIfAbsent(appId, deal)
        }
        specials.valueOrNull()?.forEach { deal ->
            val appId = deal.identity?.id?.toIntOrNull() ?: return@forEach
            merged.putIfAbsent(appId, deal)
        }

        val deals = merged.values.toList()
        // Both sources failing is a real failure; one failing is not.
        return when {
            deals.isNotEmpty() -> Outcome.Success(deals)
            featured is Outcome.Failure && specials is Outcome.Failure -> featured
            else -> Outcome.Success(emptyList())
        }
    }

    private suspend fun fetchFeatured(country: String): Outcome<List<Deal>> {
        val key = CacheKeys.steamFeatured(country)
        val root = fetchCachedJson("$BASE/api/featuredcategories/?cc=$country&l=en", key, PayloadCache.Ttl.SHORT_MILLIS)
            ?: return Outcome.Failure(Outcome.Reason.NETWORK, "Steam did not answer.")

        val deals = mutableListOf<Deal>()
        // Curated specials, then the daily deal pinned to the very top, then discounted top
        // sellers for variety.
        deals += parseSpecials(Json.obj(root, "specials"))

        val daily = Json.obj(root, "6")
        if (Json.str(daily, "id") == "cat_dailydeal") {
            deals.addAll(0, parseSpecials(Json.obj(daily, "specials")))
        }

        for (item in Json.items(Json.obj(root, "top_sellers"), "items")) {
            if (Json.bool(item, "discounted")) parseFeaturedItem(item)?.let { deals += it }
        }

        return Outcome.Success(deals.distinctBy { it.id })
    }

    private fun parseSpecials(specials: JsonObject?): List<Deal> =
        Json.items(specials, "items").mapNotNull { parseFeaturedItem(it) }

    private fun parseFeaturedItem(item: JsonElement): Deal? {
        val appId = Json.int(item, "id")
        val identity = GameIdentity.steam(appId) ?: return null

        val final = Json.dbl(item, "final_price") / 100.0
        val original = Json.dbl(item, "original_price") / 100.0
        val thumb = Json.str(item, "header_image").ifBlank { Json.str(item, "large_capsule_image") }

        return Deal(
            id = "steam_$appId",
            title = Json.str(item, "name", "App $appId"),
            thumbUrl = thumb,
            salePrice = final,
            normalPrice = if (original > 0) original else final,
            savingsPercent = Json.dbl(item, "discount_percent"),
            store = StoreId.STEAM,
            identity = identity,
            storeUrl = SteamPageParser.storeUrl(identity),
        )
    }

    /** Steam's own discounted search listing: official HTML wrapped inside a JSON response. */
    private suspend fun fetchSpecials(limit: Int, country: String): Outcome<List<Deal>> {
        val count = limit.coerceIn(10, 100)
        val url = "$BASE/search/results/?query&start=0&count=$count&dynamic_data&sort_by=Price_ASC" +
            "&snr=1_7_7_230_7&filter=topsellers&specials=1&infinite=1&cc=$country&l=en"

        return when (val response = http.getJson(url)) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val html = Json.str(response.value, "results_html")
                Outcome.Success(withContext(io) { parseSpecialsHtml(html) })
            }
        }
    }

    private val SEARCH_ROW = Regex(
        """<a[^>]+data-ds-appid=["'](\d+)["'][^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val SEARCH_TITLE = Regex("""<span class=["']title["']>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val SEARCH_DISCOUNT = Regex("""<div class=["']discount_pct["']>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val SEARCH_ORIGINAL =
        Regex("""<div class=["']discount_original_price["']>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val SEARCH_FINAL =
        Regex("""<div class=["']discount_final_price["']>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val SEARCH_IMAGE = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Parses the discounted listing. Prices come from the store's own markup and are read per row,
     * so one row with a missing price cannot shift every other row's values.
     */
    fun parseSpecialsHtml(html: String): List<Deal> {
        if (html.isBlank()) return emptyList()
        val result = mutableListOf<Deal>()

        for (row in SEARCH_ROW.findAll(html)) {
            val appId = row.groupValues[1].toIntOrNull() ?: continue
            val identity = GameIdentity.steam(appId) ?: continue
            val block = row.groupValues[2]

            val title = TextTools.stripTags(SEARCH_TITLE.find(block)?.groupValues?.get(1))
                .ifBlank { "App $appId" }
            val discount = TextTools.toDouble(
                SEARCH_DISCOUNT.find(block)?.groupValues?.get(1)?.replace("%", ""),
            )
            val original = TextTools.toDouble(SEARCH_ORIGINAL.find(block)?.groupValues?.get(1))
            val final = TextTools.toDouble(SEARCH_FINAL.find(block)?.groupValues?.get(1))
            if (title.isBlank() || (final <= 0 && discount <= 0)) continue

            val savings = when {
                discount > 0 -> discount
                original > 0 && final > 0 -> Math.round((1 - final / original) * 1000) / 10.0
                else -> 0.0
            }

            result += Deal(
                id = "steam_$appId",
                title = title,
                thumbUrl = SEARCH_IMAGE.find(block)?.groupValues?.get(1).orEmpty(),
                salePrice = final,
                normalPrice = if (original > 0) original else final,
                savingsPercent = savings,
                store = StoreId.STEAM,
                identity = identity,
                storeUrl = SteamPageParser.storeUrl(identity),
            )
        }
        return result
    }

    // ------------------------------------------------------------------ search

    suspend fun search(term: String, country: String): Outcome<List<SteamSearchHit>> {
        val query = term.trim()
        if (query.length < 2) return Outcome.Success(emptyList())

        val key = CacheKeys.steamSearch(query, country)
        val root = fetchCachedJson(
            "$BASE/api/storesearch/?term=${encode(query)}&l=en&cc=$country",
            key,
            PayloadCache.Ttl.SEARCH_MILLIS,
        ) ?: return Outcome.Failure(Outcome.Reason.NETWORK, "Steam search did not answer.")

        val hits = mutableListOf<SteamSearchHit>()
        for (item in Json.items(root, "items")) {
            if (hits.size >= MAX_SEARCH_HITS) break
            val identity = GameIdentity.steam(Json.int(item, "id")) ?: continue
            val price = Json.obj(item, "price")?.let { Json.dbl(it, "final") / 100.0 }
                ?.takeIf { it > 0 }
            hits += SteamSearchHit(
                identity = identity,
                name = Json.str(item, "name", "App ${identity.id}"),
                tinyImage = Json.str(item, "tiny_image"),
                capsuleImage = Json.str(item, "small_capsule_image"),
                price = price,
            )
        }
        return Outcome.Success(hits)
    }

    // ------------------------------------------------------------------ details

    /**
     * Fetches `appdetails` for exactly one app id.
     *
     * Steam occasionally answers under a different key than the one requested. Rather than
     * falling back to "the first successful entry" — which would silently attach a *different*
     * game's data to the requested app — the response is accepted only when the key matches the
     * request, or when the payload itself reports the requested `steam_appid`. Anything else is
     * rejected.
     */
    suspend fun fetchAppDetails(identity: GameIdentity, country: String): Outcome<SteamAppDetails?> {
        val appId = identity.id.toIntOrNull()?.takeIf { it > 0 } ?: return Outcome.Success(null)

        val root = fetchCachedJson(
            "$BASE/api/appdetails/?appids=$appId&l=en&cc=$country",
            CacheKeys.steamAppDetails(appId, country),
            PayloadCache.Ttl.DETAILS_MILLIS,
        ) ?: return Outcome.Failure(Outcome.Reason.NETWORK, "Steam did not answer.")

        val data = resolveDataBlock(root, appId) ?: return Outcome.Success(null)
        return Outcome.Success(parseAppDetails(identity, data))
    }

    /**
     * Returns the payload for [appId], or null when the response does not describe that app.
     *
     * Internal rather than private so the rule can be tested directly: it is the gate that stops
     * Steam answering under an unexpected key from attaching another game's data to this app.
     */
    internal fun resolveDataBlock(root: JsonElement, appId: Int): JsonObject? {
        val wrapper = root as? JsonObject ?: return null
        val expectedKey = appId.toString()

        wrapper[expectedKey]?.let { entry ->
            if (Json.bool(entry, "success")) {
                val data = Json.obj(entry, "data")
                // The key matched; also sanity-check the payload's own id when it publishes one.
                val reported = Json.int(data, "steam_appid")
                if (data != null && (reported == 0 || reported == appId)) return data
            }
            return null
        }

        // Fall back to scanning only for an entry that explicitly reports the requested app id.
        for ((key, entry) in wrapper) {
            if (!Json.bool(entry, "success")) continue
            val data = Json.obj(entry, "data") ?: continue
            if (Json.int(data, "steam_appid") == appId || key == expectedKey) return data
        }
        return null
    }

    fun parseAppDetails(identity: GameIdentity, data: JsonObject): SteamAppDetails {
        val trailers = mutableListOf<Trailer>()
        for ((index, movie) in Json.items(data, "movies").withIndex()) {
            if (trailers.size >= MAX_API_TRAILERS) break
            val name = Json.str(movie, "name", "Trailer ${index + 1}")
            val thumb = Json.str(movie, "thumbnail")

            val mp4 = Json.str(Json.obj(movie, "mp4"), "max")
                .ifBlank { Json.str(Json.obj(movie, "mp4"), "480") }

            // Steam has stopped publishing progressive MP4 for many titles and now ships an HLS
            // master playlist instead. The desktop build only understood `mp4`, so every one of
            // those trailers degraded to a "watch on the store page" link. Media3's HLS module is
            // part of this app, so these play in-app exactly as the MP4 ones do.
            val hls = Json.str(movie, "hls_h264")
            val storeUrl = SteamPageParser.storeUrl(identity)

            val trailer = when {
                mp4.isNotBlank() -> Trailer(
                    owner = identity,
                    kind = TrailerKind.DIRECT_VIDEO,
                    name = name,
                    thumbUrl = thumb,
                    streamUrl = mp4,
                    webUrl = storeUrl,
                )

                hls.isNotBlank() -> Trailer(
                    owner = identity,
                    kind = TrailerKind.ADAPTIVE_STREAM,
                    name = name,
                    thumbUrl = thumb,
                    streamUrl = hls,
                    webUrl = storeUrl,
                )

                // No stream at all: keep the entry as a hand-off to the store page rather than
                // showing a play control that could never work.
                thumb.isNotBlank() -> Trailer(
                    owner = identity,
                    kind = TrailerKind.STORE_LINK,
                    name = name,
                    thumbUrl = thumb,
                    streamUrl = "",
                    webUrl = storeUrl,
                )

                else -> null
            }

            if (trailer != null && trailer.isUsable) trailers += trailer
        }

        val screenshots = mutableListOf<Screenshot>()
        for (shot in Json.items(data, "screenshots")) {
            if (screenshots.size >= MAX_API_SCREENSHOTS) break
            val full = Json.str(shot, "path_full")
            if (full.isBlank()) continue
            val thumb = Json.str(shot, "path_thumbnail").ifBlank { full }
            screenshots += Screenshot(identity, thumb, full)
        }

        val requirements = Json.obj(data, "pc_requirements")
        val overview = Json.obj(data, "price_overview")

        return SteamAppDetails(
            identity = identity,
            name = Json.str(data, "name", "App ${identity.id}"),
            shortDescription = Json.str(data, "short_description"),
            detailedDescription = Json.str(data, "detailed_description"),
            headerImage = Json.str(data, "header_image"),
            capsuleImage = Json.str(data, "capsule_image"),
            developers = Json.stringList(data, "developers"),
            publishers = Json.stringList(data, "publishers"),
            releaseDate = Json.str(Json.obj(data, "release_date"), "date"),
            genres = Json.stringList(data, "genres", "description"),
            categories = Json.stringList(data, "categories", "description"),
            price = overview?.takeIf { Json.element(it, "final") != null }?.let {
                PriceOverview(
                    finalPrice = Json.dbl(it, "final") / 100.0,
                    initialPrice = Json.dbl(it, "initial") / 100.0,
                    discountPercent = Json.int(it, "discount_percent"),
                    currency = Json.str(it, "currency", "USD"),
                )
            },
            isFree = Json.bool(data, "is_free"),
            requirementsMinimum = Json.str(requirements, "minimum"),
            requirementsRecommended = Json.str(requirements, "recommended"),
            screenshots = screenshots,
            trailers = trailers,
            storeUrl = SteamPageParser.storeUrl(identity),
        )
    }

    // ------------------------------------------------------------------ store page

    /**
     * Scrapes the store page to fill API gaps. Skipped entirely when the API already supplied
     * screenshots, requirements and a playable trailer, because the page is a large, slow fetch.
     */
    suspend fun fetchPageDetails(identity: GameIdentity): SteamPageDetails {
        val appId = identity.id.toIntOrNull()?.takeIf { it > 0 } ?: return SteamPageDetails()

        val key = CacheKeys.steamStorePage(appId)
        val html = cache.read(key, PayloadCache.Ttl.PAGE_MILLIS)
            ?: http.getText("$BASE/app/$appId/?l=en").let { outcome ->
                when (outcome) {
                    is Outcome.Failure -> return SteamPageDetails()
                    is Outcome.Success -> outcome.value
                }
            }.also { payload ->
                cache.write(key, payload)
            }

        return withContext(io) { SteamPageParser.parse(html, appId) }
    }

    // ------------------------------------------------------------------ shared

    /** Reads through the cache, falling back to a live fetch on a miss or an unreadable file. */
    private suspend fun fetchCachedJson(
        url: String,
        key: com.discounthunter.android.data.cache.CacheKey,
        ttlMillis: Long,
    ): JsonElement? {
        val cached = cache.read(key, ttlMillis)
        if (cached != null) {
            // Parsing is pushed off the caller's dispatcher on purpose; see `EpicService.fetchFreeGames`.
            val parsed = withContext(io) { Json.parse(cached) }
            if (parsed != null) return parsed
        }

        return when (val response = http.getJson(url)) {
            is Outcome.Failure -> null
            is Outcome.Success -> {
                cache.write(key, response.value.toString())
                response.value
            }
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
