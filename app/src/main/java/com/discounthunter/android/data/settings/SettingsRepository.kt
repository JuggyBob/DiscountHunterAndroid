package com.discounthunter.android.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.WishlistEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "discount_hunter_settings",
)

/**
 * Reads and writes [AppSettings].
 *
 * Exposed as a [Flow] so a change made on the Settings screen immediately reaches the deal feed
 * (store toggles) and the search screen (currency, recent searches) without any manual plumbing.
 *
 * Reads are total: a corrupt preferences file yields defaults instead of an exception, because
 * settings are a convenience and must never stop the app from starting.
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it.toSettings() }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME] = mode.name }

    suspend fun setCurrency(currency: CurrencyCode) = edit { it[KEY_CURRENCY] = currency.code }

    suspend fun setEnabledStores(stores: Set<StoreId>) = edit { preferences ->
        // Never persist an empty selection: the user would see an unexplained empty feed.
        val safe = stores.ifEmpty { StoreId.MAJOR.toSet() }
        preferences[KEY_STORES] = encodeStrings(safe.map { it.id.toString() })
    }

    suspend fun setDealLimit(limit: Int) = edit { preferences ->
        preferences[KEY_DEAL_LIMIT] = limit.coerceIn(AppSettings.MIN_DEAL_LIMIT, AppSettings.MAX_DEAL_LIMIT)
    }

    suspend fun setAutoplayTrailers(enabled: Boolean) = edit { it[KEY_AUTOPLAY] = enabled }

    suspend fun setPreferStoreApps(enabled: Boolean) = edit { it[KEY_STORE_APPS] = enabled }

    suspend fun rememberSearch(query: String) = edit { preferences ->
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@edit
        val existing = decodeSearches(preferences[KEY_SEARCHES])
        val updated = existing.filterNot { it.equals(trimmed, ignoreCase = true) }
        preferences[KEY_SEARCHES] = encodeStrings((listOf(trimmed) + updated).take(AppSettings.MAX_RECENT_SEARCHES))
    }

    suspend fun setLinkedAccount(store: StoreId, account: String?) = edit { preferences ->
        val current = LinkedHashMap(decodeAccounts(preferences[KEY_ACCOUNTS]))
        val clean = account?.trim()?.take(64).orEmpty()
        if (clean.isEmpty()) current.remove(store) else current[store] = clean
        preferences[KEY_ACCOUNTS] = encodeAccounts(current)
    }

    suspend fun setWishlist(entries: List<WishlistEntry>) = edit { preferences ->
        preferences[KEY_WISHLIST] = encodeWishlist(entries)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        try {
            dataStore.edit(block)
        } catch (_: Exception) {
            // Failing to persist a preference must never break the interaction that caused it.
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val stores = decodeSearches(this[KEY_STORES]).mapNotNull { it.toIntOrNull() }.mapNotNull { StoreId.fromId(it) }.toSet()
        return AppSettings(
            themeMode = this[KEY_THEME]?.let { name ->
                runCatching { ThemeMode.valueOf(name) }.getOrNull()
            } ?: ThemeMode.SYSTEM,
            currency = this[KEY_CURRENCY]?.let { code ->
                CurrencyCode.entries.firstOrNull { it.code == code }
            } ?: CurrencyCode.USD,
            enabledStores = stores?.takeIf { it.isNotEmpty() } ?: StoreId.MAJOR.toSet(),
            dealLimit = (this[KEY_DEAL_LIMIT] ?: AppSettings.DEFAULT_DEAL_LIMIT)
                .coerceIn(AppSettings.MIN_DEAL_LIMIT, AppSettings.MAX_DEAL_LIMIT),
            autoplayTrailers = this[KEY_AUTOPLAY] ?: false,
            preferStoreApps = this[KEY_STORE_APPS] ?: true,
            recentSearches = decodeSearches(this[KEY_SEARCHES]),
            linkedAccounts = decodeAccounts(this[KEY_ACCOUNTS]),
            wishlist = decodeWishlist(this[KEY_WISHLIST]),
        )
    }

    // ------------------------------------------------------------------ encoding
    //
    // Collections are stored as compact JSON strings rather than as DataStore string sets, so the
    // order is preserved (recent searches are ordered) and entries stay compact.

    private fun encodeStrings(values: List<String>): String =
        JsonArray(values.map { JsonPrimitive(it) }).toString()

    private fun decodeSearches(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).let { it as? JsonArray }.orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
                .filter { it.isNotBlank() }
                .take(AppSettings.MAX_RECENT_SEARCHES)
        }.getOrDefault(emptyList())
    }

    private fun encodeAccounts(accounts: Map<StoreId, String>): String =
        JsonArray(
            accounts.map { (store, name) -> JsonPrimitive("${store.id}:$name") },
        ).toString()

    private fun decodeAccounts(raw: String?): Map<StoreId, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyMap()
            array.mapNotNull { element ->
                val text = (element as? JsonPrimitive)?.content ?: return@mapNotNull null
                val separator = text.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val store = StoreId.fromId(text.substring(0, separator).toIntOrNull() ?: -1)
                val name = text.substring(separator + 1).trim()
                if (store == null || name.isEmpty()) null else store to name
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encodeWishlist(entries: List<WishlistEntry>): String =
        JsonArray(
            entries.map { entry ->
                JsonObject(
                    mapOf(
                        "k" to JsonPrimitive(entry.key),
                        "t" to JsonPrimitive(entry.titleKey),
                        "n" to JsonPrimitive(entry.title),
                        "s" to JsonPrimitive(entry.store.id),
                        "a" to (entry.appId?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?)),
                        "g" to JsonPrimitive(entry.gogSlug),
                        "i" to JsonPrimitive(entry.thumbUrl),
                        "u" to JsonPrimitive(entry.url),
                        "d" to JsonPrimitive(entry.addedAt),
                    ),
                )
            },
        ).toString()

    private fun decodeWishlist(raw: String?): List<WishlistEntryRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                val json = element as? JsonObject ?: return@mapNotNull null
                val title = json.string("n")
                val key = json.string("k")
                val titleKey = json.string("t")
                // An entry with neither key cannot be matched, so it is dropped rather than kept.
                if (key.isBlank() && titleKey.isBlank()) return@mapNotNull null
                WishlistEntryRecord(
                    key = key,
                    titleKey = titleKey.ifBlank { com.discounthunter.android.core.text.TitleKey.normalize(title) },
                    title = title.ifBlank { "Unknown title" },
                    storeId = json.int("s") ?: return@mapNotNull null,
                    appId = json.int("a"),
                    gogSlug = json.string("g"),
                    thumbUrl = json.string("i"),
                    url = json.string("u"),
                    addedAt = json.long("d") ?: 0L,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

    /**
     * Reads an epoch-millis value.
     *
     * [int] cannot be used for these: epoch millis are around 1.7e12, well past `Int.MAX_VALUE`, so
     * `intOrNull` returns null and every stored timestamp silently became 0 — which is why
     * "Added today" never appeared on a restored wishlist and the newest-first sort degenerated to a
     * no-op.
     */
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.let {
            it.longOrNull ?: it.content.toLongOrNull() ?: it.content.toIntOrNull()?.toLong()
        }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_CURRENCY = stringPreferencesKey("currency")
        val KEY_STORES = stringPreferencesKey("enabled_stores")
        val KEY_DEAL_LIMIT = intPreferencesKey("deal_limit")
        val KEY_AUTOPLAY = booleanPreferencesKey("autoplay_trailers")
        val KEY_STORE_APPS = booleanPreferencesKey("prefer_store_apps")
        val KEY_SEARCHES = stringPreferencesKey("recent_searches")
        val KEY_ACCOUNTS = stringPreferencesKey("linked_accounts")
        val KEY_WISHLIST = stringPreferencesKey("wishlist")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarding_complete")
    }

    /** True once the user has been through the intro pages. */
    val hasSeenIntro: Flow<Boolean> =
        dataStore.data.map { it[KEY_ONBOARDED] ?: false }
            .catch { emit(false) }

    suspend fun setIntroSeen() {
        dataStore.edit { it[KEY_ONBOARDED] = true }
    }

    /**
     * Wipes every stored preference: settings, recent searches, linked account names and the
     * wishlist.
     *
     * `clear()` rather than resetting key by key, so a preference added later cannot survive a
     * "delete all data" by being forgotten here. Callers that also need the caches and snapshots
     * gone go through [com.discounthunter.android.di.AppContainer.deleteAllData], because this
     * class cannot see them.
     */
    suspend fun reset() {
        dataStore.edit { it.clear() }
    }
}

/** Domain model -> persisted record. */
fun WishlistEntry.toRecord(): WishlistEntryRecord = WishlistEntryRecord(
    key = key,
    titleKey = titleKey,
    title = title,
    storeId = store.id,
    appId = appId,
    gogSlug = gogSlug,
    thumbUrl = thumbUrl,
    url = url,
    addedAt = addedAt,
)

/** Persisted record -> domain model. Unknown store ids fall back to Steam, the majority store. */
fun WishlistEntryRecord.toEntry(): WishlistEntry = WishlistEntry(
    key = key,
    titleKey = titleKey,
    title = title,
    store = StoreId.fromId(storeId) ?: StoreId.STEAM,
    appId = appId,
    gogSlug = gogSlug,
    thumbUrl = thumbUrl,
    url = url,
    addedAt = addedAt,
)
