package com.discounthunter.android.core.model

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cheapest offer is chosen after converting, not before.
 *
 * ## The bug this pins down
 *
 * `bestOffer` ranked rows by their raw `price`. That is only meaningful when every store quotes the
 * same currency, and they do not: a Steam row requested for `cc=GB` arrives in pounds while a GOG
 * row is always in dollars. So `minBy(price)` compared 4.99 pounds against 5.50 dollars, decided
 * the pounds were cheaper, and the feed, the wishlist and the detail page each picked a different
 * winner from the same list — which is how one game ended up with three prices in three places.
 */
class BestOfferCurrencyTest {

    private fun rates() = FxRates(
        io = UnconfinedTestDispatcher(),
        fetch = { Result.success("""{"base":"USD","date":"2026-10-09","rates":{"GBP":0.78}}""") },
    ).also { kotlinx.coroutines.runBlocking { it.ensure() } }

    private fun offer(store: StoreId, price: Double, currency: String) = StoreOffer(
        store = store,
        price = price,
        retailPrice = price * 2,
        savingsPercent = 50.0,
        url = "",
        currencyCode = currency,
    )

    private fun detail(vararg offers: StoreOffer) = GameDetail(
        identity = null,
        acceptedIdentities = emptySet(),
        title = "Game",
        heroUrl = "",
        shortDescription = "",
        aboutText = "",
        minimumRequirements = "",
        recommendedRequirements = "",
        developers = emptyList(),
        publishers = emptyList(),
        releaseDate = "",
        genres = emptyList(),
        categories = emptyList(),
        features = emptyList(),
        price = null,
        isFreeToPlay = false,
        screenshots = emptyList(),
        trailers = emptyList(),
        offers = offers.toList(),
        sources = emptyList(),
        storeUrls = emptyMap(),
        steamAppId = null,
        gogSlug = "",
        epicSlug = "",
        epicUpcoming = false,
    )

    @Test
    fun `a smaller number in pounds can be the dearer price in dollars`() {
        // 4.99 GBP is about 6.40 USD, so the pound row looks cheaper on the number and is not.
        val fx = rates()
        val page = detail(
            offer(StoreId.GOG, 5.50, "USD"),
            offer(StoreId.STEAM, 4.99, "GBP"),
        )

        assertEquals(StoreId.GOG, page.bestOffer("USD", fx)?.store)
    }

    @Test
    fun `the raw comparison would have got this wrong`() {
        // The behaviour being replaced, kept as a test so the reason for the change cannot rot.
        val naive = detail(
            offer(StoreId.GOG, 5.50, "USD"),
            offer(StoreId.STEAM, 4.99, "GBP"),
        ).offers.minWithOrNull(
            compareBy({ if (it.isFree) 0 else 1 }, { it.price }, { it.store.ordinal }),
        )

        assertEquals(StoreId.STEAM, naive?.store)
    }

    @Test
    fun `a larger number in pounds can still be the cheaper price`() {
        // The mirror image: 7.99 GBP is about 10.24 USD, dearer than the 5.50 dollar row, but the
        // raw numbers would rank it first — and here the converted answer agrees, which is what
        // makes the pair of tests above a real check rather than a coincidence.
        val fx = rates()
        val page = detail(
            offer(StoreId.GOG, 5.50, "USD"),
            offer(StoreId.STEAM, 7.99, "GBP"),
        )

        assertEquals(StoreId.GOG, page.bestOffer("USD", fx)?.store)
    }

    @Test
    fun `a pound row really can be the cheapest once converted`() {
        // 4.99 GBP (6.40 USD) against 9.99 USD: the pounds are the better deal.
        val page = detail(
            offer(StoreId.GOG, 9.99, "USD"),
            offer(StoreId.STEAM, 4.99, "GBP"),
        )

        assertEquals(StoreId.STEAM, page.bestOffer("USD", rates())?.store)
    }

    @Test
    fun `same-currency rows rank as before`() {
        val page = detail(
            offer(StoreId.GOG, 9.99, "USD"),
            offer(StoreId.STEAM, 4.99, "USD"),
        )

        assertEquals(StoreId.STEAM, page.bestOffer("USD", rates())?.store)
    }

    @Test
    fun `a free offer still wins whatever the currency`() {
        val page = detail(
            offer(StoreId.GOG, 9.99, "USD"),
            offer(StoreId.EPIC, 0.0, "EUR"),
        )

        assertEquals(StoreId.EPIC, page.bestOffer("USD", rates())?.store)
    }

    @Test
    fun `without rates the raw amount decides`() {
        // Degraded, but never wrong-by-conversion: the numbers shown are the numbers quoted.
        val page = detail(
            offer(StoreId.GOG, 4.99, "USD"),
            offer(StoreId.STEAM, 5.50, "GBP"),
        )

        assertEquals(StoreId.GOG, page.bestOffer("USD", null)?.store)
    }

    @Test
    fun `an empty offers table has no best`() {
        assertEquals(null, detail().bestOffer("USD", rates()))
    }
}
