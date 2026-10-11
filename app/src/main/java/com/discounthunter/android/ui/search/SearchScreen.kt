package com.discounthunter.android.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discounthunter.android.R
import com.discounthunter.android.core.model.GameReference
import com.discounthunter.android.core.model.SearchResult
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.data.wishlist.WishlistRepository
import com.discounthunter.android.ui.components.BestPriceLabel
import com.discounthunter.android.ui.components.EmptyState
import com.discounthunter.android.ui.components.StoreBadge
import com.discounthunter.android.ui.deals.DealCardCompact
import com.discounthunter.android.ui.deals.WishlistToggle

/**
 * Search across Steam, GOG and the Epic freebies feed.
 *
 * The search field is pinned to the top of a scrolling list rather than living in its own screen,
 * so results appear *under* the query the user typed — the important context while scanning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    wishlistRepository: WishlistRepository,
    onOpenGame: (GameReference) -> Unit,
    onGetBestOffer: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedSearchField(
                query = state.query,
                isSearching = state.isSearching,
                onQueryChange = viewModel::onQueryChange,
                onClear = viewModel::clear,
                onSearch = viewModel::searchNow,
            )

            if (state.results.isEmpty() && state.recentSearches.isNotEmpty() && state.query.isBlank()) {
                RecentSearches(
                    queries = state.recentSearches,
                    onPick = { picked ->
                        viewModel.onQueryChange(picked)
                        viewModel.searchNow()
                    },
                )
            }

            when {
                state.isSearching && state.results.isEmpty() ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                state.showEmptyResult ->
                    EmptyState(
                        title = "No results",
                        message = state.message.orEmpty(),
                        icon = Icons.Outlined.SearchOff,
                    )

                state.results.isEmpty() ->
                    EmptyState(
                        // The text field already says "Search every store" right above this, so
                        // repeating it as a heading printed the same words twice on one screen.
                        title = "Nothing searched yet",
                        message = "Find a game on Steam, GOG and the Epic freebies feed at once. " +
                            "Results show the cheapest price and which stores matched.",
                        icon = Icons.Outlined.Search,
                    )

                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "count") {
                        Text(
                            text = "${state.results.size} result${if (state.results.size == 1) "" else "s"} · cheapest first",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(items = state.results, key = { resultKey(it) }) { result ->
                        SearchResultCard(
                            modifier = Modifier.animateItem(),
                            result = result,
                            currencySymbol = state.currencySymbol,
                            isSaved = wishlistRepository.matches(result.wishlistKey, result.titleKey),
                            onOpen = { result.reference?.let(onOpenGame) },
                            onGetBestOffer = { onGetBestOffer(result) },
                            onToggleWishlist = { viewModel.toggleWishlist(result) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: SearchResult,
    currencySymbol: String,
    isSaved: Boolean,
    onOpen: () -> Unit,
    onGetBestOffer: () -> Unit,
    onToggleWishlist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DealCardCompact(
        modifier = modifier,
        title = result.title,
        subtitle = subtitleFor(result),
        thumbUrl = result.thumbUrl,
        onClick = onOpen,
        trailing = {
            WishlistToggle(isSaved = isSaved, title = result.title, onToggle = onToggleWishlist)
        },
        leading = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BestPriceLabel(
                    price = result.cheapestPrice,
                    storeName = storeLabel(result),
                    currencySymbol = currencySymbol,
                    isFree = result.isFree,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.matchedStores.sortedBy { it.ordinal }.forEach { store ->
                        StoreBadge(store = store, short = true)
                    }
                }
            }
        },
    )
}

/**
 * The subtitle says *which* stores matched and how they were identified, so a row that merged a
 * Steam and a GOG entry is visibly different from a Steam-only one.
 */
private fun subtitleFor(result: SearchResult): String = buildList {
    if (result.steamAppId != null) add("Steam app ${result.steamAppId}")
    if (!result.gogSlug.isNullOrBlank()) add("GOG")
    if (result.epicFree) add("FREE on Epic")
    if (isEmpty()) add("Web match")
}.joinToString("  •  ")

private fun storeLabel(result: SearchResult): String = when {
    result.epicFree -> "Epic"
    result.steamPrice != null && result.gogPrice != null -> "Steam / GOG"
    result.steamPrice != null -> StoreId.STEAM.shortName
    result.gogPrice != null -> StoreId.GOG.shortName
    else -> "varies"
}

/**
 * A stable key for a result row.
 *
 * The store-native id is the identity; the title only disambiguates rows that have no id at all.
 */
private fun resultKey(result: SearchResult): String =
    result.identity?.key ?: "title:${result.titleKey}"

@Composable
private fun RecentSearches(queries: List<String>, onPick: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Recent",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items = queries, key = { it }) { query ->
                AssistChip(
                    onClick = { onPick(query) },
                    label = { Text(query, maxLines = 1) },
                )
            }
        }
    }
}
