package com.discounthunter.android.data.epic

import com.discounthunter.android.core.model.EpicGiveaway
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.ScheduleStatus
import com.discounthunter.android.core.text.Json
import com.discounthunter.android.data.cache.CacheKeys
import com.discounthunter.android.data.cache.PayloadCache
import com.discounthunter.android.data.net.HttpClient
import com.discounthunter.android.data.net.Outcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads Epic's public free-games feed: the running and upcoming 100%-off giveaways.
 *
 * Epic's product pages block unauthenticated scraping, so a non-free Epic title deliberately shows
 * feed data plus any Steam/GOG matches rather than fabricated page content.
 */
class EpicService(
    private val http: HttpClient,
    private val cache: PayloadCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private companion object {
        const val FEED =
            "https://store-site-backend-static.ak.epicgames.com/freeGamesPromotions"

        /** Ordered by usefulness: a wide card, then a square thumbnail. */
        val PREFERRED_IMAGE_TYPES = listOf(
            "DieselStoreFrontWide",
            "OfferImageWide",
            "DieselGameBoxTall",
            "Thumbnail",
        )
    }

    suspend fun fetchFreeGames(country: String = "US"): Outcome<List<EpicGiveaway>> {
        val key = CacheKeys.epicFreeGames(country)
        val url = "$FEED?locale=en-US&country=$country&allowCountries=$country"

        // Parsing is pushed off the caller's dispatcher on purpose. ViewModels resume on Main, and a
        // multi-megabyte feed parsed there is dropped frames on every refresh.
        val cached = cache.read(key, PayloadCache.Ttl.SHORT_MILLIS)
        val root = cached?.let { withContext(io) { Json.parse(it) } }
            ?: when (val response = http.getJson(url)) {
                is Outcome.Failure -> return response
                is Outcome.Success -> response.value.also { cache.write(key, it.toString()) }
            }

        val elements = Json.path(root, "data", "Catalog", "searchStore", "elements")
        val now = System.currentTimeMillis()
        val result = mutableListOf<EpicGiveaway>()

        // Epic's feed really does return the same product more than once — different catalog
        // namespaces and repeated offer mappings produce several elements for one slug. Two rows
        // for one game means two rows with the same [com.discounthunter.android.core.model.Deal.id],
        // and Compose's lazy lists throw `IllegalArgumentException: Key … was already used` on a
        // duplicate key during layout, which takes the whole screen down rather than just rendering
        // oddly. Keying on the slug here means the duplication never leaves the service.
        val seenSlugs = HashSet<String>()

        for (element in Json.list(elements)) {
            val promotions = Json.obj(element, "promotions") ?: continue
            val match = findOfferWindow(promotions, now) ?: continue
            val (status, start, end) = match

            // Only genuinely free titles belong on the feed.
            val discountPrice = Json.pathDouble(element, 1.0, "price", "totalPrice", "discountPrice")
            if (discountPrice != 0.0) continue

            val identity = extractSlug(element)?.let { GameIdentity.epic(it) } ?: continue
            if (!seenSlugs.add(identity.id)) continue
            result += EpicGiveaway(
                title = Json.str(element, "title", "Epic game"),
                description = Json.str(element, "description"),
                thumbUrl = extractImage(element),
                startMillis = start,
                endMillis = end,
                status = status,
                slug = identity.id,
                url = pageUrl(identity),
                normalPrice = Json.pathDouble(element, 0.0, "price", "totalPrice", "originalPrice") / 100.0,
            )
        }

        // Running giveaways first, upcoming afterwards — a stable, meaningful order.
        return Outcome.Success(
            result.sortedWith(
                compareBy<EpicGiveaway> { if (it.status == ScheduleStatus.CURRENT) 0 else 1 }
                    .thenBy { it.startMillis }
                    .thenBy { it.title },
            ),
        )
    }

    fun pageUrl(identity: GameIdentity): String =
        "https://store.epicgames.com/en-US/p/${identity.id}"

    /**
     * Picks the window that applies right now, or the next upcoming one.
     *
     * A product can carry several promotion groups; the one that matters is the window containing
     * the current instant, and failing that the earliest window that has not started.
     */
    private fun findOfferWindow(
        promotions: JsonObject,
        now: Long,
    ): Triple<ScheduleStatus, Long, Long>? {
        var current: Triple<ScheduleStatus, Long, Long>? = null
        var upcoming: Triple<ScheduleStatus, Long, Long>? = null

        for (groupName in listOf("promotionalOffers", "upcomingPromotionalOffers")) {
            for (group in Json.items(promotions, groupName)) {
                for (offer in Json.items(group, "promotionalOffers")) {
                    val start = Json.timestampMillis(offer, "startDate") ?: continue
                    val end = Json.timestampMillis(offer, "endDate") ?: continue
                    if (end <= start) continue

                    if (start <= now && now <= end) {
                        current = Triple(ScheduleStatus.CURRENT, start, end)
                    } else if (start > now) {
                        val candidate = Triple(ScheduleStatus.UPCOMING, start, end)
                        if (upcoming == null || start < upcoming!!.second) upcoming = candidate
                    }
                }
            }
        }
        return current ?: upcoming
    }

    private fun extractImage(element: JsonElement?): String {
        val byType = LinkedHashMap<String, String>()
        var first = ""
        for (image in Json.items(element, "keyImages")) {
            val url = Json.str(image, "url")
            if (url.isBlank()) continue
            if (first.isBlank()) first = url
            val type = Json.str(image, "type")
            if (type.isNotBlank()) byType.putIfAbsent(type, url)
        }
        return PREFERRED_IMAGE_TYPES.firstNotNullOfOrNull { byType[it] } ?: first
    }

    /**
     * The page slug, which is the only stable identifier the feed publishes. Fallbacks walk the
     * offer mappings and the namespace mappings.
     */
    private fun extractSlug(element: JsonElement?): String? {
        Json.str(element, "productSlug").takeIf { it.isNotBlank() }?.let { return it }

        for (mapping in Json.items(element, "offerMappings")) {
            Json.str(mapping, "pageSlug").takeIf { it.isNotBlank() }?.let { return it }
        }
        val namespace = Json.obj(element, "catalogNs") ?: return null
        for (mapping in Json.items(namespace, "mappings")) {
            Json.str(mapping, "pageSlug").takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}

