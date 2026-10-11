package com.discounthunter.android.data.wishlist

import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.StoreId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which deal a wishlist row advertises.
 *
 * The reported bug was that every saved game read "Best: FREE" while the Deals tab showed a real
 * price for it. The cause is that the ranking used to consider *every* offer for the game, so a
 * free giveaway anywhere beat a discounted price on the store the user actually saved — while the
 * row's badge still named the saved store, so the row contradicted itself.
 */
class BestLiveDealTest {

    private fun deal(
        store: StoreId,
        id: String,
        salePrice: Double,
        savings: Double = 20.0,
    ) = Deal(
        id = "$store:$id",
        title = "Elden Ring",
        thumbUrl = "",
        salePrice = salePrice,
        normalPrice = 59.99,
        savingsPercent = savings,
        store = store,
        identity = GameIdentity(store, id),
        storeUrl = "",
    )

    @Test
    fun `the saved store wins over a free giveaway elsewhere`() {
        val best = bestLiveDealFor(
            savedStore = StoreId.GOG,
            candidates = listOf(
                deal(StoreId.GOG, "elden_ring", salePrice = 11.99, savings = 80.0),
                deal(StoreId.EPIC, "elden-ring", salePrice = 0.0, savings = 100.0),
            ),
        )

        assertEquals(StoreId.GOG, best?.store)
        assertEquals(11.99, best!!.salePrice, 0.001)
    }

    @Test
    fun `another store is used only when the saved store has nothing`() {
        val best = bestLiveDealFor(
            savedStore = StoreId.GOG,
            candidates = listOf(
                deal(StoreId.EPIC, "elden-ring", salePrice = 0.0, savings = 100.0),
            ),
        )

        assertEquals(StoreId.EPIC, best?.store)
    }

    @Test
    fun `a free offer on the saved store is still the best`() {
        val best = bestLiveDealFor(
            savedStore = StoreId.EPIC,
            candidates = listOf(
                deal(StoreId.EPIC, "elden-ring", salePrice = 0.0, savings = 100.0),
                deal(StoreId.GOG, "elden_ring", salePrice = 1.99, savings = 96.0),
            ),
        )

        assertEquals(StoreId.EPIC, best?.store)
        assertTrue(best!!.isFree)
    }

    @Test
    fun `the deepest discount on the saved store wins`() {
        val best = bestLiveDealFor(
            savedStore = StoreId.STEAM,
            candidates = listOf(
                deal(StoreId.STEAM, "1245620", salePrice = 47.99, savings = 20.0),
                deal(StoreId.STEAM, "1245620", salePrice = 11.99, savings = 80.0),
            ),
        )

        assertEquals(11.99, best!!.salePrice, 0.001)
    }

    @Test
    fun `no candidates yields nothing rather than a wrong game`() {
        assertNull(bestLiveDealFor(savedStore = StoreId.STEAM, candidates = emptyList()))
    }

    @Test
    fun `equal discounts fall back to the lower price`() {
        val best = bestLiveDealFor(
            savedStore = StoreId.STEAM,
            candidates = listOf(
                deal(StoreId.STEAM, "a", salePrice = 9.99, savings = 50.0),
                deal(StoreId.STEAM, "b", salePrice = 4.99, savings = 50.0),
            ),
        )

        assertEquals(4.99, best!!.salePrice, 0.001)
    }
}