package com.discounthunter.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.FxRates
import com.discounthunter.android.core.model.PriceFormat
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.ui.theme.LocalExtraColors
import com.discounthunter.android.ui.theme.isDarkTheme
import com.discounthunter.android.ui.theme.storePalette

/** A small rounded label. The app's main way of tagging a card at a glance. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    emphasised: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (emphasised) MaterialTheme.colorScheme.primary else container)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasised) MaterialTheme.colorScheme.onPrimary else content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The store badge, coloured with the storefront's own brand palette. */
@Composable
fun StoreBadge(
    store: StoreId,
    modifier: Modifier = Modifier,
    short: Boolean = false,
) {
    val palette = storePalette(store, isDarkTheme)
    Pill(
        text = if (short) store.shortName else store.displayName,
        modifier = modifier,
        container = palette.container,
        content = palette.content,
    )
}

/**
 * The discount badge — the single most-scanned element on a deal card.
 *
 * Free games read `FREE` rather than `-100%`, because "free" is the actual fact and it is easier
 * to act on at a glance.
 */
@Composable
fun DiscountBadge(
    deal: Deal,
    modifier: Modifier = Modifier,
) {
    if (deal.isFree) {
        Pill(
            text = "FREE",
            modifier = modifier,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            emphasised = true,
        )
    } else {
        Pill(
            text = deal.savingsLabel,
            modifier = modifier,
            container = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            content = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A "scheduled / upcoming" marker for Epic giveaways that have not started yet. */
@Composable
fun UpcomingBadge(modifier: Modifier = Modifier) {
    Pill(
        text = "UPCOMING",
        modifier = modifier,
        container = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
        content = MaterialTheme.colorScheme.tertiary,
    )
}

/** A "saved" marker, shown on any card whose game is on the wishlist. */
@Composable
fun WishlistBadge(modifier: Modifier = Modifier) {
    Pill(
        text = "SAVED",
        modifier = modifier,
        container = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
        content = MaterialTheme.colorScheme.secondary,
    )
}

/**
 * The price line: sale price, with the original struck through beside it when it is meaningfully
 * higher. The original price is hidden for free games, where there is nothing to compare against.
 */
@Composable
fun PriceLine(
    salePrice: Double,
    originalPrice: Double,
    currencySymbol: String,
    isFree: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /**
     * The currency the store actually quoted these prices in, when it said.
     *
     * Blank means the caller has already converted or the store re-quotes per region — see
     * [PriceFormat.priceWith].
     */
    currencyCode: String = "",
    /** Rates for converting [currencyCode] into the user's chosen currency. */
    fx: FxRates? = null,
    /** The user's chosen currency, as an ISO code. */
    chosenCode: String = "USD",
) {
    val muted = LocalExtraColors.current.mutedPrice
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = PriceFormat.priceWith(salePrice, chosenCode, currencyCode, fx),
            style = if (compact) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = if (isFree) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (!isFree && originalPrice > salePrice + Deal.PRICE_EPSILON) {
            Text(
                text = PriceFormat.priceWith(originalPrice, chosenCode, currencyCode, fx),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                textDecoration = TextDecoration.LineThrough,
            )
        }
    }
}

/**
 * A compact "best price" line used on search results and wishlist rows.
 *
 * [storeName] is nullable because the wishlist row already shows the store it was saved from as a
 * badge directly above this line. Printing the store again is only informative when the best deal
 * came from somewhere *else*, so the caller passes null to suppress the duplicate.
 */
@Composable
fun BestPriceLabel(
    price: Double,
    storeName: String?,
    currencySymbol: String,
    isFree: Boolean,
    modifier: Modifier = Modifier,
    /** The currency the store quoted [price] in; blank means it is already the user's. */
    currencyCode: String = "",
    /** The user's chosen currency, for conversion. */
    chosenCode: String = "USD",
    fx: FxRates? = null,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (isFree) {
                "Best: FREE"
            } else {
                // Same conversion the feed and the detail page use, so one game cannot show three
                // different numbers in three places.
                "Best: ${PriceFormat.priceWith(price, chosenCode, currencyCode, fx)}"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!storeName.isNullOrBlank()) {
            Text(
                text = " · $storeName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A thin circular progress ring used on a card that is still loading. */
@Composable
fun InlineSpinner(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 16.dp) {
    androidx.compose.material3.CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary,
    )
}

