package com.discounthunter.android.ui.deals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FilterListOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discounthunter.android.R
import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.GameReference
import com.discounthunter.android.data.wishlist.WishlistRepository
import com.discounthunter.android.ui.components.CardSkeleton
import com.discounthunter.android.ui.components.EmptyState
import com.discounthunter.android.ui.components.ErrorState

/**
 * The front page.
 *
 * ## List identity
 *
 * Rows are keyed by `deal.id`, the store-native identifier. That single decision is what keeps a
 * `LazyColumn` honest when the filter changes: Compose moves the *same* item rather than rebuilding
 * a slot, so no row can inherit the previous occupant's artwork, star state or price.
 *
 * Pull-to-refresh re-queries the stores; scrolling to the bottom extends the page without a request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealsScreen(
    viewModel: DealsViewModel,
    wishlistRepository: WishlistRepository,
    onOpenGame: (GameReference) -> Unit,
    onGetDeal: (Deal) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    // A phone in portrait gets one column; a phone in landscape, a foldable or a tablet gets more.
    // One column on a 900 dp-wide screen means a single card floating in a sea of empty space, which
    // is the classic "this is a desktop layout wearing a phone hat" failure. The count is derived
    // from the available width so it degrades smoothly rather than switching at one magic size.
    //
    // The chrome stays *inside* the scroll either way: on a short landscape screen, pinning it would
    // cost more vertical room than the extra column buys back.
    val widthDp = LocalConfiguration.current.screenWidthDp
    val columns = remember(widthDp) { (widthDp / GRID_COLUMN_DP).coerceIn(1, 3) }
    val gutter = if (columns > 1) 12.dp else 0.dp

    // Extend the page as the user approaches its end. Paging is purely local: the whole feed is
    // already in memory, so this costs no network traffic.
    val shouldLoadMore = state.canLoadMore &&
        state.deals.isNotEmpty() &&
        gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            ?.let { it >= state.deals.size - PREFETCH_DISTANCE } == true
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (state.summary.isNotBlank()) {
                            Text(
                                text = state.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // Only refresh lives here. The feed used to carry a second "filters" icon in
                    // the app bar *and* a labelled "Filters" chip pinned above the list; two
                    // controls opening the same sheet is one too many, and the chip survives
                    // because it sits with the sort order it lives next to and shows when a
                    // filter is active.
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.deals_refresh),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isLoading && state.deals.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(gutter),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The day strip and the sort/filter bar span the full width so they read as the
                // page's chrome rather than as a card that happens to be first.
                item(
                    key = "chrome",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    DealFeedChrome(
                        state = state,
                        onOpenFilters = { showFilters = true },
                        viewModel = viewModel,
                    )
                }

                dealListItems(
                    state = state,
                    wishlistRepository = wishlistRepository,
                    viewModel = viewModel,
                    onOpenGame = onOpenGame,
                    onGetDeal = onGetDeal,
                )
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            state = state,
            onDismiss = { showFilters = false },
            onToggleStore = viewModel::toggleStore,
            onDiscountChange = viewModel::setDiscountFilter,
            onSortChange = viewModel::setSortOrder,
            onTitleQueryChange = viewModel::setTitleQuery,
            onReset = viewModel::resetFilters,
        )
    }
}

/** The day strip, the sort/filter bar and the notice — the feed's chrome. */
@Composable
private fun DealFeedChrome(
    state: DealsUiState,
    onOpenFilters: () -> Unit,
    viewModel: DealsViewModel,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DayStrip(
            days = state.days,
            onSelect = viewModel::selectDate,
            onPrevious = { viewModel.shiftDays(-1) },
            onNext = { viewModel.shiftDays(1) },
        )
        DealsSummaryBar(
            state = state,
            onOpenFilters = onOpenFilters,
            onGoToToday = viewModel::goToToday,
        )
        if (state.notice != null) {
            DealsNotice(state)
        }
    }
}

/**
 * The slice of the lazy-grid DSL the deal list needs.
 *
 * Keeping the four list states behind one small interface means the chrome, the cards and the
 * "load more" footer are declared once, whether the feed is showing one column or three.
 */
private interface DealListScope {
    fun item(key: String, content: @Composable () -> Unit)

    fun <T> items(
        items: List<T>,
        key: (T) -> Any,
        itemContent: @Composable (T) -> Unit,
    )
}

private fun LazyGridScope.dealListItems(
    state: DealsUiState,
    wishlistRepository: WishlistRepository,
    viewModel: DealsViewModel,
    onOpenGame: (GameReference) -> Unit,
    onGetDeal: (Deal) -> Unit,
) {
    val grid = this
    dealListItems(
        scope = object : DealListScope {
            override fun item(key: String, content: @Composable () -> Unit) {
                grid.item(key = key) { content() }
            }

            override fun <T> items(
                items: List<T>,
                key: (T) -> Any,
                itemContent: @Composable (T) -> Unit,
            ) {
                // The `LazyItemScope` receiver only exists inside *this* lambda, so the item
                // placement animation has to be applied here rather than handed in from further
                // out — which is also why the wrapper cannot be the grid itself.
                //
                // Removing this was a wrong call. Every sort order floats wishlisted deals to the
                // top, so starring a game moves it — and that motion is the whole point of the
                // feature. See `WishlistToggle` for the part that actually needed hardening.
                grid.items(items = items, key = key) { item ->
                    Box(Modifier.animateItem()) { itemContent(item) }
                }
            }
        },
        state = state,
        wishlistRepository = wishlistRepository,
        viewModel = viewModel,
        onOpenGame = onOpenGame,
        onGetDeal = onGetDeal,
    )
}

/**
 * The four states a deal list can be in, plus the cards themselves.
 */
private fun dealListItems(
    scope: DealListScope,
    state: DealsUiState,
    wishlistRepository: WishlistRepository,
    viewModel: DealsViewModel,
    onOpenGame: (GameReference) -> Unit,
    onGetDeal: (Deal) -> Unit,
) = with(scope) {
    when {
        state.error != null -> item(key = "error") {
            ErrorState(
                message = state.error.orEmpty(),
                onRetry = viewModel::refresh,
                retryLabel = stringResource(R.string.deals_retry),
            )
        }

        state.isLoading && state.deals.isEmpty() -> item(key = "loading") {
            Column {
                repeat(3) { CardSkeleton() }
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 3.dp,
                    )
                }
            }
        }

        state.deals.isEmpty() -> item(key = "empty") {
            val (title, message) = emptyCopy(state)
            EmptyState(
                title = title,
                message = message,
                icon = Icons.Outlined.FilterListOff,
                actionLabel = if (state.isFiltered) {
                    stringResource(R.string.deals_filter_reset)
                } else {
                    stringResource(R.string.deals_retry)
                },
                onAction = {
                    if (state.isFiltered) viewModel.resetFilters() else viewModel.refresh()
                },
            )
        }

        else -> {
            items(
                items = state.deals,
                // Stable identity: the store-native deal id, never the list index.
                key = { it.id },
            ) { deal ->
                val entry = viewModel.entryFor(deal)
                val saved = wishlistRepository.matches(entry.key, entry.titleKey)
                DealCard(
                    deal = deal,
                    currencySymbol = state.currencySymbol,
                    currencyCode = state.currencyCode,
                    fx = state.fxRates,
                    isSaved = saved,
                    onOpen = {
                        viewModel.referenceFor(deal)?.let(onOpenGame)
                    },
                    onGetDeal = { onGetDeal(deal) },
                    onToggleWishlist = { viewModel.toggleWishlist(entry) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.canLoadMore) {
                item(key = "loadmore") {
                    LoadMoreRow(
                        remaining = state.totalMatching - state.shownCount,
                        onClick = viewModel::loadMore,
                    )
                }
            } else if (state.deals.isNotEmpty()) {
                item(key = "footer") { StoresFooter() }
            }
        }
    }
}

/** Explains *why* the list is empty, in the user's terms. */
@Composable
private fun emptyCopy(state: DealsUiState): Pair<String, String> = when {
    state.isFiltered ->
        "No deals match" to "Try enabling more stores, lowering the discount threshold, or clearing the title filter."
    state.enabledStores.isEmpty() ->
        "No stores selected" to "Enable at least one store in the filters to see deals."
    state.selectedDate.isAfter(java.time.LocalDate.now()) ->
        "Nothing scheduled" to "No giveaways are known for this day yet. Check Today for everything live."
    else ->
        "No deals loaded" to "Check your connection and pull down to refresh."
}

/** Number of rows from the end at which the next page is requested. */
private const val PREFETCH_DISTANCE = 4

/** Width one column of the deal grid needs before a second column is worth showing. */
private const val GRID_COLUMN_DP = 420
