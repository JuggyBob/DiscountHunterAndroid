package com.discounthunter.android.data.cache

import java.security.MessageDigest

/**
 * A cache key built from structured parts.
 *
 * Making the key a typed list of parts (rather than a free-form string) is deliberate: it forces
 * every cache entry to be named after the *identity* of the thing it holds — store, kind, the
 * store-native id, and any variant such as the country/locale that changes the payload.
 *
 * This is what stops one game's cached data from ever being served for another game. Titles are
 * never used as cache keys.
 */
data class CacheKey(val parts: List<String>) {

    init {
        require(parts.isNotEmpty() && parts.none { it.isBlank() }) {
            "Cache keys must be non-empty and fully populated; got $parts"
        }
    }

    constructor(vararg parts: String) : this(parts.toList())

    /** A stable, filesystem-safe digest. */
    val digest: String by lazy {
        val bytes = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray())
        bytes.joinToString("") { "%02x".format(it) }
    }

    /** Human-readable form, used in logs and in the settings screen. */
    val readable: String get() = parts.joinToString("/")

    override fun toString(): String = readable
}

/**
 * The complete set of cache keys the app uses.
 *
 * Every key names a store, a payload kind and a stable store-native id. Locale-dependent payloads
 * include the country code, because Steam's prices and availability differ per region.
 */
object CacheKeys {

    // ---- Steam -------------------------------------------------------------------------
    fun steamFeatured(country: String) = CacheKey("steam", "featured", country, "v3")
    fun steamSearch(term: String, country: String) = CacheKey("steam", "search", term.lowercase(), country, "v2")
    fun steamAppDetails(appId: Int, country: String) = CacheKey("steam", "appdetails", appId.toString(), country, "v3")
    fun steamStorePage(appId: Int) = CacheKey("steam", "storepage", appId.toString(), "v3")

    // ---- GOG ---------------------------------------------------------------------------
    fun gogCatalogue(sort: String, limit: Int, query: String?) =
        CacheKey("gog", "catalog", sort, limit.toString(), query?.lowercase() ?: "all", "v3")

    fun gogPage(slug: String) = CacheKey("gog", "page", slug.lowercase(), "v3")
    fun gogPageTitle(slug: String) = CacheKey("gog", "pagetitle", slug.lowercase(), "v2")

    // ---- Epic --------------------------------------------------------------------------
    fun epicFreeGames(country: String) = CacheKey("epic", "freegames", country, "v3")
}
