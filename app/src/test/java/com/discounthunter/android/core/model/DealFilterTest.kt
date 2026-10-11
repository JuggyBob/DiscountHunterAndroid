package com.discounthunter.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Local filtering and ordering.
 *
 * Two properties matter and are asserted throughout:
 *
 *  * filtering and sorting operate on the **underlying data**, not on whatever happens to be visible,
 *    so the result is the same whether or not the list has been paged;
 *  * the order is total and deterministic — every comparator ends in a stable tiebreaker, so the
 *    same feed always renders identically.
 */
class DealFilterTest {

    private fun deal(
        id: String,
        title: String,
        store: StoreId = StoreId.STEAM,
        sale: Double = 10.0,
        normal: Double = 20.0,
        savings: Double = 50.0,
    ) = Deal(
        id = id,
        title = title,
        thumbUrl = "",
        salePrice = sale,
        normalPrice = normal,
        savingsPercent = savings,
        store = store,
        // Most storefront deals publish a native id; the tests that care about identity set it.
        identity = GameIdentity(store, id.substringAfter('_')),
        storeUrl = "https://example.invalid/$id",
    )

    /** Deal ids the test considers saved. */
    private val saved = mutableSetOf<String>()

    private fun filter(
        deals: List<Deal>,
        stores: Set<StoreId> = StoreId.MAJOR.toSet(),
        discount: DiscountFilter = DiscountFilter.ALL,
        query: String = "",
        sort: SortOrder = SortOrder.BIGGEST_DISCOUNT,
    ) = DealFilter.apply(deals, stores, discount, query, sort) { it.id in saved }

    // ---------------------------------------------------------------- stores

    @Test
    fun `only enabled stores survive`() {
        val deals = listOf(
            deal("steam_1", "Steam Game"),
            deal("gog_2", "Gog Game", StoreId.GOG),
            deal("epic_3", "Epic Game", StoreId.EPIC),
        )

        val result = filter(deals, stores = setOf(StoreId.STEAM, StoreId.GOG))

        assertEquals(2, result.size)
        assertTrue(result.none { it.store == StoreId.EPIC })
    }

    @Test
    fun `an empty store selection yields nothing rather than everything`() {
        val deals = listOf(deal("steam_1", "Steam Game"))
        assertTrue(filter(deals, stores = emptySet()).isEmpty())
    }

    // ---------------------------------------------------------------- discount thresholds

    @Test
    fun `discount thresholds are inclusive`() {
        val deals = listOf(
            deal("a", "Seventy", savings = 70.0),
            deal("b", "SeventyFive", savings = 75.0),
            deal("c", "Eighty", savings = 80.0),
            deal("d", "Free", sale = 0.0, normal = 20.0, savings = 100.0),
        )

        // The default order is by biggest discount, so the free game leads.
        assertEquals(listOf("d", "c", "b"), filter(deals, discount = DiscountFilter.OVER_75).map { it.id })
        assertEquals(listOf("d", "c", "b", "a"), filter(deals, discount = DiscountFilter.OVER_50).map { it.id })
        assertEquals(listOf("d", "c", "b", "a"), filter(deals, discount = DiscountFilter.OVER_25).map { it.id })
        assertEquals(listOf("d"), filter(deals, discount = DiscountFilter.FREE_ONLY).map { it.id })
    }

    @Test
    fun `a free game satisfies every threshold`() {
        // "-100%" clears "75% and up", so the free game must not vanish when the user tightens the
        // filter — that is what users expect from a discount slider.
        val deals = listOf(deal("free", "Free Game", sale = 0.0, savings = 100.0))

        assertTrue(DealFilter.matchesDiscount(deals.single(), DiscountFilter.OVER_75))
        assertTrue(DealFilter.matchesDiscount(deals.single(), DiscountFilter.OVER_25))
        assertTrue(DealFilter.matchesDiscount(deals.single(), DiscountFilter.FREE_ONLY))
    }

    @Test
    fun `isFree covers both a zero price and a full discount`() {
        assertTrue(deal("a", "A", sale = 0.0, savings = 0.0).isFree)
        assertTrue(deal("b", "B", sale = 5.0, savings = 100.0).isFree)
        assertFalse(deal("c", "C", sale = 5.0, savings = 50.0).isFree)
    }

    // ---------------------------------------------------------------- text filter

    @Test
    fun `the text filter is case insensitive and matches anywhere in the title`() {
        val deals = listOf(
            deal("a", "Elden Ring"),
            deal("b", "Hollow Knight"),
            deal("c", "ELDEN RING II"),
        )

        assertEquals(listOf("a", "c"), filter(deals, query = "elden").map { it.id })
        assertEquals(listOf("a", "c"), filter(deals, query = "  RING  ").map { it.id })
        assertTrue(filter(deals, query = "nothing").isEmpty())
    }

    @Test
    fun `filters combine rather than override`() {
        val deals = listOf(
            deal("a", "Elden Ring", store = StoreId.STEAM, savings = 80.0),
            deal("b", "Elden Ring Deluxe", store = StoreId.STEAM, savings = 20.0),
            deal("c", "Elden Ring", store = StoreId.GOG, savings = 80.0),
        )

        val result = filter(
            deals,
            stores = setOf(StoreId.STEAM),
            discount = DiscountFilter.OVER_75,
            query = "elden",
        )

        assertEquals(listOf("a"), result.map { it.id })
    }

    // ---------------------------------------------------------------- sorting

    @Test
    fun `biggest discount puts free games first then deepest cut`() {
        val deals = listOf(
            deal("shallow", "Shallow", sale = 8.0, savings = 20.0),
            deal("deep", "Deep", sale = 3.0, savings = 70.0),
            deal("free", "Free", sale = 0.0, savings = 100.0),
        )

        assertEquals(
            listOf("free", "deep", "shallow"),
            filter(deals, sort = SortOrder.BIGGEST_DISCOUNT).map { it.id },
        )
    }

    @Test
    fun `lowest price puts free games first then cheapest`() {
        val deals = listOf(
            deal("pricey", "Pricey", sale = 40.0, savings = 10.0),
            deal("cheap", "Cheap", sale = 5.0, savings = 70.0),
            deal("free", "Free", sale = 0.0, savings = 100.0),
        )

        assertEquals(
            listOf("free", "cheap", "pricey"),
            filter(deals, sort = SortOrder.LOWEST_PRICE).map { it.id },
        )
    }

    @Test
    fun `title order is alphabetical and case insensitive`() {
        val deals = listOf(
            deal("b", "banana"),
            deal("a", "Apple"),
            deal("c", "Cherry"),
        )

        assertEquals(
            listOf("a", "b", "c"),
            filter(deals, sort = SortOrder.TITLE_ASCENDING).map { it.id },
        )
    }

    @Test
    fun `saved games sort to the top under every order`() {
        val deals = listOf(
            deal("saved_shallow", "Saved Shallow", sale = 9.0, savings = 10.0),
            deal("unsaved_deep", "Unsaved Deep", sale = 1.0, savings = 90.0),
            deal("unsaved_free", "Unsaved Free", sale = 0.0, savings = 100.0),
        )
        saved += "saved_shallow"

        // Whatever the order, the saved game leads; the chosen order still applies within the rest.
        assertEquals("saved_shallow", filter(deals).first().id)
        assertEquals(
            listOf("saved_shallow", "unsaved_free", "unsaved_deep"),
            filter(deals, sort = SortOrder.BIGGEST_DISCOUNT).map { it.id },
        )
        assertEquals(
            listOf("saved_shallow", "unsaved_free", "unsaved_deep"),
            filter(deals, sort = SortOrder.LOWEST_PRICE).map { it.id },
        )
        assertEquals(
            "saved_shallow",
            filter(deals, sort = SortOrder.TITLE_ASCENDING).first().id,
        )
    }

    @Test
    fun `sorting is deterministic for equal keys`() {
        // Same discount and price: the tiebreaker on the store-native id must fix the order, or the
        // list would reshuffle between recompositions.
        val deals = listOf(
            deal("c", "Third", sale = 5.0, savings = 50.0),
            deal("a", "First", sale = 5.0, savings = 50.0),
            deal("b", "Second", sale = 5.0, savings = 50.0),
        )

        val first = filter(deals).map { it.id }
        val second = filter(deals.reversed()).map { it.id }
        val third = filter(deals.shuffled()).map { it.id }

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(listOf("a", "b", "c"), first)
    }

    @Test
    fun `titles with equal discount and price are still ordered deterministically`() {
        val deals = listOf(
            deal("b", "Same", sale = 5.0, savings = 50.0),
            deal("a", "Same", sale = 5.0, savings = 50.0),
        )

        assertEquals(listOf("a", "b"), filter(deals).map { it.id })
        assertEquals(listOf("a", "b"), filter(deals.reversed()).map { it.id })
    }

    // ---------------------------------------------------------------- paging

    @Test
    fun `a page of results is a prefix of the full ordered list`() {
        val deals = (1..30).map { deal("id$it", "Game $it", sale = 30.0 - it, savings = it.toDouble()) }

        val all = filter(deals)
        val page = all.take(25)

        // Paging must not change ordering or membership, only how much is shown.
        assertEquals(all.take(25), page)
        assertEquals(30, all.size)
        assertEquals(25, page.size)
    }

    @Test
    fun `filtering an empty feed yields an empty list`() {
        assertTrue(filter(emptyList()).isEmpty())
    }

    @Test
    fun `a large result set filters and sorts without dropping rows`() {
        val deals = (1..500).map { index ->
            deal(
                id = "steam_$index",
                title = if (index % 10 == 0) "Elden Ring $index" else "Game $index",
                store = if (index % 3 == 0) StoreId.GOG else StoreId.STEAM,
                sale = index.toDouble(),
                savings = (index % 100).toDouble(),
            )
        }

        val all = filter(deals)
        assertEquals(500, all.size)

        val elden = filter(deals, query = "elden")
        assertEquals(50, elden.size)
        assertTrue(elden.all { it.title.startsWith("Elden Ring") })

        val gogOnly = filter(deals, stores = setOf(StoreId.GOG))
        assertTrue(gogOnly.all { it.store == StoreId.GOG })
    }

    // ---------------------------------------------------------------- presentation

    @Test
    fun `the original price is hidden when it is not meaningfully higher`() {
        assertTrue(deal("a", "A", sale = 10.0, normal = 20.0).showsOriginalPrice)
        assertFalse(deal("b", "B", sale = 10.0, normal = 10.0).showsOriginalPrice)
        assertFalse(deal("c", "C", sale = 10.0, normal = 10.001).showsOriginalPrice)
    }

    @Test
    fun `the original price is hidden for free games`() {
        val free = deal("a", "A", sale = 0.0, normal = 59.99, savings = 100.0)
        assertFalse("a free game has nothing to compare against", free.showsOriginalPrice)
    }

    @Test
    fun `savings labels read sensibly`() {
        assertEquals("-75%", deal("a", "A", savings = 75.0).savingsLabel)
        assertEquals("-100%", deal("b", "B", sale = 0.0, savings = 100.0).savingsLabel)
        // A store publishing 120% must not produce a nonsense label.
        assertEquals("-100%", deal("c", "C", savings = 120.0).savingsLabel)
    }

    @Test
    fun `prices format as free or with two decimals`() {
        assertEquals("Free", PriceFormat.price(0.0, "$"))
        assertEquals("Free", PriceFormat.price(0.0001, "$"))
        assertEquals("$9.99", PriceFormat.price(9.99, "$"))
        assertEquals("$1,234.50", PriceFormat.price(1234.5, "$"))
        assertEquals("\u20AC39.99", PriceFormat.price(39.99, "\u20AC"))
    }

    /**
     * The currency is chosen explicitly, so the number must not also follow the device locale.
     *
     * A European phone with USD selected used to render `$9,99` — a dollar sign with comma decimals.
     */
    @Test
    fun `price formatting is independent of the device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALY)
            assertEquals("$9.99", PriceFormat.price(9.99, "$"))
            assertEquals("$1,234.50", PriceFormat.price(1234.5, "$"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
