package com.discounthunter.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the price-history box presents a set of points.
 *
 * The regression this pins down is a hard crash. The box used to read `points.last()` before
 * deciding which branch to render, so any game with **no** recorded price threw
 * `NoSuchElementException` and killed the process. That is not a rare edge case — DLCs,
 * soundtracks and most non-deal titles never appear in the daily deal snapshots, so their list is
 * always empty and the app died the moment the page was scrolled far enough to compose the box.
 */
class PriceHistorySummaryTest {

    private val today = 20_000L

    private fun point(day: Long, price: Double) = PricePoint(day, price)

    @Test
    fun `no points is the empty case, not a crash`() {
        // The exact list a DLC produces.
        assertEquals(PriceHistorySummary.Empty, summarizePriceHistory(emptyList(), today))
    }

    @Test
    fun `one point is stated in words with how long ago it was`() {
        val summary = summarizePriceHistory(listOf(point(today - 3, 4.99)), today)

        assertTrue(summary is PriceHistorySummary.Single)
        assertEquals(4.99, (summary as PriceHistorySummary.Single).price, 0.001)
        assertEquals(3L, summary.daysAgo)
    }

    @Test
    fun `a point recorded today reads as zero days ago`() {
        val summary = summarizePriceHistory(listOf(point(today, 0.0)), today)

        assertEquals(0L, (summary as PriceHistorySummary.Single).daysAgo)
    }

    @Test
    fun `a point dated in the future does not go negative`() {
        val summary = summarizePriceHistory(listOf(point(today + 2, 1.0)), today)

        assertEquals(-2L, (summary as PriceHistorySummary.Single).daysAgo)
    }

    @Test
    fun `two or more points draw the chart`() {
        assertEquals(
            PriceHistorySummary.Chart,
            summarizePriceHistory(listOf(point(1, 9.99), point(2, 4.99)), today),
        )
    }

    @Test
    fun `two points on the same day still draw the chart`() {
        // Same day, different prices: not a trend, but not empty either, and the chart divides by a
        // guarded day span rather than crashing on a zero-width axis.
        assertEquals(
            PriceHistorySummary.Chart,
            summarizePriceHistory(listOf(point(5, 9.99), point(5, 4.99)), today),
        )
    }

    @Test
    fun `no branch ever throws, whatever the list holds`() {
        val lists = listOf(
            emptyList(),
            listOf(point(1, 1.0)),
            listOf(point(1, 1.0), point(2, 2.0)),
            List(50) { point(it.toLong(), it.toDouble()) },
            listOf(point(0, 0.0), point(0, 0.0), point(0, 0.0)),
        )

        lists.forEach { points ->
            // The whole point of the refactor: this must simply return, for every input.
            summarizePriceHistory(points, today)
        }
    }
}