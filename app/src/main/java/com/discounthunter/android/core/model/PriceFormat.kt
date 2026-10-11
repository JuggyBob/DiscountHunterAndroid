package com.discounthunter.android.core.model

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Formats store prices.
 *
 * Two rules, both deliberate:
 *
 *  * **A price at or near zero reads as "Free."** Every store publishes free games as `0.00`, and
 *    showing "$0.00" where the truth is "this costs nothing" is a worse answer than the raw number.
 *  * **The digits are formatted with `Locale.US`.** The user picks the currency explicitly
 *    (USD/EUR/GBP) and the app labels prices with that currency's own symbol. Letting the device
 *    locale decide the separators would print `$9,99` — a dollar sign with comma decimals — because
 *    the phone is set to a European region. The currency choice and the number formatting have to
 *    agree.
 */
object PriceFormat {

    private const val FREE_LABEL = "Free"
    private const val FREE_EPSILON = 0.001

    private val decimals = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

    /** Formats [value] with [symbol], or as "Free" when it is effectively zero. */
    fun price(value: Double, symbol: String): String =
        if (value <= FREE_EPSILON) FREE_LABEL else symbol + decimals.format(value)

    /**
     * Formats [value] for a viewer who chose [chosenCode], given that the store quoted it in
     * [actualCode], converting when the two differ.
     *
     * ## The rule
     *
     *  * Same currency, or the store did not say — print it with the chosen symbol. Steam and Epic
     *    genuinely re-quote per region, so their numbers already *are* in the chosen currency.
     *  * Different currency and a rate is available — **convert**, and say so. A GOG dollar price
     *    shown to a euro user has to become euros or the currency setting does nothing for a third
     *    of the app. Printing `$0.49` under a euro sign states a price the user cannot buy; printing
     *    it as `$0.49 USD` is honest but reads as a bug.
     *  * Different currency and no rate — fall back to the store's own currency with its code.
     *    Better a labelled foreign price than an invented one.
     *
     * [fx] may be null, which is the "rates not loaded yet" case and behaves as rule three.
     */
    fun priceWith(
        value: Double,
        chosenCode: String,
        actualCode: String,
        fx: FxRates?,
    ): String {
        if (value <= FREE_EPSILON) return FREE_LABEL
        val target = chosenCode.trim().uppercase(Locale.US)
        val source = actualCode.trim().uppercase(Locale.US)
        val symbol = symbolFor(target) ?: return price(value, "$")

        if (source.isEmpty() || source == target) return price(value, symbol)

        val converted = fx?.takeIf { it.canConvert(source, target) }?.let {
            it.convert(value, source, target)
        }
        return if (converted != null) {
            // "~" rather than a bare number: this is a converted estimate, not a quoted price, and
            // the user is about to spend real money on the strength of it.
            "~" + price(converted, symbol)
        } else {
            // No rate, so the store's own figure stands. Its symbol and code are both shown, or —
            // when the currency is one we have no symbol for — just the code, because printing the
            // user's symbol next to a foreign amount would be the original bug all over again.
            val own = symbolFor(source)
            if (own != null) own + decimals.format(value) + " " + source
            else decimals.format(value) + " " + source
        }
    }

    /** The symbol for an ISO code, or null when it is one we do not render specially. */
    fun symbolFor(code: String): String? = when (code.trim().uppercase(Locale.US)) {
        "USD" -> "$"
        "EUR" -> "\u20AC"
        "GBP" -> "\u00A3"
        else -> null
    }

    /** Formats [value] as a bare amount with no currency symbol. */
    fun amount(value: Double): String = decimals.format(value)

    /** True when [value] should be presented as free. */
    fun isFree(value: Double): Boolean = value <= FREE_EPSILON
}
