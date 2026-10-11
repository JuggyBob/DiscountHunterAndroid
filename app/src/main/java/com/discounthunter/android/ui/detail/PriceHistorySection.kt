package com.discounthunter.android.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.discounthunter.android.R
import com.discounthunter.android.core.model.PriceFormat
import com.discounthunter.android.core.model.PriceHistorySource
import com.discounthunter.android.core.model.PriceHistorySummary
import com.discounthunter.android.core.model.PricePoint
import com.discounthunter.android.core.model.summarizePriceHistory
import java.time.LocalDate

/**
 * The price-history box at the foot of the detail page.
 *
 * The chart shows prices this device has genuinely observed — see [PriceHistorySource] for why the
 * app does not scrape the databases it links to. The dropdown beside it offers those databases,
 * already filtered to the stores this game is actually listed on, and each one opens that site's
 * own page for the game.
 */
@Composable
fun PriceHistorySection(
    points: List<PricePoint>,
    sources: List<PriceHistorySource>,
    selected: PriceHistorySource?,
    currencySymbol: String,
    onSelectSource: (PriceHistorySource) -> Unit,
    onOpenSource: (PriceHistorySource) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.detail_price_history),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                SourceDropdown(
                    sources = sources,
                    selected = selected,
                    onSelect = onSelectSource,
                )
            }

            // Order matters, and so does the fact that the choice was made by
            // `summarizePriceHistory` — a total function over the list, not a `when` written here.
            // Reading `points.last()` before deciding threw `NoSuchElementException` for every game
            // with no recorded price, which is every DLC and soundtrack: they never appear in the
            // daily deal snapshots, so the list is empty and the app died on open.
            when (val summary = summarizePriceHistory(points, LocalDate.now().toEpochDay())) {
                PriceHistorySummary.Empty -> Text(
                    text = stringResource(R.string.detail_price_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )

                // A single observation is not a trend. Drawing it as a line over a zero-width axis
                // produces an empty box with a dot in the corner, so it is stated in words instead.
                is PriceHistorySummary.Single -> Text(
                    text = "One price recorded: ${PriceFormat.price(summary.price, currencySymbol)}" +
                        if (summary.daysAgo <= 0L) {
                            " today"
                        } else {
                            " ${summary.daysAgo} ${if (summary.daysAgo == 1L) "day" else "days"} ago"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )

                PriceHistorySummary.Chart -> PriceChart(
                    points = points,
                    currencySymbol = currencySymbol,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(120.dp),
                )
            }

            if (selected != null) {
                OutlinedButton(
                    onClick = { onOpenSource(selected) },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Icon(
                        Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.height(16.dp),
                    )
                    Text(
                        text = "${selected.label} — ${stringResource(R.string.detail_price_history_open)}",
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceDropdown(
    sources: List<PriceHistorySource>,
    selected: PriceHistorySource?,
    onSelect: (PriceHistorySource) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(
                text = selected?.label ?: sources.first().label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sources.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.label) },
                    onClick = {
                        onSelect(source)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * A minimal line chart: min and max are labelled because a price axis with no numbers is
 * decoration, not information.
 */
@Composable
private fun PriceChart(
    points: List<PricePoint>,
    currencySymbol: String,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labels = MaterialTheme.colorScheme.onSurfaceVariant

    val lowest = points.minOf { it.price }
    val highest = points.maxOf { it.price }
    // A flat series would divide by zero; give it a little room so the line sits mid-height.
    val span = (highest - lowest).takeIf { it > 0.0001 } ?: 1.0

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "High ${PriceFormat.price(highest, currencySymbol)}",
                style = MaterialTheme.typography.labelSmall,
                color = labels,
            )
            Text(
                text = "Low ${PriceFormat.price(lowest, currencySymbol)}",
                style = MaterialTheme.typography.labelSmall,
                color = labels,
            )
        }
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
            val first = points.first().epochDay.toFloat()
            val last = points.last().epochDay.toFloat()
            val daySpan = (last - first).takeIf { it > 0f } ?: 1f

            fun x(day: Long) = ((day - first) / daySpan) * size.width
            fun y(price: Double) =
                size.height - (((price - lowest) / span) * size.height).toFloat()

            repeat(3) { step ->
                val gy = size.height * step / 2f
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }

            val path = Path()
            points.forEachIndexed { index, point ->
                val px = x(point.epochDay)
                val py = y(point.price)
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }

            val area = Path().apply {
                addPath(path)
                lineTo(x(points.last().epochDay), size.height)
                lineTo(x(points.first().epochDay), size.height)
                close()
            }
            drawPath(area, fill)
            drawPath(path, line, style = Stroke(width = 3f, cap = StrokeCap.Round))

            points.forEach { point ->
                drawCircle(line, radius = 3.5f, center = Offset(x(point.epochDay), y(point.price)))
            }
        }
    }
}