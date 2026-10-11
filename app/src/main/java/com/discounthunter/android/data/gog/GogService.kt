package com.discounthunter.android.data.gog

import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.text.Json
import com.discounthunter.android.core.text.TextTools
import com.discounthunter.android.data.cache.CacheKeys
import com.discounthunter.android.data.cache.PayloadCache
import com.discounthunter.android.data.net.HttpClient
import com.discounthunter.android.data.net.Outcome
import com.discounthunter.android.data.net.valueOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * All GOG traffic: the discounted catalogue, catalogue search and game-page parsing.
 */
class GogService(
    private val http: HttpClient,
    private val cache: PayloadCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private companion object {
        const val CATALOG = "https://catalog.gog.com/v1/catalog"
        const val GAME_PAGE = "https://www.gog.com/en/game/"
        const val MAX_SEARCH_RESULTS = 24
        const val MAX_CACHED_PAGE_CHARS = 2_500_000

        /** Words that make a GOG full-text query useless on its own. */
        val SEARCH_NOISE = setOf(
            "the", "and", "for", "with", "from", "pack", "edition", "collection", "deluxe",
            "bundle", "game", "ultimate", "complete", "definitive", "remastered", "enhanced",
            "extended", "simulator", "volume", "chapter", "episode", "part", "digital", "supporter",
        )
    }

    // ------------------------------------------------------------------ deals

    /** The catalogue ordered by deepest discount. */
    suspend fun fetchDeals(limit: Int): Outcome<List<GogProduct>> =
        fetchCatalogue(
            limit = limit.coerceIn(5, 60),
            sort = "desc:discount",
            query = null,
            key = CacheKeys.gogCatalogue("discount", limit.coerceIn(5, 60), null),
        )

    // ------------------------------------------------------------------ search

    /**
     * Full-text catalogue search.
     *
     * The index ranks single distinctive words well but returns generic bestsellers for
     * multi-word queries, so the full query is unioned with the strongest individual words. The
     * caller then applies [com.discounthunter.android.core.text.TitleMatcher] before showing
     * anything, which is what stops one game's search from filling the page with unrelated titles.
     */
    suspend fun search(term: String, limit: Int = 10): Outcome<List<GogProduct>> {
        val query = term.trim()
        if (query.length < 2) return Outcome.Success(emptyList())

        val bounded = limit.coerceIn(3, 20)
        val seen = LinkedHashMap<String, GogProduct>()

        fetchCatalogue(bounded, "relevance", query, CacheKeys.gogCatalogue("search", bounded, query))
            .valueOrNull()
            ?.forEach { product -> seen.putIfAbsent(product.identity.key, product) }

        val words = TextTools.words(query)
        val distinct = words
            .filter { it.length >= 4 && it.lowercase() !in SEARCH_NOISE }
            .sortedByDescending { it.length }
            .take(3)

        val fallback = distinct.ifEmpty {
            words.filter { it.length >= 3 }.sortedByDescending { it.length }.take(2)
        }

        for (word in fallback) {
            if (seen.size >= MAX_SEARCH_RESULTS) break
            fetchCatalogue(
                limit = 8,
                sort = "relevance",
                query = word,
                key = CacheKeys.gogCatalogue("search", 8, word),
            ).valueOrNull()?.forEach { product -> seen.putIfAbsent(product.identity.key, product) }
        }

        return Outcome.Success(seen.values.take(MAX_SEARCH_RESULTS).toList())
    }

    // ------------------------------------------------------------------ game page

    /**
     * Reads the page title only — a cheap probe used to confirm a slug really is the game we
     * think it is *before* downloading and trusting its media.
     */
    suspend fun probePageTitle(slug: String): String {
        val identity = GameIdentity.gog(slug) ?: return ""
        val key = CacheKeys.gogPageTitle(identity.id)

        cache.read(key, PayloadCache.Ttl.PROBE_MILLIS)?.let { return it }

        val html = http.getText(GAME_PAGE + identity.id).let { outcome ->
            when (outcome) {
                is Outcome.Failure -> return ""
                is Outcome.Success -> outcome.value
            }
        }
        // An unknown slug redirects to the storefront listing rather than a game page.
        if (!html.contains("/game/")) return ""

        val title = GogPageParser.parsePageTitle(html)
        if (title.isNotBlank()) cache.write(key, title)
        return title
    }

    suspend fun fetchGameDetails(identity: GameIdentity): GogGameDetails {
        if (!identity.isUsable) return GogGameDetails()
        val key = CacheKeys.gogPage(identity.id)

        val html = cache.read(key, PayloadCache.Ttl.PAGE_MILLIS)
            ?: http.getText(GAME_PAGE + identity.id).let { outcome ->
                when (outcome) {
                    is Outcome.Failure -> return GogGameDetails()
                    is Outcome.Success -> outcome.value
                }
            }.also { payload ->
                // GOG pages are large; cap what is persisted so the cache stays bounded.
                cache.write(key, payload.take(MAX_CACHED_PAGE_CHARS))
            }

        if (!html.contains("/game/")) return GogGameDetails()

        // Screenshots and trailers are stamped with the slug we asked for, so even a mis-routed
        // response cannot contribute media to a different game.
        return withContext(io) { GogPageParser.parse(html, identity) }
    }

    /** Builds the store's own page URL for a slug. */
    fun pageUrl(identity: GameIdentity): String =
        if (identity.isUsable) GAME_PAGE + identity.id else "https://www.gog.com/games"

    // ------------------------------------------------------------------ shared

    private suspend fun fetchCatalogue(
        limit: Int,
        sort: String,
        query: String?,
        key: com.discounthunter.android.data.cache.CacheKey,
    ): Outcome<List<GogProduct>> {
        val url = buildString {
            append(CATALOG).append("?limit=").append(limit)
            append("&order=").append(sort)
            if (!query.isNullOrBlank()) append("&query=").append(encode(query))
        }

        // Parsing is pushed off the caller's dispatcher on purpose; see `EpicService.fetchFreeGames`.
        val cached = cache.read(key, PayloadCache.Ttl.SHORT_MILLIS)
        val root = cached?.let { withContext(io) { Json.parse(it) } }
            ?: when (val response = http.getJson(url)) {
                is Outcome.Failure -> return response
                is Outcome.Success -> response.value.also { cache.write(key, it.toString()) }
            }

        val products = (root as? JsonObject)?.get("products") as? JsonArray ?: return Outcome.Success(emptyList())
        val parsed = products.mapNotNull { parseProduct(it as? JsonObject) }
        return Outcome.Success(parsed)
    }

    fun parseProduct(product: JsonObject?): GogProduct? {
        if (product == null) return null
        val title = Json.str(product, "title")
        if (title.isBlank()) return null

        val slug = Json.str(product, "slug")
        val identity = GameIdentity.gog(slug) ?: return null

        val price = Json.obj(product, "price")
        var final = 0.0
        var base = 0.0
        var discount = 0.0
        var currency = ""

        if (price != null) {
            // GOG publishes `finalMoney`/`baseMoney` objects; the flat `final`/`base` numbers are the
            // fallback for older catalogue entries.
            val finalMoney = Json.obj(price, "finalMoney")
            val baseMoney = Json.obj(price, "baseMoney")
            final = finalMoney?.let { Json.dbl(it, "amount") } ?: Json.dbl(price, "final")
            base = baseMoney?.let { Json.dbl(it, "amount") } ?: Json.dbl(price, "base")

            // Which currency that number is actually in. The catalogue ignores a `currency` query
            // parameter, so without this the app would print a dollar amount with a euro sign.
            currency = Json.str(finalMoney, "currency")
                .ifBlank { Json.str(baseMoney, "currency") }

            val discountText = Json.str(price, "discount")
            discount = if (discountText.isNotBlank()) {
                TextTools.toDouble(discountText.removePrefix("-"))
            } else {
                Json.dbl(price, "discount")
            }
        }

        if (discount <= 0 && base > 0 && final >= 0) {
            discount = Math.round((1 - final / base) * 1000) / 10.0
        }

        return GogProduct(
            identity = identity,
            title = title.trim(),
            thumbUrl = TextTools.resolveImageFormatter(Json.str(product, "coverHorizontal"), "w_679"),
            url = Json.str(product, "storeLink").ifBlank { pageUrl(identity) },
            price = final,
            basePrice = base,
            discountPercent = discount,
            currencyCode = currency,
        )
    }

    fun toDeal(product: GogProduct): com.discounthunter.android.core.model.Deal =
        com.discounthunter.android.core.model.Deal(
            id = "gog_${product.identity.id}",
            title = product.title,
            thumbUrl = product.thumbUrl,
            salePrice = product.price,
            normalPrice = if (product.basePrice > 0) product.basePrice else product.price,
            savingsPercent = product.discountPercent,
            store = StoreId.GOG,
            identity = product.identity,
            storeUrl = product.url,
            currencyCode = product.currencyCode,
        )

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
