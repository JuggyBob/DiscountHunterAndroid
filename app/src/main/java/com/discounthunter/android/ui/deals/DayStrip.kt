package com.discounthunter.android.ui.deals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.ui.components.Pill
import com.discounthunter.android.ui.components.StoreBadge
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * The horizontal day strip.
 *
 * Only Epic schedules giveaways in advance, so the markers are specific to that: a dot means a
 * giveaway starts or ends that day. Tapping a day switches the feed; the selected day is scrolled
 * into view whenever the selection changes from outside the strip (the "Today" button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayStrip(
    days: List<DayChip>,
    onSelect: (LocalDate) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return
    val listState = rememberLazyListState()

    // Keep the selected day visible when it changes from outside the strip.
    val selectedIndex = days.indexOfFirst { it.isSelected }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            listState.animateScrollToItem(
                index = (selectedIndex - 2).coerceAtLeast(0),
            )
        }
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous day",
            )
        }

        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(items = days, key = { it.date.toString() }) { chip ->
                DayCell(chip = chip, onClick = { onSelect(chip.date) })
            }
        }

        IconButton(onClick = onNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next day",
            )
        }
    }
}

@Composable
private fun DayCell(chip: DayChip, onClick: () -> Unit) {
    val selected = chip.isSelected
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val foreground = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
                contentDescription = buildString {
                    append(chip.date.format(DAY_FORMAT))
                    if (chip.isToday) append(", today")
                    if (chip.hasGiveaway) append(", giveaway day")
                    if (selected) append(", selected")
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = chip.date.dayOfWeek
                .getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
                .take(3),
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
        )
        Text(
            text = chip.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = foreground,
        )
        // The marker row is always present so cells keep a consistent height whether or not a
        // giveaway is scheduled.
        Box(
            modifier = Modifier.size(6.dp).clip(CircleShape).background(
                if (chip.hasGiveaway) {
                    if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.tertiary
                } else {
                    Color.Transparent
                },
            ),
        )
    }
}

/** A compact summary row above the list: how much matched, plus a jump to the filter sheet. */
@Composable
fun DealsSummaryBar(
    state: DealsUiState,
    onOpenFilters: () -> Unit,
    onGoToToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.isFromSnapshot) {
            Pill(
                text = "SNAPSHOT",
                container = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                content = MaterialTheme.colorScheme.tertiary,
            )
        }

        Text(
            // The count already sits in the app bar's subtitle, which scrolls away; this row
            // stays pinned above the list and therefore earns its space by reporting *how* the
            // list is currently arranged instead of repeating it.
            text = buildList {
                if (state.isFiltered) add(state.discountFilter.label)
                add("Sorted by ${state.sortOrder.label.lowercase()}")
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )

        if (!state.isToday) {
            FilterChip(
                selected = false,
                onClick = onGoToToday,
                label = { Text("Today") },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }

        FilterChip(
            selected = state.isFiltered || state.enabledStores.size < StoreId.MAJOR.size,
            onClick = onOpenFilters,
            label = { Text("Filters") },
            leadingIcon = {
                Icon(
                    Icons.Outlined.FilterList,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}

/** The non-fatal notice strip (one store down, a snapshot being shown, a future date). */
@Composable
fun DealsNotice(state: DealsUiState, modifier: Modifier = Modifier) {
    val text = state.notice ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (state.isFromSnapshot) Icons.Outlined.CalendarMonth else Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A store toggle chip used inside the filter sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreToggleChip(
    store: StoreId,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { Text(store.displayName) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/** The "show more" footer for the paginated list. */
@Composable
fun LoadMoreRow(
    remaining: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        FilterChip(
            selected = false,
            onClick = onClick,
            label = {
                Text(
                    text = if (remaining > 0) "Show $remaining more" else "Show more",
                    textAlign = TextAlign.Center,
                )
            },
        )
    }
}

/** A one-line reminder that the stores are live. */
@Composable
fun StoresFooter(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        StoreBadge(store = StoreId.STEAM, short = true)
        Spacer(Modifier.width(6.dp))
        StoreBadge(store = StoreId.EPIC, short = true)
        Spacer(Modifier.width(6.dp))
        StoreBadge(store = StoreId.GOG, short = true)
    }
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
