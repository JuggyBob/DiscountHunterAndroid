package com.discounthunter.android.data.cache

import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.StoreId
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

/**
 * Snapshots are scoped by region.
 *
 * ## The bug this pins down
 *
 * Steam prices and availability are per-region, but the snapshot file was named after the day
 * alone. Switching the app's currency from USD to EUR therefore kept serving the US figures — and
 * because `DealsViewModel` never reloaded on a currency change either, not even a manual refresh
 * would replace them. The user saw dollar amounts with a euro symbol.
 *
 * A snapshot is only useful as history if it was recorded in the region the user is looking at, so
 * the region is part of the path rather than part of the file name.
 */
class SnapshotRegionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(dir: java.io.File) =
        SnapshotStore(dir, UnconfinedTestDispatcher())

    private fun deal(id: String, price: Double) = Deal(
        id = "steam_$id",
        title = "Game $id",
        thumbUrl = "",
        salePrice = price,
        normalPrice = price * 2,
        savingsPercent = 50.0,
        store = StoreId.STEAM,
        identity = GameIdentity(StoreId.STEAM, id),
        storeUrl = "",
    )

    @Test
    fun `a snapshot saved in one region is not served for another`() = runTest {
        val dir = folder.root
        val date = LocalDate.of(2024, 5, 1)
        store(dir).save(date, listOf(deal("1", 9.99)), "US")

        assertNull(store(dir).load(date, "GB"))
    }

    @Test
    fun `each region keeps its own figures`() = runTest {
        val dir = folder.root
        val date = LocalDate.of(2024, 5, 1)
        store(dir).save(date, listOf(deal("1", 9.99)), "US")
        store(dir).save(date, listOf(deal("1", 7.49)), "GB")

        assertEquals(9.99, store(dir).load(date, "US")!!.single().salePrice, 0.001)
        assertEquals(7.49, store(dir).load(date, "GB")!!.single().salePrice, 0.001)
    }

    @Test
    fun `the region folder is named from the country code`() = runTest {
        val dir = folder.root
        store(dir).save(LocalDate.of(2024, 5, 1), listOf(deal("1", 1.0)), "de")

        assertTrue(java.io.File(dir, "DE").isDirectory)
    }

    @Test
    fun `a blank region falls back to the default rather than writing to an empty folder`() = runTest {
        val dir = folder.root
        val date = LocalDate.of(2024, 5, 1)
        store(dir).save(date, listOf(deal("1", 3.0)), "")

        assertEquals(1, store(dir).load(date, "US")?.size)
    }

    @Test
    fun `price history is region scoped too`() = runTest {
        val dir = folder.root
        val date = LocalDate.of(2024, 5, 1)
        val identity = GameIdentity(StoreId.STEAM, "1")
        store(dir).save(date, listOf(deal("1", 5.0)), "US")

        assertEquals(1, store(dir).priceHistory(identity, "US").size)
        assertEquals(0, store(dir).priceHistory(identity, "DE").size)
    }

    @Test
    fun `clearing removes every region`() = runTest {
        val dir = folder.root
        val date = LocalDate.of(2024, 5, 1)
        val snapshots = store(dir)
        snapshots.save(date, listOf(deal("1", 5.0)), "US")
        snapshots.save(date, listOf(deal("1", 6.0)), "GB")

        snapshots.clear()

        assertNull(snapshots.load(date, "US"))
        assertNull(snapshots.load(date, "GB"))
    }
}