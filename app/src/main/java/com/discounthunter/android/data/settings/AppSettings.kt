package com.discounthunter.android.data.settings

import com.discounthunter.android.core.model.StoreId

/** Appearance preference. [SYSTEM] follows the platform's dark-mode setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Display currency.
 *
 * Each value carries the Steam country code it implies, because Steam prices and availability are
 * region-specific — the desktop app used exactly this pairing.
 */
enum class CurrencyCode(val code: String, val symbol: String, val steamCountry: String) {
    USD("USD", "$", "US"),
    EUR("EUR", "\u20AC", "DE"),
    GBP("GBP", "\u00A3", "GB"),
}

/** Everything the user can configure, plus the two collections that travel with those settings. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val currency: CurrencyCode = CurrencyCode.USD,
    val enabledStores: Set<StoreId> = StoreId.MAJOR.toSet(),
    /** How many deals to request per store. */
    val dealLimit: Int = DEFAULT_DEAL_LIMIT,
    /** Start a trailer as soon as its player opens. */
    val autoplayTrailers: Boolean = false,
    /** Prefer a store's own app over the browser when both are available. */
    val preferStoreApps: Boolean = true,
    val recentSearches: List<String> = emptyList(),
    val linkedAccounts: Map<StoreId, String> = emptyMap(),
    val wishlist: List<WishlistEntryRecord> = emptyList(),
) {
    val steamCountry: String get() = currency.steamCountry
    val currencySymbol: String get() = currency.symbol

    fun accountFor(store: StoreId): String? = linkedAccounts[store]?.takeIf { it.isNotBlank() }

    companion object {
        const val DEFAULT_DEAL_LIMIT = 40
        const val MIN_DEAL_LIMIT = 10
        const val MAX_DEAL_LIMIT = 60
        const val MAX_RECENT_SEARCHES = 10
    }
}

/**
 * The persisted shape of a wishlist entry.
 *
 * Kept separate from the domain model so the storage schema can change without a migration, and so
 * malformed stored rows can be rejected at the boundary.
 */
data class WishlistEntryRecord(
    val key: String,
    val titleKey: String,
    val title: String,
    val storeId: Int,
    val appId: Int? = null,
    val gogSlug: String = "",
    val thumbUrl: String = "",
    val url: String = "",
    val addedAt: Long,
)

/** The result of a search-term insertion, so the caller can persist exactly what was stored. */
fun AppSettings.withSearch(query: String): AppSettings {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    val updated = recentSearches.filterNot { it.equals(trimmed, ignoreCase = true) }
    return copy(recentSearches = (listOf(trimmed) + updated).take(AppSettings.MAX_RECENT_SEARCHES))
}

fun AppSettings.withDealLimit(limit: Int): AppSettings = copy(
    dealLimit = limit.coerceIn(AppSettings.MIN_DEAL_LIMIT, AppSettings.MAX_DEAL_LIMIT),
)
