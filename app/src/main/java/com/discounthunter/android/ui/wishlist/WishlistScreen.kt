package com.discounthunter.android.ui.wishlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discounthunter.android.R
import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.GameReference
import com.discounthunter.android.ui.components.BestPriceLabel
import com.discounthunter.android.ui.components.EmptyState
import com.discounthunter.android.ui.components.Pill
import com.discounthunter.android.ui.components.StoreBadge
import com.discounthunter.android.ui.deals.DealCardCompact
import com.discounthunter.android.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The wishlist.
 *
 * Each row cross-references the live feed: anything currently discounted is badged and shows its
 * best price. The "Get deal" action always prefers the live offer over the stored store page,
 * matching what the user sees on the front page.
 *
 * ## Why removals are animated here and not in the repository
 *
 * Both this screen and the repository know when an entry goes away, but only the screen knows when
 * the user is looking. A removal that is applied the instant the star is tapped makes the next row
 * jump up under the finger before anything has visibly happened, so the screen holds the row on
 * screen, plays the "poof", and only then tells the repository. See [PoofOut].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WishlistScreen(
    viewModel: WishlistViewModel,
    onOpenGame: (GameReference) -> Unit,
    onGetDeal: (Deal) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    // The key of the row currently playing its poof, if any. It stays in the list until the
    // animation finishes; see the `rows` binding below.
    var exiting by remember { mutableStateOf<String?>(null) }
    // The whole list playing its poof after "clear all".
    var clearingAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The row being removed deliberately stays in the list: filtering it out here would take it out
    // of composition before `PoofOut` ever ran, and the animation would have nothing to animate.
    // It leaves the list when the repository drops it, which happens in `onFinished`.
    val rows = state.rows

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.wishlist_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = state.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.rows.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.wishlist_clear_all),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (state.isEmpty) {
            EmptyState(
                title = "Nothing saved yet",
                message = "Tap the star on any game to follow it. Saved games float to the top of " +
                    "the Deals tab, and this page tells you the moment one goes on sale.",
                icon = Icons.Outlined.StarOutline,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        // Clearing everything is the same gesture applied to the whole list: swell, beat, collapse.
        // The repository is only told to clear once the animation has finished, so the empty state
        // replaces an already-collapsed list rather than cutting straight to it.
        val pop = remember { Animatable(1f) }
        LaunchedEffect(clearingAll) {
            if (!clearingAll) return@LaunchedEffect
            pop.snapTo(1f)
            pop.animateTo(1.05f, tween(130, easing = Motion.Emphasised))
            delay(70)
            pop.animateTo(0f, tween(260, easing = Motion.Accelerate))
            viewModel.clearAll()
            clearingAll = false
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .graphicsLayer {
                    val p = pop.value
                    scaleX = p
                    scaleY = p
                    alpha = (p * 1.4f).coerceIn(0f, 1f)
                },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = rows, key = { rowKey(it) }) { row ->
                PoofOut(
                    visible = exiting != rowKey(row),
                    onFinished = {
                        // Only now is the row allowed to actually leave. Removing it the instant
                        // the star was tapped made the next row jump up under the finger before
                        // anything had visibly happened — the animation had nothing to animate.
                        exiting?.let { key ->
                            state.rows.firstOrNull { rowKey(it) == key }?.let { target ->
                                scope.launch { viewModel.removeAfterPoof(target.entry) }
                            }
                        }
                        exiting = null
                    },
                ) {
                    WishlistRowCard(
                        // `animateItem` moves rows that change position, which is what makes a
                        // newly starred game rise to the top rather than teleport there.
                        modifier = Modifier.fillMaxWidth().animateItem(),
                        row = row,
                        currencySymbol = state.currencySymbol,
                        currencyCode = state.currencyCode,
                        fx = state.fxRates,
                        onOpenDetails = { row.reference?.let(onOpenGame) },
                        onGetDeal = {
                            // Prefer the live discounted offer over the stored store page.
                            val live = row.liveDeal
                            if (live != null) {
                                onGetDeal(live)
                            } else {
                                row.reference?.let(onOpenGame)
                            }
                        },
                        // Only records *which* row is leaving; see `onFinished` above.
                        onRemove = { exiting = rowKey(row) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the wishlist?") },
            text = { Text("All ${state.rows.size} saved games will be removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Play the poof, then clear — see `clearingAll` above.
                        clearingAll = true
                        confirmClear = false
                    },
                ) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

/** The stable key of a row, used both by the lazy list and by the removal animation. */
private fun rowKey(row: WishlistRow): String = row.entry.key.ifBlank { row.entry.titleKey }

/**
 * Holds a row on screen while it plays its "poof", then lets the caller remove it.
 *
 * The shape is deliberate: a small swell, a beat, then a collapse to nothing. Collapsing straight
 * away reads as a glitch, and swelling without ever shrinking reads as a stuck press. The beat of
 * nothing is what makes the swell register as an event rather than the first half of a bounce.
 */
@Composable
private fun PoofOut(visible: Boolean, onFinished: () -> Unit, content: @Composable () -> Unit) {
    val progress = remember { Animatable(1f) }

    LaunchedEffect(visible) {
        if (visible) return@LaunchedEffect
        progress.snapTo(1f)
        progress.animateTo(1.06f, tween(110, easing = Motion.Emphasised))
        delay(60)
        progress.animateTo(0f, tween(220, easing = Motion.Accelerate))
        onFinished()
    }

    Box(
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            scaleX = p
            scaleY = p
            // Fades a little faster than it shrinks, so the row dissolves as it collapses rather
            // than hanging around as a shrinking thumbnail.
            alpha = (p * 1.4f).coerceIn(0f, 1f)
        },
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WishlistRowCard(
    row: WishlistRow,
    currencySymbol: String,
    currencyCode: String,
    fx: com.discounthunter.android.core.model.FxRates?,
    onOpenDetails: () -> Unit,
    onGetDeal: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DealCardCompact(
        modifier = modifier,
        title = row.entry.title,
        subtitle = buildString {
            append(row.addedLabel)
            // A separator only when there is something above it, so a row with no date does not
            // start with a stray bullet.
            if (row.addedLabel.isNotEmpty() && row.isOnSale) append("  •  ")
            if (row.isOnSale) append("On sale now")
        },
        thumbUrl = row.entry.thumbUrl,
        // "Added today" and "On sale now" are two facts; one line truncated the second to
        // "On sale n…".
        subtitleMaxLines = 2,
        onClick = onOpenDetails,
        // The star sits above the deal button in a single right-hand column, so the button lines up
        // with the game's own data the way it does on the Deals tab.
        trailing = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Remove ${row.entry.title} from your wishlist",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                FilledTonalButton(
                    onClick = onGetDeal,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (row.liveDeal?.isFree == true) "Claim free" else "Get deal",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        leading = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // A long store name ("Epic Games Store" shrunk, or the long GOG label) can push the
                // second badge onto its own line. `FlowRow` puts no gap between lines, so the badges
                // carry their own row spacing rather than the surrounding column's.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The badge follows the live deal's store, so the tag and the price below it
                    // always describe the same shop.
                    StoreBadge(store = row.shownStore, short = true)
                    if (row.isOnSale) {
                        Pill(
                            text = "ON SALE",
                            container = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            content = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                val live = row.liveDeal
                if (live != null) {
                    // No store suffix: the badge above already names the shop, and printing it
                    // twice was how the row came to disagree with itself.
                    BestPriceLabel(
                        price = live.salePrice,
                        storeName = null,
                        currencySymbol = currencySymbol,
                        isFree = live.isFree,
                        currencyCode = live.currencyCode,
                        chosenCode = currencyCode,
                        fx = fx,
                    )
                }
            }
        },
    )
}