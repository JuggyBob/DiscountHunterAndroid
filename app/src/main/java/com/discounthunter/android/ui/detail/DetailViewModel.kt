package com.discounthunter.android.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.discounthunter.android.core.model.FxRates
import com.discounthunter.android.core.model.GameDetail
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.GameReference
import com.discounthunter.android.core.model.PriceFormat
import com.discounthunter.android.core.model.PriceHistoryCatalog
import com.discounthunter.android.core.model.PriceHistorySource
import com.discounthunter.android.core.model.PricePoint
import com.discounthunter.android.core.model.Screenshot
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.StoreOffer
import com.discounthunter.android.core.model.Trailer
import com.discounthunter.android.core.model.WishlistEntry
import com.discounthunter.android.data.cache.SnapshotStore
import com.discounthunter.android.data.repo.GameDetailRepository
import com.discounthunter.android.diagnostics.AppLog
import com.discounthunter.android.data.settings.AppSettings
import com.discounthunter.android.data.settings.SettingsRepository
import com.discounthunter.android.data.wishlist.WishlistRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


/**
 * A trailer that is playing, and where it is being shown.
 *
 * Playback starts [isFullScreen] = false — inline in the page, like a video on a shopping page
 * rather than a takeover. Expanding is the user's explicit choice, and keeping both states in one
 * object means the same `ExoPlayer` moves between them without reloading or losing its position.
 */
data class TrailerPlayback(
    val trailer: Trailer,
    val isFullScreen: Boolean = false,
)

data class DetailUiState(
    /** What the user asked for — shown immediately, before the full detail arrives. */
    val reference: GameReference? = null,
    val detail: GameDetail? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val currencySymbol: String = "$",
    /** The user's chosen currency as an ISO code, for conversion rather than relabelling. */
    val currencyCode: String = "USD",
    /** Today's rates, or null while they are still loading. */
    val fxRates: FxRates? = null,
    /** The date the rates were published, shown alongside any converted price. */
    val fxRateDate: java.time.LocalDate? = null,
    val autoplayTrailers: Boolean = false,
    val preferStoreApps: Boolean = true,
    val isSaved: Boolean = false,
    /** The screenshot opened in the full-screen viewer, if any. */
    val viewingScreenshot: Screenshot? = null,
    /** The trailer being played, inline or full-screen, if any. */
    val playback: TrailerPlayback? = null,
    /** Prices this device has recorded for this game, oldest first. */
    val history: List<PricePoint> = emptyList(),
    /** The price-history database the user picked in the dropdown. */
    val historySource: PriceHistorySource? = null,
) {
    /** The title to display: the loaded title wins, otherwise what the row said. */
    val title: String get() = detail?.title ?: reference?.title.orEmpty()

    val heroUrl: String get() = detail?.heroUrl ?: reference?.thumbUrl.orEmpty()

    val bestOffer: StoreOffer? get() = detail?.bestOffer(currencyCode, fxRates)

    val screenshots: List<Screenshot> get() = detail?.screenshots.orEmpty()

    val trailers: List<Trailer> get() = detail?.trailers.orEmpty()

    val offers: List<StoreOffer> get() = detail?.offers.orEmpty()

    val canOpenAnything: Boolean get() = (detail?.storeUrls?.values?.any { it.isNotBlank() } == true)

    /** Prices this device has recorded for the game, oldest first. */
    val priceHistory: List<PricePoint> get() = history

    /**
     * The stores this game is listed on.
     *
     * Taken from the offers the page actually collected rather than from the store the user opened,
     * so a GOG game that also sells on Steam offers both databases and an Epic-only game is never
     * sent to SteamDB.
     */
    val historyStores: Set<StoreId>
        get() = buildSet {
            detail?.primaryStore?.let(::add)
            detail?.offers?.forEach { add(it.store) }
        }

    /** The price-history databases that apply to this game, most specific first. */
    val historySources: List<PriceHistorySource> get() = PriceHistoryCatalog.sourcesFor(historyStores)

    /** The database the user picked, or the first one available. */
    val selectedHistorySource: PriceHistorySource?
        get() = historySource ?: historySources.firstOrNull()

    /**
     * True when the load finished and no store had anything for this game.
     *
     * Drives the explicit "no store has this game" message, so an empty page is never mistaken for
     * a game that simply has no screenshots.
     */
    val hasNoData: Boolean get() = detail != null && !detail.hasAnyContent

    /** Metadata chips under the title. */
    val metaChips: List<String>
        get() = buildList {
            detail?.developers?.firstOrNull()?.let { add("By ${it.take(30)}") }
            detail?.releaseDate?.takeIf { it.isNotBlank() }?.let { add(it.take(20)) }
            detail?.genres?.filter { it.isNotBlank() }?.take(3)?.forEach(::add)
            if (detail?.epicUpcoming == true) add("Epic: upcoming giveaway")
        }.take(5)

    /**
     * The headline price.
     *
     * ## Why this leads with the best offer and not the Steam price
     *
     * It used to prefer `info.price` — Steam's own `price_overview` — and only fall back to the best
     * offer. That made the page contradict itself: for a game opened from GOG whose Steam twin was
     * dearer, the header showed the *Steam* price while the "Where to buy" table right below it
     * listed the cheaper GOG row, and the wishlist showed the GOG figure as "Best". Three parts of
     * one app, three numbers for one game.
     *
     * The offers table is the page's single source of truth — it is the same "Where to buy" data
     * the feed and the wishlist read — so the headline now leads with that same best offer. Steam's
     * price overview is used only when there are no offers at all, which is the one case where it is
     * the only price available.
     */
    val priceLine: String?
        get() {
            val info = detail ?: return null
            if (info.isFreeToPlay) return "Free to play"

            info.bestOffer(currencyCode, fxRates)?.let { best ->
                val price = PriceFormat.priceWith(
                    best.price, currencyCode, best.currencyCode, fxRates,
                )
                val retail = PriceFormat.priceWith(
                    best.retailPrice, currencyCode, best.currencyCode, fxRates,
                )
                return when {
                    best.isFree -> "Free on ${best.store.displayName}"
                    best.showsRetailPrice -> "$price (was $retail, ${best.savingsLabel})"
                    else -> "$price at ${best.store.displayName}"
                }
            }

            info.price?.let { price ->
                val final = PriceFormat.priceWith(
                    price.finalPrice, currencyCode, price.currency, fxRates,
                )
                val initial = PriceFormat.priceWith(
                    price.initialPrice, currencyCode, price.currency, fxRates,
                )
                return if (price.hasDiscount) {
                    "$final (was $initial, -${price.discountPercent}%)"
                } else {
                    final
                }
            }
            return null
        }

    /**
     * True when any price on this page had to be converted rather than quoted.
     *
     * Drives a one-line note telling the user the figure is an estimate at the day's rate. Showing
     * a converted price as though it were a quoted one is the same class of lie as printing
     * dollars under a euro sign; the difference is much smaller, but it is still there.
     */
    val hasConvertedPrice: Boolean
        get() {
            val info = detail ?: return false
            val target = currencyCode
            val offers = info.offers.any { offer ->
                offer.currencyCode.isNotBlank() &&
                    !offer.currencyCode.equals(target, ignoreCase = true)
            }
            return offers || (info.price?.currency?.isNotBlank() == true &&
                !info.price.currency.equals(target, ignoreCase = true))
        }

    /**
     * A note under the price when some figure on this page is converted rather than quoted.
     *
     * Only shown when a conversion actually happened — a page of Steam prices fetched for the
     * user's own region has nothing to disclaim, and a permanent caveat trains people to ignore it.
     */
    val convertedNote: String?
        get() = when {
            !hasConvertedPrice -> null
            (fxRates?.isLoaded != true) -> "Prices from some stores are in another currency; " +
                "the rate could not be loaded so they are shown as quoted."
            fxRateDate != null -> "Some prices are converted from the store's currency at the " +
                "rate published for ${fxRateDate}. The store's own price may differ slightly."
            else -> "Some prices are converted from the store's currency."
        }
}

/**
 * The game detail page.
 *
 * ## Why the reference is kept in state
 *
 * The page is entered from a list row that already knows *which* game was tapped. That reference
 * carries the store-native identity and is published immediately, so the header is correct from the
 * first frame.
 *
 * The desktop build had the opposite bug: it set the title from the tapped row and never refreshed
 * it after loading, so when the (fuzzily) resolved app belonged to another game the header kept
 * claiming the original title while showing the other game's screenshots. Here the loaded
 * [GameDetail.title] wins as soon as it arrives, and cross-store media is only ever accepted when
 * [TitleMatcher] proves it is the same product.
 *
 * ## Load lifecycle
 *
 * Opening a second game cancels the first load and starts a new one, so a slow response can never
 * repopulate a page the user has already moved on from.
 */
class DetailViewModel(
    private val repository: GameDetailRepository,
    private val settingsRepository: SettingsRepository,
    private val wishlistRepository: WishlistRepository,
    private val snapshotStore: SnapshotStore,
    /** Injected so every screen converts with the same rates; defaults keep tests simple. */
    private val fxRates: FxRates = FxRates(),
) : ViewModel() {

    private companion object {
        const val STATE_TIMEOUT_MILLIS = 5_000L
    }

    private val state = MutableStateFlow(DetailUiState())
    private var loadJob: Job? = null
    private var settings: AppSettings = AppSettings()

    /**
     * Bumped whenever rates arrive.
     *
     * A counter rather than the rates themselves because `MutableStateFlow` suppresses equal values:
     * assigning the same `DetailUiState` back to itself emits nothing and the page would keep
     * showing dollars forever. A monotonically increasing tick is a genuinely different value, so
     * `combine` re-runs and the converted figures appear.
     */
    private val ratesRevision = MutableStateFlow(0)

    private var fxRateDate: java.time.LocalDate? = null
    val uiState: StateFlow<DetailUiState> = combine(
        state,
        settingsRepository.settings,
        wishlistRepository.entries,
        ratesRevision,
    ) { current, currentSettings, wishlist, _ ->
        val saved = current.detail?.let {
            wishlistRepository.matches(it.wishlistKey, it.titleKey)
        } ?: current.reference?.let {
            val entry = WishlistEntry.fromReference(it, System.currentTimeMillis())
            wishlistRepository.matches(entry.key, entry.titleKey)
        } ?: false

        current.copy(
            currencySymbol = currentSettings.currencySymbol,
            currencyCode = currentSettings.currency.code,
            fxRates = fxRates,
            fxRateDate = fxRateDate,
            autoplayTrailers = currentSettings.autoplayTrailers,
            preferStoreApps = currentSettings.preferStoreApps,
            isSaved = saved,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_TIMEOUT_MILLIS),
        initialValue = DetailUiState(),
    )

    init {
        settingsRepository.settings
            .onEach { settings = it }
            .launchIn(viewModelScope)

        // Rates are needed before any price can be shown converted, and a page that renders dollars
        // then flips to euros a moment later is worse than one that renders a beat later.
        viewModelScope.launch {
            fxRates.ensure()
            fxRateDate = fxRates.rateDate
            ratesRevision.value += 1
        }
    }

    // ------------------------------------------------------------------ loading

    /** Loads (or reloads) the game behind [reference]. */
    fun load(reference: GameReference) {
        // Switching games: close any media and drop the previous game's data immediately, so the
        // page never shows game A's screenshots while game B is loading.
        loadJob?.cancel()
        state.value = DetailUiState(
            reference = reference,
            isLoading = true,
            currencySymbol = settings.currencySymbol,
            autoplayTrailers = settings.autoplayTrailers,
            preferStoreApps = settings.preferStoreApps,
        )

        loadJob = viewModelScope.launch {
            try {
                val detail = repository.load(reference, settings.steamCountry)
                state.value = state.value.copy(detail = detail, isLoading = false)
                loadHistory(detail)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AppLog.e("Detail load failed for ${reference.identity}", error)
                state.value = state.value.copy(
                    isLoading = false,
                    error = "Could not load the full details for this title " +
                        "(${error.message ?: "unknown error"}). Check your connection and try again.",
                )
            }
        }
    }

    fun retry() {
        state.value.reference?.let(::load)
    }

    // ------------------------------------------------------------------ price history

    /**
     * Reads back the prices this device recorded for [detail].
     *
     * Runs after the detail lands and never blocks it: it only reads local snapshot files, and an
     * empty result simply leaves the chart in its "nothing recorded yet" state.
     */
    private suspend fun loadHistory(detail: GameDetail) {
        val identity = detail.identity ?: return
        val points = snapshotStore.priceHistory(identity)
        // Guard against a slow read landing after the user moved to another game.
        if (state.value.detail?.identity != identity) return
        state.value = state.value.copy(history = points)
    }

    /** The user picked a different price-history database from the dropdown. */
    fun selectHistorySource(source: PriceHistorySource) {
        state.value = state.value.copy(historySource = source)
    }

    fun historyUrl(source: PriceHistorySource): String? =
        state.value.detail?.let { PriceHistoryCatalog.urlFor(source, it) }

    // ------------------------------------------------------------------ actions

    fun toggleWishlist() {
        val current = state.value
        val entry = current.detail?.let { WishlistEntry.fromDetail(it, System.currentTimeMillis()) }
            ?: current.reference?.let { WishlistEntry.fromReference(it, System.currentTimeMillis()) }
            ?: return
        viewModelScope.launch { wishlistRepository.toggle(entry) }
    }

    fun openScreenshot(screenshot: Screenshot) {
        state.value = state.value.copy(viewingScreenshot = screenshot)
    }

    fun closeScreenshotViewer() {
        state.value = state.value.copy(viewingScreenshot = null)
    }

    /** Starts a trailer playing inline, where the page can still be read around it. */
    fun playTrailer(trailer: Trailer) {
        state.value = state.value.copy(playback = TrailerPlayback(trailer))
    }

    /** The user's explicit choice: slide the running player up into a full-screen overlay. */
    fun expandTrailer() {
        val current = state.value.playback ?: return
        if (current.isFullScreen) return
        state.value = state.value.copy(playback = current.copy(isFullScreen = true))
    }

    /** Back down to the inline player, keeping playback running. */
    fun collapseTrailer() {
        val current = state.value.playback ?: return
        if (!current.isFullScreen) return
        state.value = state.value.copy(playback = current.copy(isFullScreen = false))
    }

    fun closeTrailerPlayer() {
        state.value = state.value.copy(playback = null)
    }

    /** The URL to hand to another app for a trailer that cannot be played in-app. */
    fun externalUrlFor(trailer: Trailer): String = trailer.externalUrl

    /** The best store page to open from the primary action. */
    fun primaryUrl(): String? {
        val current = state.value
        current.detail?.bestOffer(settings.currency.code, fxRates)?.url?.takeIf { it.isNotBlank() }?.let { return it }
        current.detail?.webUrl?.takeIf { it.isNotBlank() }?.let { return it }
        return current.reference?.fallbackUrl?.takeIf { it.isNotBlank() }
    }

    /** The store of the best offer, for choosing a deep link. */
    fun primaryStore() = state.value.detail?.bestOffer(settings.currency.code, fxRates)?.store
        ?: state.value.reference?.store

    /** The store-native identity of the game on screen. */
    fun identity(): GameIdentity? = state.value.detail?.identity ?: state.value.reference?.identity
}
