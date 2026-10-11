package com.discounthunter.android.ui.deals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.FxRates
import com.discounthunter.android.ui.components.DiscountBadge
import com.discounthunter.android.ui.components.NetworkArtwork
import com.discounthunter.android.ui.components.PriceLine
import com.discounthunter.android.ui.components.StoreBadge
import com.discounthunter.android.ui.components.UpcomingBadge
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import com.discounthunter.android.ui.theme.Motion
import com.discounthunter.android.ui.components.WishlistBadge

/**
 * One deal in the feed.
 *
 * ## Identity, not position
 *
 * The card is a pure function of an immutable [Deal], and the enclosing `LazyColumn` keys rows by
 * `deal.id` — the store-native id. Compose therefore treats a moved, filtered or re-sorted row as
 * the same item rather than a new one, and no per-row mutable state exists that a recycled slot
 * could inherit from its previous occupant.
 *
 * The artwork URL also comes only from this deal, so the image loader cannot pair a title with
 * somebody else's picture.
 */
@Composable
fun DealCard(
    deal: Deal,
    currencySymbol: String,
    currencyCode: String,
    fx: FxRates?,
    isSaved: Boolean,
    onOpen: () -> Unit,
    onGetDeal: () -> Unit,
    onToggleWishlist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics {
                contentDescription = buildString {
                    append(deal.title)
                    append(", ").append(deal.store.displayName)
                    append(", ").append(deal.savingsLabel)
                }
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetworkArtwork(
                candidates = listOf(deal.thumbUrl),
                contentDescription = null,
                modifier = Modifier
                    .width(132.dp)
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(8.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = deal.title.ifBlank { "Unknown title" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    WishlistToggle(isSaved = isSaved, onToggle = onToggleWishlist, title = deal.title)
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DiscountBadge(deal = deal)
                    StoreBadge(store = deal.store, short = true)
                    if (deal.isUpcoming) UpcomingBadge()
                    if (isSaved) WishlistBadge()
                }

                // A free game already says so three times — a "FREE" badge, a "Free" price and a
                // "Get it free" button — so the price line is dropped for those cards rather than
                // repeating the one fact a fourth time. Discounted deals keep their prices.
                if (!deal.isFree) {
                    Spacer(Modifier.height(8.dp))
                    PriceLine(
                        salePrice = deal.salePrice,
                        originalPrice = deal.normalPrice,
                        currencySymbol = currencySymbol,
                        isFree = false,
                        // GOG quotes in dollars whatever region is asked for, so its rows are
                        // converted rather than relabelled - the same rule the detail page uses, so
                        // one game shows one number everywhere.
                        currencyCode = deal.currencyCode,
                        chosenCode = currencyCode,
                        fx = fx,
                    )
                }

                Spacer(Modifier.height(10.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    FilledTonalButton(
                        onClick = onGetDeal,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 6.dp,
                        ),
                    ) {
                        Text(
                            text = if (deal.isFree) "Get it free" else "Get deal",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The wishlist star.
 *
 * A dedicated `IconButton` rather than a `Checkbox` so pressing it never also registers as a tap on
 * the card underneath. The state comes from the shared wishlist service, so the same game shows the
 * same star everywhere in the app.
 */
@Composable
fun WishlistToggle(
    isSaved: Boolean,
    title: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier.size(36.dp),
    ) {
        // Two-stage pop: overshoot out, then settle back, so a press feels like it *pops* rather
        // than switches.
        //
        // The animation is deliberately **not** keyed on `isSaved`. Keying it there meant every tap
        // cancelled a running animation and started a new one from a fresh `snapTo`, and a user
        // tapping quickly queued dozens of springs against the same `Animatable` — the shape of
        // input most likely to leave one of them mid-flight when its node leaves the tree. Keying
        // on a tick that only advances when the *pop* should play means repeated taps on an
        // already-correct state do nothing at all, and the common case (toggle, toggle, toggle)
        // animates once each time instead of racing itself.
        val pop = remember { Animatable(1f) }
        var playTick by remember { mutableIntStateOf(0) }
        var wasSaved by remember { mutableStateOf(isSaved) }

        // Records the intent the instant it is expressed, so the icon and the pop agree on the
        // same frame rather than waiting for the wishlist write to come back from disk.
        LaunchedEffect(isSaved) {
            if (isSaved != wasSaved) {
                wasSaved = isSaved
                playTick++
            }
        }

        LaunchedEffect(playTick) {
            if (playTick == 0) return@LaunchedEffect
            // `snapTo` cancels whatever was running; `animateTo` then settles it. The try/catch is
            // belt and braces: a cancellation here is normal, and must never escape as a crash.
            runCatching {
                pop.snapTo(if (isSaved) 0.7f else 1.15f)
                pop.animateTo(1f, Motion.pop())
            }
        }

        Icon(
            imageVector = if (isSaved) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (isSaved) {
                "Remove $title from your wishlist"
            } else {
                "Add $title to your wishlist"
            },
            tint = if (isSaved) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.scale(pop.value),
        )
    }
}

/** A compact variant used by the search results and wishlist screens. */
@Composable
fun DealCardCompact(
    title: String,
    subtitle: String,
    thumbUrl: String,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    /** Rows whose subtitle carries two facts give it a second line rather than an ellipsis. */
    subtitleMaxLines: Int = 1,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NetworkArtwork(
                candidates = listOf(thumbUrl),
                contentDescription = null,
                modifier = Modifier
                    .width(104.dp)
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(8.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifBlank { "Unknown title" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (leading != null) {
                    Spacer(Modifier.height(8.dp))
                    leading()
                }
            }
            trailing()
        }
    }
}
