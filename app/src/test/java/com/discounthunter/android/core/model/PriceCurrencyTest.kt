package com.discounthunter.android.core.model

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A price is *converted* for the user's currency, not merely relabelled.
 *
 * ## The bug this pins down
 *
 * Two rounds of the same complaint. First the app relabelled a GOG dollar price with a euro sign.
 * Fixing that by labelling it honestly (`$0.49 USD`) meant the currency setting did nothing at all
 * for a third of the app — which is worse, because the user changed a setting and nothing changed.
 *
 * GOG cannot be persuaded: its catalogue ignores `currency`, `currency_code`,
 * `userSelectedCurrency` and `locale`+`country` alike, all verified to still answer
 * `"currency":"USD"`. So the app converts, and these tests hold the rules that make that honest.
 */
class PriceCurrencyTest {

    private val dollar = "$"
    private val euro = "\u20AC"
    private val pound = "\u00A3"

    /** Rates as the ECB would publish them: 1 USD = 0.92 EUR = 0.78 GBP. */
    private fun rates() = FxRates(
        io = UnconfinedTestDispatcher(),
        fetch = { _ ->
            Result.success(
                """{"amount":1.0,"base":"USD","date":"2026-10-09",
                   "rates":{"EUR":0.92,"GBP":0.78}}""",
            )
        },
    ).also { kotlinx.coroutines.runBlocking { it.ensure() } }

    @Test
    fun `a store that did not say keeps the chosen currency`() {
        assertEquals("$9.99", PriceFormat.priceWith(9.99, "USD", "", null))
    }

    @Test
    fun `a quoted currency matching the setting needs no conversion`() {
        val fx = rates()
        assertEquals("$9.99", PriceFormat.priceWith(9.99, "USD", "USD", fx))
        assertEquals("\u20AC9.99", PriceFormat.priceWith(9.99, "EUR", "EUR", fx))
    }

    @Test
    fun `a dollar price converts for a euro user`() {
        // The case that started all of this: a GOG dollar price shown to someone who chose EUR.
        assertEquals("~\u20AC0.45", PriceFormat.priceWith(0.49, "EUR", "USD", rates()))
    }

    @Test
    fun `a dollar price converts for a pound user`() {
        assertEquals("~\u00A30.38", PriceFormat.priceWith(0.49, "GBP", "USD", rates()))
    }

    @Test
    fun `a converted price is marked as an estimate`() {
        // It is not a quoted price and the user is about to spend money on the strength of it.
        assertEquals(
            true,
            PriceFormat.priceWith(0.49, "EUR", "USD", rates()).startsWith("~"),
        )
    }

    @Test
    fun `without rates the original currency is shown rather than a wrong one`() {
        // Null rates: the pre-loaded state. Falling back to the store's own currency is honest;
        // converting with no rate would be inventing a number.
        assertEquals("$0.49 USD", PriceFormat.priceWith(0.49, "EUR", "USD", null))
    }

    @Test
    fun `a free price never grows a currency suffix`() {
        assertEquals("Free", PriceFormat.priceWith(0.0, "EUR", "USD", rates()))
        assertEquals("Free", PriceFormat.priceWith(0.0, "USD", "", null))
    }

    @Test
    fun `an unknown currency code shows the code without a borrowed symbol`() {
        // JPY has no entry in [PriceFormat.symbolFor] and no rate. Falling back to the user's own
        // symbol here would put a dollar sign on a yen amount, which is the exact bug this whole
        // class exists to prevent.
        assertEquals("5.00 JPY", PriceFormat.priceWith(5.0, "USD", "JPY", rates()))
    }

    @Test
    fun `the currency code is matched case insensitively`() {
        assertEquals("~\u20AC0.45", PriceFormat.priceWith(0.49, "EUR", "usd", rates()))
    }

    @Test
    fun `symbols resolve for the currencies the app offers`() {
        assertEquals("$", PriceFormat.symbolFor("USD"))
        assertEquals("\u20AC", PriceFormat.symbolFor("EUR"))
        assertEquals("\u00A3", PriceFormat.symbolFor("GBP"))
        assertEquals(null, PriceFormat.symbolFor("CHF"))
    }

    @Test
    fun `grouping and decimals stay locale independent`() {
        assertEquals("$1,234.56", PriceFormat.priceWith(1234.56, "USD", "USD", rates()))
    }
}
