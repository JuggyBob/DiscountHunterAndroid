package com.discounthunter.android.alerts

import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.WishlistEntry
import com.discounthunter.android.data.wishlist.representsSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What gets announced, and what does not.
 *
 * ## The behaviour this pins down
 *
 * The check runs on a timer, so the same deals are seen over and over. The rule is that only a
 * **change** is worth telling someone about:
 *
 *  * a wishlisted game that has *started* being discounted;
 *  * a game that has *become* free.
 *
 * A game that is still discounted next hour is not news, and announcing it again is how a user
 * learns to swipe the notification away without reading it.
 *
 * ## Why the keys come from the model
 *
 * The wishlist entries here are built with [WishlistEntry.fromDeal], so their keys are exactly
 * the ones production produces. Writing the keys out by hand — `"s1:1"` — would pass for reasons
 * unrelated to the code, and would keep passing even after the key format changed. That is the
 * worst property a test can have.
 */
class SaleDetectorTest {

    private fun deal(
        id: String,
        title: String,
        price: Double = 9.99,
        original: Double = 19.99,
        percent: Double = 50.0,
        store: StoreId = StoreId.STEAM,
        free: Boolean = false,
        /**
         * The currency the store quoted it in.
         *
         * Blank means the store re-quotes for the user's region. GOG never does — its catalogue
         * answers in dollars whatever is asked for — so a GOG deal carries `"USD"` in production,
         * set by the service. This mirrors that, so the test exercises the same shape the app
         * really produces.
         */
        currencyCode: String = "",
    ) = Deal(
        id = "${store.name.lowercase()}_$id",
        title = title,
        thumbUrl = "",
        salePrice = if (free) 0.0 else price,
        normalPrice = if (free) 0.0 else original,
        savingsPercent = if (free) 100.0 else percent,
        store = store,
        identity = GameIdentity(store, id),
        storeUrl = "",
        currencyCode = currencyCode,
    )

    /** A wishlist entry for the same game, keyed exactly as production would key it. */
    private fun saved(deal: Deal): WishlistEntry = WishlistEntry.fromDeal(deal, 0L)

    /** The production matching rule, used unchanged. */
    private val matches: (WishlistEntry, Deal) -> Boolean = { entry, deal ->
        representsSame(entry, deal.wishlistKey, deal.titleKey)
    }

    private fun detect(
        wishlist: List<WishlistEntry>,
        deals: List<Deal>,
        state: WatchState,
    ) = SaleDetector.detect(wishlist, deals, state, matches)

    // ------------------------------------------------------------------ first run

    @Test
    fun `the first run announces nothing`() {
        // There is no previous observation, so nothing can be shown to have *become* anything.
        val elden = deal("1", "Elden Ring")
        val result = detect(
            wishlist = listOf(saved(elden)),
            deals = listOf(elden),
            state = WatchState(),
        )

        assertTrue(result.sales.isEmpty())
        assertTrue(result.freeGames.isEmpty())
    }

    @Test
    fun `the first run records what it saw`() {
        val elden = deal("1", "Elden Ring")
        val result = detect(listOf(saved(elden)), listOf(elden), WatchState())

        assertTrue(result.nextState.initialised)
        assertEquals(1, result.nextState.announcedSales.size)
    }

    @Test
    fun `the first run does not announce the free games already on offer`() {
        // Otherwise enabling the feature produces one notification per free game currently
        // available, which is how a feature like this gets switched off before it is used.
        val result = detect(
            wishlist = emptyList(),
            deals = listOf(deal("1", "Free One", free = true), deal("2", "Free Two", free = true)),
            state = WatchState(),
        )

        assertTrue(result.freeGames.isEmpty())
        assertEquals(2, result.nextState.seenFreeGames.size)
    }

    // ------------------------------------------------------------------ sales

    @Test
    fun `a game that starts being discounted is announced`() {
        val notYet = deal("1", "Elden Ring", percent = 0.0)
        val entry = saved(notYet)

        val before = detect(listOf(entry), listOf(notYet), WatchState())
        val after = detect(
            wishlist = listOf(entry),
            deals = listOf(deal("1", "Elden Ring", percent = 50.0)),
            state = before.nextState,
        )

        assertEquals(1, after.sales.size)
        assertEquals("Elden Ring", after.sales.first().title)
        assertEquals(50.0, after.sales.first().discountPercent, 0.001)
        assertEquals(9.99, after.sales.first().price, 0.001)
    }

    @Test
    fun `the same sale is not announced twice`() {
        val entry = saved(deal("1", "Elden Ring"))
        val deals = listOf(deal("1", "Elden Ring", percent = 50.0))

        val first = detect(listOf(entry), deals, WatchState())
        val second = detect(listOf(entry), deals, first.nextState)

        assertTrue(second.sales.isEmpty())
    }

    @Test
    fun `a deeper discount is announced again`() {
        val entry = saved(deal("1", "Elden Ring"))

        val first = detect(
            listOf(entry),
            listOf(deal("1", "Elden Ring", price = 9.99, percent = 50.0)),
            WatchState(),
        )
        val second = detect(
            listOf(entry),
            listOf(deal("1", "Elden Ring", price = 4.99, percent = 75.0)),
            first.nextState,
        )

        assertEquals(1, second.sales.size)
        assertEquals(75.0, second.sales.first().discountPercent, 0.001)
    }

    @Test
    fun `a sale that ended and starts again is announced again`() {
        val entry = saved(deal("1", "Elden Ring"))

        val onSale = detect(
            listOf(entry),
            listOf(deal("1", "Elden Ring", percent = 50.0)),
            WatchState(),
        )
        val offSale = detect(
            listOf(entry),
            listOf(deal("1", "Elden Ring", percent = 0.0)),
            onSale.nextState,
        )
        val backOnSale = detect(
            listOf(entry),
            listOf(deal("1", "Elden Ring", percent = 50.0)),
            offSale.nextState,
        )

        assertEquals(1, backOnSale.sales.size)
    }

    @Test
    fun `a game missing from the feed is not treated as no longer on sale`() {
        // The feed only holds the top few dozen discounts per store. A game dropping out of it
        // says nothing about whether it is still discounted, so the record has to survive.
        val entry = saved(deal("1", "Elden Ring"))
        val onSale = detect(
            listOf(entry),
            listOf(deal("1", "Elden Ring", percent = 50.0)),
            WatchState(),
        )
        val absent = detect(listOf(entry), emptyList(), onSale.nextState)

        assertEquals(1, absent.nextState.announcedSales.size)
    }

    @Test
    fun `a game that disappears and comes back at the same price is not announced again`() {
        val entry = saved(deal("1", "Elden Ring"))
        val onSale = detect(
            listOf(entry),
            listOf(deal("1", "Elden Ring", percent = 50.0)),
            WatchState(),
        )
        val absent = detect(listOf(entry), emptyList(), onSale.nextState)
        val back = detect(
            listOf(entry),
            listOf(deal("1", "Elden Ring", percent = 50.0)),
            absent.nextState,
        )

        assertTrue(back.sales.isEmpty())
    }

    @Test
    fun `a game on the wishlist that is not discounted is not announced`() {
        val entry = saved(deal("1", "Elden Ring"))
        val result = detect(
            listOf(entry),
            listOf(deal("1", "Elden Ring", percent = 0.0)),
            WatchState(initialised = true),
        )

        assertTrue(result.sales.isEmpty())
    }

    @Test
    fun `a game that is not on the wishlist is not announced as a sale`() {
        val entry = saved(deal("1", "Elden Ring"))
        val result = detect(
            listOf(entry),
            listOf(deal("2", "Some Other Game", percent = 90.0)),
            WatchState(initialised = true),
        )

        assertTrue(result.sales.isEmpty())
    }

    @Test
    fun `the sale carries the price and the discount`() {
        // The user asked to be told both, and a notification without them is useless.
        val entry = saved(deal("1", "Elden Ring"))
        val before = detect(
            listOf(entry),
            listOf(deal("1", "Elden Ring", percent = 0.0)),
            WatchState(),
        )
        val after = detect(
            listOf(entry),
            listOf(deal("1", "Elden Ring", price = 24.99, original = 99.99, percent = 75.0)),
            before.nextState,
        )

        val sale = after.sales.first()
        assertEquals(24.99, sale.price, 0.001)
        assertEquals(99.99, sale.originalPrice, 0.001)
        assertEquals(75.0, sale.discountPercent, 0.001)
        assertTrue(sale.hasOriginalPrice)
    }

    @Test
    fun `the sale keeps the currency the store quoted it in`() {
        val gog = deal("some-slug", "Some Long Game Name", store = StoreId.GOG, currencyCode = "USD")
        val entry = saved(gog)
        val before = detect(
            listOf(entry),
            listOf(
                deal("some-slug", "Some Long Game Name", store = StoreId.GOG, percent = 0.0, currencyCode = "USD"),
            ),
            WatchState(),
        )
        val after = detect(
            listOf(entry),
            listOf(
                deal("some-slug", "Some Long Game Name", store = StoreId.GOG, percent = 50.0, currencyCode = "USD"),
            ),
            before.nextState,
        )

        // A GOG price is in dollars whatever region is asked for, and the notification has to
        // know that or it will present it in the wrong currency.
        assertEquals("USD", after.sales.first().currencyCode)
    }

    @Test
    fun `a free game on the wishlist is announced once, not twice`() {
        // Announcing it as a free game *and* as "100% off" tells the user about the same thing
        // twice, and the second version is the less useful one.
        val free = deal("1", "Agent A", free = true)
        val entry = saved(free)

        val result = detect(listOf(entry), listOf(free), WatchState(initialised = true))

        assertTrue(result.sales.isEmpty())
        assertEquals(1, result.freeGames.size)
    }

    @Test
    fun `a wishlisted free game that becomes a paid discount is announced as a sale`() {
        val free = deal("1", "Agent A", free = true)
        val entry = saved(free)

        val wasFree = detect(listOf(entry), listOf(free), WatchState(initialised = true))
        val nowDiscounted = detect(
            listOf(entry),
            listOf(deal("1", "Agent A", price = 9.99, percent = 50.0)),
            wasFree.nextState,
        )

        assertEquals(1, nowDiscounted.sales.size)
    }

    // ------------------------------------------------------------------ free games

    @Test
    fun `a new free game is announced`() {
        val before = detect(emptyList(), emptyList(), WatchState(initialised = true))
        val after = detect(
            wishlist = emptyList(),
            deals = listOf(deal("1", "Free One", free = true)),
            state = before.nextState,
        )

        assertEquals(1, after.freeGames.size)
        assertEquals("Free One", after.freeGames.first().title)
    }

    @Test
    fun `a free game that stays free is not announced again`() {
        val before = detect(emptyList(), emptyList(), WatchState(initialised = true))
        val first = detect(
            wishlist = emptyList(),
            deals = listOf(deal("1", "Free One", free = true)),
            state = before.nextState,
        )
        val second = detect(
            wishlist = emptyList(),
            deals = listOf(deal("1", "Free One", free = true)),
            state = first.nextState,
        )

        assertTrue(second.freeGames.isEmpty())
    }

    @Test
    fun `a free game is announced whether or not it is on the wishlist`() {
        // A free copy of something the user has not saved is exactly the thing they would want
        // to hear about.
        val before = detect(emptyList(), emptyList(), WatchState(initialised = true))
        val after = detect(
            wishlist = emptyList(),
            deals = listOf(deal("1", "Free One", free = true)),
            state = before.nextState,
        )

        assertEquals(1, after.freeGames.size)
    }

    @Test
    fun `a discounted game is not announced as free`() {
        val before = detect(emptyList(), emptyList(), WatchState(initialised = true))
        val after = detect(
            wishlist = emptyList(),
            deals = listOf(deal("1", "On Sale", percent = 50.0)),
            state = before.nextState,
        )

        assertTrue(after.freeGames.isEmpty())
    }

    @Test
    fun `the free game memory is bounded`() {
        val before = detect(emptyList(), emptyList(), WatchState(initialised = true))
        val many = (1..(WatchState.MAX_SEEN_FREE_GAMES + 50)).map {
            deal(it.toString(), "Free $it", free = true)
        }
        val after = detect(emptyList(), many, before.nextState)

        assertEquals(WatchState.MAX_SEEN_FREE_GAMES, after.nextState.seenFreeGames.size)
    }

    // ------------------------------------------------------------------ idempotence

    @Test
    fun `running the same check twice announces nothing the second time`() {
        // The schedule has two independent triggers, and they can both fire. The outcome must not
        // depend on which one won the race.
        val elden = deal("1", "Elden Ring", percent = 50.0)
        val wishlist = listOf(saved(elden))
        val deals = listOf(elden, deal("2", "Free One", free = true))

        val first = detect(wishlist, deals, WatchState())
        val second = detect(wishlist, deals, first.nextState)

        assertTrue(second.sales.isEmpty())
        assertTrue(second.freeGames.isEmpty())
        assertFalse(second.hasAnythingToAnnounce)
    }
}
