package com.discounthunter.android.ui.wishlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.GameReference
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.WishlistEntry
import com.discounthunter.android.core.model.FxRates
import com.discounthunter.android.data.settings.SettingsRepository
import com.discounthunter.android.data.wishlist.WishlistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One saved game, enriched with whether it is discounted right now. */
data class WishlistRow(
    val entry: WishlistEntry,
    /** The best live deal for this game, when one exists. */
    val liveDeal: Deal?,
) {
    val isOnSale: Boolean get() = liveDeal != null

    /**
     * The store this row is actually talking about.
     *
     * Normally the store the game was saved from, but when the only live deal is on another store
     * the badges must follow it — otherwise a row could show a GOG tag next to Epic's price.
     */
    val shownStore: StoreId get() = liveDeal?.store ?: entry.store

    /** "Added today" / "Added 3 days ago" / a date. */
    val addedLabel: String get() {
        val addedAt = entry.addedAt
        if (addedAt <= 0) return ""
        val days = ((System.currentTimeMillis() - addedAt) / DAY_MILLIS).toInt()
        return when {
            days <= 0 -> "Added today"
            days == 1 -> "Added yesterday"
            days < 30 -> "Added $days days ago"
            else -> "Added ${days / 30} month${if (days / 30 == 1) "" else "s"} ago"
        }
    }

    /** The reference used to open this game's detail page. */
    val reference: GameReference?
        get() = runCatching {
            GameIdentityFactory.from(entry)
        }.getOrNull()

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

data class WishlistUiState(
    val rows: List<WishlistRow> = emptyList(),
    val currencySymbol: String = "$",
    /** The chosen currency as an ISO code, for conversion rather than relabelling. */
    val currencyCode: String = "USD",
    /** Today's rates; null until loaded. */
    val fxRates: FxRates? = null,
    val hasLoadedFeed: Boolean = false,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    val onSaleCount: Int get() = rows.count { it.isOnSale }

    val summary: String
        get() = when {
            rows.isEmpty() -> "Nothing saved yet"
            onSaleCount == 0 -> "${rows.size} saved · none on sale right now"
            onSaleCount == 1 -> "${rows.size} saved · 1 is on sale"
            else -> "${rows.size} saved · $onSaleCount are on sale"
        }
}

/**
 * The wishlist: everything the user saved, cross-referenced against the live feed.
 *
 * "On sale now" is derived from the deals already loaded on the front page, so the wishlist screen
 * costs no extra network traffic. The live feed is pushed in by the host when the deals screen
 * loads, and the rows are recomputed from it here.
 */
class WishlistViewModel(
    private val wishlistRepository: WishlistRepository,
    private val settingsRepository: SettingsRepository,
    /** Shared app-wide so the wishlist converts one price identically to the feed. */
    private val fxRates: FxRates? = null,
) : ViewModel() {

    private companion object {
        const val STATE_TIMEOUT_MILLIS = 5_000L
    }

    private val liveDeals = MutableStateFlow<List<Deal>>(emptyList())
    private val currencySymbol = MutableStateFlow("$")
    private val currencyCode = MutableStateFlow("USD")

    val uiState: StateFlow<WishlistUiState> = combine(
        wishlistRepository.entries,
        liveDeals,
        currencySymbol,
        currencyCode,
    ) { entries, deals, symbol, _ ->
        WishlistUiState(
            rows = entries.map { entry ->
                WishlistRow(entry = entry, liveDeal = wishlistRepository.bestLiveDeal(entry, deals))
            },
            currencySymbol = symbol,
            currencyCode = currencyCode.value,
            fxRates = fxRates,
            hasLoadedFeed = deals.isNotEmpty(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_TIMEOUT_MILLIS),
        initialValue = WishlistUiState(),
    )

    init {
        settingsRepository.settings
            .onEach {
                currencySymbol.value = it.currencySymbol
                currencyCode.value = it.currency.code
            }
            .launchIn(viewModelScope)
    }

    /**
     * Publishes the live feed for cross-referencing.
     *
     * Called by the host whenever the front page loads or refreshes, so the "on sale" flags are
     * always in step with the deals the user is looking at.
     */
    fun updateLiveDeals(deals: List<Deal>) {
        liveDeals.value = deals
    }

    fun remove(entry: WishlistEntry) {
        viewModelScope.launch { wishlistRepository.remove(entry.key, entry.titleKey) }
    }

    /**
     * Removes an entry, but only once the row's "poof" animation has finished playing.
     *
     * The screen drives the animation and then calls this. If the repository were told first, the
     * row would leave the lazy layout immediately and the next row would jump up underneath the
     * finger before anything had visibly happened.
     */
    suspend fun removeAfterPoof(entry: WishlistEntry) {
        wishlistRepository.remove(entry.key, entry.titleKey)
    }

    fun clearAll() {
        viewModelScope.launch { wishlistRepository.clear() }
    }
}

/** Builds a stable identity for a saved entry, so the wishlist can reopen its detail page. */
private object GameIdentityFactory {
    fun from(entry: WishlistEntry): GameReference? {
        val id = when (entry.store) {
            StoreId.STEAM -> entry.appId?.toString()
            StoreId.GOG -> entry.gogSlug.takeIf { it.isNotBlank() }
            StoreId.EPIC -> entry.url.substringAfterLast('/').takeIf { it.isNotBlank() && !it.contains('.') }
        }
        if (id.isNullOrBlank()) return null
        return GameReference(
            identity = com.discounthunter.android.core.model.GameIdentity(entry.store, id),
            title = entry.title,
            thumbUrl = entry.thumbUrl,
            originUrl = entry.url,
        )
    }
}
