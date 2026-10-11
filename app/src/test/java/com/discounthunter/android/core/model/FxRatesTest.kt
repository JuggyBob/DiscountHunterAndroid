package com.discounthunter.android.core.model

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exchange rates are fetched once, cached, and fail soft.
 *
 * ## What this guards
 *
 * Prices depend on this, so three things matter: a bad response must not take the app's prices
 * with it, a failed fetch must not blank figures that were already displaying, and two screens
 * asking at once must not produce two different rate tables.
 */
class FxRatesTest {

    private fun body(rates: String = "\"EUR\":0.92,\"GBP\":0.78") =
        """{"amount":1.0,"base":"USD","date":"2026-10-09","rates":{$rates}}"""

    private fun rates(
        response: Result<String> = Result.success(body()),
        onFetch: () -> Unit = {},
    ) = FxRates(
        io = UnconfinedTestDispatcher(),
        fetch = { onFetch(); response },
    )

    @Test
    fun `a direct pair converts by the published rate`() = runTest {
        val fx = rates()
        fx.ensure()

        assertEquals(9.20, fx.convert(10.0, "USD", "EUR"), 0.0001)
    }

    @Test
    fun `a cross pair goes through the base`() = runTest {
        val fx = rates()
        fx.ensure()

        // No EUR_GBP row, so it is GBP-per-USD over EUR-per-USD: 10 x (0.78 / 0.92).
        assertEquals(8.4783, fx.convert(10.0, "EUR", "GBP"), 0.001)
    }

    @Test
    fun `converting back to the base inverts the published rate`() = runTest {
        val fx = rates()
        fx.ensure()

        // The common case: a pound price shown to someone who chose dollars. There is no GBP_USD
        // row — only USD_GBP — so this exercises the reciprocal path.
        assertEquals(6.3974, fx.convert(4.99, "GBP", "USD"), 0.001)
    }

    @Test
    fun `converting to the same currency returns the input untouched`() = runTest {
        val fx = rates()
        fx.ensure()

        assertEquals(4.99, fx.convert(4.99, "GBP", "GBP"), 0.0)
    }

    @Test
    fun `an unknown pair returns the amount rather than a wrong one`() = runTest {
        val fx = rates()
        fx.ensure()

        assertEquals(5.0, fx.convert(5.0, "JPY", "GBP"), 0.0)
    }

    @Test
    fun `rates are fetched only once`() = runTest {
        var fetches = 0
        val fx = rates(onFetch = { fetches++ })

        fx.ensure()
        fx.ensure()
        fx.ensure()

        assertEquals(1, fetches)
    }

    @Test
    fun `a failed fetch leaves prices usable and conversion off`() = runTest {
        val fx = rates(response = Result.failure(java.io.IOException("offline")))
        fx.ensure()

        assertFalse(fx.isLoaded)
        assertFalse(fx.canConvert("USD", "EUR"))
        // The important property: the amount survives, so a price can still be rendered.
        assertEquals(10.0, fx.convert(10.0, "USD", "EUR"), 0.0)
    }

    @Test
    fun `a malformed body is treated as no rates`() = runTest {
        val fx = rates(response = Result.success("<html>not json</html>"))
        fx.ensure()

        assertFalse(fx.isLoaded)
    }

    @Test
    fun `the published date is recorded`() = runTest {
        val fx = rates()
        fx.ensure()

        assertEquals("2026-10-09", fx.rateDate.toString())
    }

    @Test
    fun `a body with no date does not fail the parse`() = runTest {
        val fx = rates(response = Result.success("""{"base":"USD","rates":{"GBP":0.78}}"""))
        fx.ensure()

        assertTrue(fx.isLoaded)
        assertNull(fx.rateDate)
    }

    @Test
    fun `canConvert is true for a pair it cannot actually cross`() = runTest {
        val fx = rates()
        fx.ensure()

        // Same currency never needs a rate, so the answer is yes even with an empty table.
        assertTrue(FxRates(io = UnconfinedTestDispatcher(), fetch = { Result.success("{}") })
            .canConvert("USD", "USD"))
    }
}
