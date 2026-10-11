package com.discounthunter.android.ui.deals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.DealFilter
import com.discounthunter.android.core.model.DiscountFilter
import com.discounthunter.android.core.model.FxRates
import com.discounthunter.android.core.model.GameReference
import com.discounthunter.android.core.model.SortOrder
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.WishlistEntry
import com.discounthunter.android.diagnostics.AppLog
import com.discounthunter.android.data.repo.DealRepository
import com.discounthunter.android.data.settings.AppSettings
import com.discounthunter.android.data.settings.SettingsRepository
import com.discounthunter.android.data.wishlist.WishlistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** One cell in the day strip. */
data class DayChip(
    val date: LocalDate,
    val isToday: Boolean,
    /** True when a giveaway starts or ends on this day. */
    val hasGiveaway: Boolean,
    val isSelected: Boolean,
)

/** Everything the deals screen renders. */
data class DealsUiState(
    val days: List<DayChip> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val deals: List<Deal> = emptyList(),
    val totalMatching: Int = 0,
    /** Deals loaded for the selected day, before any filter is applied. */
    val totalFetched: Int = 0,
    val isLoading: Boolean = false,
    /** True when the visible list came from disk rather than the network. */
    val isFromSnapshot: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val enabledStores: Set<StoreId> = StoreId.MAJOR.toSet(),
    val discountFilter: DiscountFilter = DiscountFilter.ALL,
    val sortOrder: SortOrder = SortOrder.BIGGEST_DISCOUNT,
    val titleQuery: String = "",
    val shownCount: Int = 0,
    val currencySymbol: String = "$",
    /** The chosen currency as an ISO code, for conversion rather than relabelling. */
    val currencyCode: String = "USD",
    /** Today's rates; null until loaded. Shared app-wide via the container. */
    val fxRates: FxRates? = null,
    val wishlistCount: Int = 0,
    val savedOnSale: Boolean = false,
) {
    val canLoadMore: Boolean get() = shownCount < totalMatching

    val isFiltered: Boolean
        get() = titleQuery.isNotBlank() || discountFilter != DiscountFilter.ALL

    val isToday: Boolean get() = selectedDate == LocalDate.now()

    /** The line under the app bar: how many deals matched, and where they came from. */
    val summary: String
        get() = when {
            isLoading && totalMatching == 0 -> "Loading deals…"
            totalMatching == 0 -> ""
            isFiltered -> "$totalMatching of $totalFetched deals match" +
                if (shownCount < totalMatching) " · showing $shownCount" else ""
            isFromSnapshot -> "$totalMatching deals · saved snapshot"
            totalMatching == 1 -> "1 deal · free games on top"
            else -> "$totalMatching deals · free games on top"
        }
}

/**
 * The front page: the day strip, filters, and the merged deal feed.
 *
 * ## How the list is built
 *
 * The full feed for the selected day lives in [rawDeals]; what the screen shows is
 * `DealFilter.apply(...)` over that list, re-derived whenever the feed, the wishlist or any filter
 * changes. Filtering and sorting therefore always operate on the underlying data rather than
 * re-ordering whatever happens to be visible, and because the input is already in memory, changing
 * a filter never triggers a network request.
 *
 * ## Load lifecycle
 *
 * Selecting a date or pressing refresh cancels the previous load and starts a new one. The
 * repository work is cancellable at every suspend point, so a slow response for yesterday can never
 * land on top of today's results.
 */
class DealsViewModel(
    private val repository: DealRepository,
    private val settingsRepository: SettingsRepository,
    private val wishlistRepository: WishlistRepository,
    /** Shared app-wide so the feed and the detail page convert one price identically. */
    private val fxRates: FxRates? = null,
) : ViewModel() {

    private companion object {
        const val PAST_DAYS = 14L
        const val FUTURE_DAYS = 15L
        const val PAGE_SIZE = 25
        const val STATE_TIMEOUT_MILLIS = 5_000L
    }

    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val filters = MutableStateFlow(Filters())
    private val rawDeals = MutableStateFlow<List<Deal>>(emptyList())
    private val loadState = MutableStateFlow(LoadState())
    private val shownCount = MutableStateFlow(PAGE_SIZE)
    private val giveawayDays = MutableStateFlow<Set<LocalDate>>(emptySet())

    private var loadJob: Job? = null
    private var settings: AppSettings = AppSettings()

    private data class Filters(
        val enabledStores: Set<StoreId> = StoreId.MAJOR.toSet(),
        val discount: DiscountFilter = DiscountFilter.ALL,
        val sort: SortOrder = SortOrder.BIGGEST_DISCOUNT,
        val titleQuery: String = "",
    )

    private data class LoadState(
        val inProgress: Boolean = false,
        val isFromSnapshot: Boolean = false,
        val notice: String? = null,
        val error: String? = null,
    )

    private val content = combine(
        rawDeals,
        filters,
        shownCount,
        wishlistRepository.entries,
        settingsRepository.settings,
    ) { deals, activeFilters, shown, wishlist, currentSettings ->
        Content(
            filtered = DealFilter.apply(
                deals = deals,
                enabledStores = activeFilters.enabledStores,
                discount = activeFilters.discount,
                titleQuery = activeFilters.titleQuery,
                sort = activeFilters.sort,
                isWishlisted = { wishlistRepository.matches(it.wishlistKey, it.titleKey) },
            ),
            rawCount = deals.size,
            shown = shown,
            wishlistSize = wishlist.size,
            savedOnSale = deals.any { wishlistRepository.matches(it.wishlistKey, it.titleKey) },
            settings = currentSettings,
        )
    }

    private data class Content(
        val filtered: List<Deal>,
        val rawCount: Int,
        val shown: Int,
        val wishlistSize: Int,
        val savedOnSale: Boolean,
        val settings: AppSettings,
    )

    private val chrome = combine(
        loadState,
        selectedDate,
        filters,
        giveawayDays,
    ) { state, date, activeFilters, marked ->
        Chrome(state, date, activeFilters, marked)
    }

    private data class Chrome(
        val state: LoadState,
        val date: LocalDate,
        val filters: Filters,
        val markedDays: Set<LocalDate>,
    )

    val uiState: StateFlow<DealsUiState> = combine(content, chrome) { body, chromeState ->
        DealsUiState(
            days = buildDayStrip(chromeState.date, chromeState.markedDays),
            selectedDate = chromeState.date,
            deals = body.filtered.take(body.shown),
            totalMatching = body.filtered.size,
            totalFetched = body.rawCount,
            isLoading = chromeState.state.inProgress,
            isFromSnapshot = chromeState.state.isFromSnapshot,
            notice = chromeState.state.notice,
            error = chromeState.state.error,
            enabledStores = chromeState.filters.enabledStores,
            discountFilter = chromeState.filters.discount,
            sortOrder = chromeState.filters.sort,
            titleQuery = chromeState.filters.titleQuery,
            shownCount = body.shown.coerceAtMost(body.filtered.size),
            currencySymbol = body.settings.currencySymbol,
            currencyCode = body.settings.currency.code,
            fxRates = fxRates,
            wishlistCount = body.wishlistSize,
            savedOnSale = body.savedOnSale,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_TIMEOUT_MILLIS),
        initialValue = DealsUiState(),
    )

    init {
        settingsRepository.settings
            .onEach { current ->
                val regionChanged = current.steamCountry != settings.steamCountry
                settings = current
                // Only adopt the stored store selection while the user has not overridden it on
                // screen; their explicit choice must not be undone by a settings write.
                if (!storeOverrideActive) {
                    filters.value = filters.value.copy(enabledStores = current.enabledStores)
                }
                // Changing currency changes the Steam region, and every price in the feed comes from
                // that region. Without a reload the list kept showing the old currency's figures,
                // and for a past day it kept showing them out of the stored snapshot too — which is
                // why pulling to refresh did not appear to help either.
                if (regionChanged) load()
            }
            .launchIn(viewModelScope)

        load()
    }

    private var storeOverrideActive = false

    // ------------------------------------------------------------------ actions

    /** Refreshes the feed for the selected day. */
    fun refresh() {
        load()
    }

    fun selectDate(date: LocalDate) {
        if (selectedDate.value == date) return
        selectedDate.value = date
        load()
    }

    fun goToToday() = selectDate(LocalDate.now())

    fun shiftDays(days: Long) {
        val target = selectedDate.value.plusDays(days)
        if (target in stripRange()) selectDate(target)
    }

    fun toggleStore(store: StoreId) {
        val current = filters.value.enabledStores
        val next = if (store in current) current - store else current + store
        storeOverrideActive = true
        filters.value = filters.value.copy(enabledStores = next)
        resetPaging()
        viewModelScope.launch { settingsRepository.setEnabledStores(next) }
    }

    fun setDiscountFilter(filter: DiscountFilter) {
        if (filters.value.discount == filter) return
        filters.value = filters.value.copy(discount = filter)
        resetPaging()
    }

    fun setSortOrder(order: SortOrder) {
        if (filters.value.sort == order) return
        filters.value = filters.value.copy(sort = order)
        resetPaging()
    }

    fun setTitleQuery(query: String) {
        val clean = query.trim()
        if (filters.value.titleQuery == clean) return
        filters.value = filters.value.copy(titleQuery = clean)
        resetPaging()
    }

    /** Clears the discount, sort and title filters, keeping the store selection. */
    fun resetFilters() {
        filters.value = Filters(enabledStores = filters.value.enabledStores)
        resetPaging()
    }

    fun loadMore() {
        shownCount.value = (shownCount.value + PAGE_SIZE).coerceAtMost(uiState.value.totalMatching)
    }

    fun toggleWishlist(entry: WishlistEntry) {
        viewModelScope.launch { wishlistRepository.toggle(entry) }
    }

    /**
     * The reference used to open a deal's detail page.
     *
     * Built from the store-native id, not the display title, so the detail screen always knows
     * *which* game it is about.
     */
    fun referenceFor(deal: Deal): GameReference? {
        val identity = deal.identity ?: return null
        return GameReference(
            identity = identity,
            title = deal.title,
            thumbUrl = deal.thumbUrl,
            originUrl = deal.storeUrl,
        )
    }

    fun entryFor(deal: Deal): WishlistEntry = WishlistEntry.fromDeal(deal, System.currentTimeMillis())

    // ------------------------------------------------------------------ loading

    private fun load() {
        // One load at a time: a superseded load is cancelled, never raced against the new one.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val date = selectedDate.value
            val today = LocalDate.now()
            val current = settings

            loadState.value = LoadState(inProgress = true)
            shownCount.value = PAGE_SIZE

            try {
                when {
                    // A future day: only Epic schedules ahead, and only giveaways.
                    date.isAfter(today) -> loadFuture(date)

                    // A past day: prefer the stored snapshot so history stays browsable.
                    date.isBefore(today) -> {
                        val snapshot = repository.loadSnapshot(date, settings.steamCountry)
                        if (snapshot != null) {
                            rawDeals.value = snapshot
                            loadState.value = LoadState(
                                isFromSnapshot = true,
                                notice = "Showing the snapshot saved for $date. Live deals refresh daily.",
                            )
                        } else {
                            loadLive(date, today, current, missingSnapshotFor = date)
                        }
                    }

                    else -> loadLive(date, today, current, missingSnapshotFor = null)
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AppLog.e("Deals load failed for $date in region ${settings.steamCountry}", error)
                rawDeals.value = emptyList()
                loadState.value = LoadState(
                    error = "Could not load deals (${error.message ?: "unknown error"}). " +
                        "Check your connection and try again.",
                )
            }
        }
    }

    private suspend fun loadFuture(date: LocalDate) {
        val deals = repository.giveawaysForDate(date)
        rawDeals.value = deals
        loadState.value = LoadState(
            notice = if (deals.isEmpty()) {
                "No giveaways are known to start or end on $date. Check Today for everything live."
            } else {
                "${deals.size} scheduled giveaway${if (deals.size == 1) "" else "s"} around $date. " +
                    "Store-wide sales refresh daily."
            },
        )
    }

    private suspend fun loadLive(
        date: LocalDate,
        today: LocalDate,
        current: AppSettings,
        missingSnapshotFor: LocalDate?,
    ) {
        val feed = repository.refreshFeed(
            country = current.steamCountry,
            limit = current.dealLimit,
            stores = filters.value.enabledStores,
        )

        rawDeals.value = feed.deals
        loadState.value = LoadState(
            error = if (feed.isEmpty) feed.warnings.firstOrNull() else null,
            notice = when {
                feed.isEmpty -> null
                missingSnapshotFor != null ->
                    "No snapshot was saved for $missingSnapshotFor, so today's live deals are shown. " +
                        feed.warnings.joinToString(" ").ifBlank { "" }
                feed.warnings.isNotEmpty() -> feed.warnings.joinToString(" ")
                else -> null
            },
        )

        if (date == today && feed.deals.isNotEmpty()) {
            repository.saveSnapshot(date, feed.deals, settings.steamCountry)
            // Calendar markers are cosmetic; a failure here must never disturb the feed.
            // `runCatching` swallows `CancellationException` along with real failures, so a
            // superseded load would keep running instead of unwinding — which is exactly the
            // invariant this ViewModel documents. Only genuine errors are caught.
            try {
                giveawayDays.value = repository.giveawayDays(
                    from = today.minusDays(PAST_DAYS),
                    to = today.plusDays(FUTURE_DAYS),
                    zone = ZoneId.systemDefault(),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Calendar markers are cosmetic; a failure here must never disturb the feed.
            }
        }
    }

    // ------------------------------------------------------------------ strip

    private fun stripRange(): ClosedRange<LocalDate> {
        val today = LocalDate.now()
        return today.minusDays(PAST_DAYS)..today.plusDays(FUTURE_DAYS)
    }

    private fun buildDayStrip(selected: LocalDate, marked: Set<LocalDate>): List<DayChip> {
        val today = LocalDate.now()
        val start = today.minusDays(PAST_DAYS)
        val total = (PAST_DAYS + FUTURE_DAYS + 1).toInt()
        return (0 until total).map { offset ->
            val date = start.plusDays(offset.toLong())
            DayChip(
                date = date,
                isToday = date == today,
                hasGiveaway = date in marked,
                isSelected = date == selected,
            )
        }
    }

    private fun resetPaging() {
        shownCount.value = PAGE_SIZE
    }
}
