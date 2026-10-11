package com.discounthunter.android.data.repo

import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.ScheduleStatus
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.data.cache.SnapshotStore
import com.discounthunter.android.data.net.Outcome
import com.discounthunter.android.data.source.EpicSource
import com.discounthunter.android.data.source.FakeEpic
import com.discounthunter.android.data.source.FakeGog
import com.discounthunter.android.data.source.FakeSteam
import com.discounthunter.android.data.source.GogSource
import com.discounthunter.android.data.source.SteamSource
import com.discounthunter.android.data.source.gogProduct
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The merged front-page feed.
 *
 * The behaviour that matters most here is partial failure: one store being down must degrade the
 * feed with a warning, never empty it. The desktop build fetched the three stores and merged the
 * results without isolating failures, so one unreachable store could take the whole page down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DealRepositoryTest {

    private lateinit var snapshots: SnapshotStore

    private fun repository(
        steam: SteamSource = FakeSteam(),
        gog: GogSource = FakeGog(),
        epic: EpicSource = FakeEpic(),
        directory: File = Files.createTempDirectory("snapshot-test").toFile(),
    ): Pair<DealRepository, SnapshotStore> {
        snapshots = SnapshotStore(directory, io = UnconfinedTestDispatcher())
        return DealRepository(steam, gog, epic, snapshots) to snapshots
    }

    private fun steamDeal(id: String, title: String = "Game $id", sale: Double = 10.0, savings: Double = 50.0) =
        Deal(
            id = id,
            title = title,
            thumbUrl = "https://cdn.steamstatic.com/$id.jpg",
            salePrice = sale,
            normalPrice = sale * 2,
            savingsPercent = savings,
            store = StoreId.STEAM,
            identity = com.discounthunter.android.core.model.GameIdentity.steam(id.removePrefix("steam_").toInt()),
            storeUrl = "https://store.steampowered.com/app/$id/",
        )

    // ---------------------------------------------------------------- merging

    @Test
    fun `free epic giveaways lead the feed`() = runTest {
        val (repository, _) = repository(
            steam = FakeSteam(deals = listOf(steamDeal("steam_1"))),
            gog = FakeGog(deals = listOf(gogProduct("gog1", "Gog Game"))),
            epic = FakeEpic(listOf(com.discounthunter.android.data.source.epicGiveaway("free_game", "Free Game"))),
        )

        val feed = repository.refreshFeed("US", 40, StoreId.MAJOR.toSet())

        assertEquals(3, feed.deals.size)
        assertEquals(StoreId.EPIC, feed.deals.first().store)
        assertTrue("a free giveaway must lead the feed", feed.deals.first().isFree)
    }

    @Test
    fun `gog deals carry their slug as identity`() = runTest {
        val (repository, _) = repository(
            gog = FakeGog(deals = listOf(gogProduct("elden_ring", "Elden Ring", price = 29.99))),
        )

        val deal = repository.refreshFeed("US", 40, setOf(StoreId.GOG)).deals.single()

        assertEquals("gog_elden_ring", deal.id)
        assertEquals("elden_ring", deal.identity?.id)
        assertEquals(29.99, deal.salePrice, 0.001)
    }

    @Test
    fun `deals are ordered free first then by deepest discount`() = runTest {
        val (repository, _) = repository(
            steam = FakeSteam(
                deals = listOf(
                    steamDeal("steam_1", sale = 30.0, savings = 10.0),
                    steamDeal("steam_2", sale = 5.0, savings = 80.0),
                    steamDeal("steam_3", sale = 0.0, savings = 100.0),
                ),
            ),
        )

        val order = repository.refreshFeed("US", 40, setOf(StoreId.STEAM)).deals.map { it.id }

        assertEquals(listOf("steam_3", "steam_2", "steam_1"), order)
    }

    @Test
    fun `duplicate deal ids are collapsed`() = runTest {
        // The desktop build keyed de-duplication on (store, title, rounded price), which merged two
        // different editions that happened to share a title and price.
        val (repository, _) = repository(
            steam = FakeSteam(
                deals = listOf(
                    steamDeal("steam_1", "Shared Title", sale = 10.0, savings = 50.0),
                    steamDeal("steam_1", "Shared Title", sale = 10.0, savings = 50.0),
                ),
            ),
        )

        assertEquals(1, repository.refreshFeed("US", 40, setOf(StoreId.STEAM)).deals.size)
    }

    @Test
    fun `two editions sharing a title are both kept`() = runTest {
        val (repository, _) = repository(
            steam = FakeSteam(
                deals = listOf(
                    steamDeal("steam_1", "Game Deluxe Edition", sale = 10.0, savings = 50.0),
                    steamDeal("steam_2", "Game Deluxe Edition", sale = 10.0, savings = 50.0),
                ),
            ),
        )

        assertEquals(2, repository.refreshFeed("US", 40, setOf(StoreId.STEAM)).deals.size)
    }

    @Test
    fun `disabled stores are never queried`() = runTest {
        val steam = FakeSteam(deals = listOf(steamDeal("steam_1")))
        val gog = FakeGog(deals = listOf(gogProduct("gog1", "Gog Game")))
        val (repository, _) = repository(steam, gog)

        val feed = repository.refreshFeed("US", 40, setOf(StoreId.STEAM))

        assertEquals(1, feed.deals.size)
        assertTrue(feed.deals.all { it.store == StoreId.STEAM })
        // Nothing from GOG at all, not even a warning about it.
        assertTrue(feed.warnings.isEmpty())
    }

    // ---------------------------------------------------------------- partial failure

    @Test
    fun `one failing store degrades the feed instead of emptying it`() = runTest {
        val (repository, _) = repository(
            steam = FailingSteam,
            gog = FakeGog(deals = listOf(gogProduct("gog1", "Gog Game"))),
        )

        val feed = repository.refreshFeed("US", 40, StoreId.MAJOR.toSet())

        assertTrue("the surviving store's deals must still show", feed.deals.isNotEmpty())
        assertTrue(
            "the failure must be reported to the user",
            feed.warnings.any { it.contains("Steam", ignoreCase = true) },
        )
    }

    @Test
    fun `every store failing produces an empty feed with warnings`() = runTest {
        val (repository, _) = repository(steam = FailingSteam, gog = FailingGog, epic = FailingEpic)

        val feed = repository.refreshFeed("US", 40, StoreId.MAJOR.toSet())

        assertTrue(feed.isEmpty)
        assertTrue(feed.warnings.isNotEmpty())
    }

    @Test
    fun `an empty feed with no warnings still explains itself`() = runTest {
        val (repository, _) = repository()

        val feed = repository.refreshFeed("US", 40, StoreId.MAJOR.toSet())

        assertTrue(feed.isEmpty)
        assertTrue(feed.warnings.any { it.contains("No deals", ignoreCase = true) })
    }

    // ---------------------------------------------------------------- schedule

    @Test
    fun `giveaway markers cover the whole giveaway window`() = runTest {
        // Giveaways are daily markers: every day a giveaway is running is marked, so the user can
        // see at a glance which dates have something free.
        val giveaway = com.discounthunter.android.data.source.epicGiveaway("game", "Game")
        val (repository, _) = repository(epic = FakeEpic(listOf(giveaway)))

        val days = repository.giveawayDays(
            from = LocalDate.of(2000, 1, 1),
            to = LocalDate.of(2100, 1, 1),
        )

        val first = Instant.ofEpochMilli(giveaway.startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val last = Instant.ofEpochMilli(giveaway.endMillis).atZone(ZoneId.systemDefault()).toLocalDate()

        assertTrue("the window spans several days", days.size >= 7)
        // Every marked day lies within the giveaway window, which is the whole point of the marker.
        assertTrue(days.all { !it.isBefore(first) && !it.isAfter(last) })
    }

    @Test
    fun `giveaway markers are clipped to the requested range`() = runTest {
        val giveaway = com.discounthunter.android.data.source.epicGiveaway("game", "Game")
        val (repository, _) = repository(epic = FakeEpic(listOf(giveaway)))

        val days = repository.giveawayDays(
            from = LocalDate.of(2000, 1, 1),
            to = LocalDate.of(2000, 1, 5),
        )

        // The giveaway is far outside the requested window, so nothing inside it is marked.
        assertTrue(days.isEmpty())
    }

    @Test
    fun `giveaways for a single date cover the window`() = runTest {
        val giveaway = com.discounthunter.android.data.source.epicGiveaway("game", "Game")
        val (repository, _) = repository(epic = FakeEpic(listOf(giveaway)))
        val insideWindow = Instant.ofEpochMilli(giveaway.startMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .plusDays(1)

        val deals = repository.giveawaysForDate(insideWindow)

        assertEquals(1, deals.size)
        assertEquals("epic_game", deals.single().id)
        assertTrue(deals.single().isFree)
    }

    @Test
    fun `a date outside every giveaway window yields nothing`() = runTest {
        val giveaway = com.discounthunter.android.data.source.epicGiveaway("game", "Game")
        val (repository, _) = repository(epic = FakeEpic(listOf(giveaway)))

        assertTrue(repository.giveawaysForDate(LocalDate.of(1990, 1, 1)).isEmpty())
    }

    @Test
    fun `an epic failure does not break the schedule`() = runTest {
        val (repository, _) = repository(epic = FailingEpic)

        assertTrue(repository.giveawayDays(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)).isEmpty())
    }

    @Test
    fun `the giveaway day range is bounded`() = runTest {
        // A feed entry with an absurd date must not produce thousands of markers.
        val bad = com.discounthunter.android.data.source.epicGiveaway("bad", "Bad")
            .copy(startMillis = 0L, endMillis = 4_000_000_000_000L)

        val (repository, _) = repository(epic = FakeEpic(listOf(bad)))
        val days = repository.giveawayDays(
            from = LocalDate.of(2024, 1, 1),
            to = LocalDate.of(2030, 1, 1),
        )

        assertTrue("the range must stay bounded", days.size <= 62)
    }

    // ---------------------------------------------------------------- snapshots

    @Test
    fun `a snapshot round-trips its deals`() = runTest {
        val directory = Files.createTempDirectory("snapshot-test").toFile()
        val (repository, store) = repository(directory = directory)
        val deals = listOf(
            steamDeal("steam_1", "Elden Ring"),
            steamDeal("steam_2", "Hollow Knight"),
        )
        val date = LocalDate.of(2024, 3, 15)

        store.save(date, deals)
        val restored = store.load(date)

        assertEquals(2, restored?.size)
        assertEquals(deals.map { it.id }, restored?.map { it.id })
        // Identity survives, so a snapshot row can still open the right detail page.
        assertEquals(deals.map { it.identity?.id }, restored?.map { it.identity?.id })
        assertEquals("Elden Ring", restored?.first()?.title)
    }

    @Test
    fun `a missing snapshot is null rather than empty`() = runTest {
        val (repository, _) = repository()
        assertEquals(null, repository.loadSnapshot(LocalDate.of(2020, 1, 1), "US"))
    }

    @Test
    fun `an empty snapshot is not written`() = runTest {
        val (repository, store) = repository()
        store.save(LocalDate.of(2024, 1, 1), emptyList())
        assertEquals(null, store.load(LocalDate.of(2024, 1, 1)))
    }

    @Test
    fun `a corrupt snapshot is treated as absent`() = runTest {
        val directory = Files.createTempDirectory("snapshot-test").toFile()
        val (repository, store) = repository(directory = directory)
        store.save(LocalDate.of(2024, 1, 1), listOf(steamDeal("steam_1")))

        // Corrupt the file, as an interrupted write would.
        snapshotFile(directory, LocalDate.of(2024, 1, 1)).writeText("not json")

        assertEquals(null, store.load(LocalDate.of(2024, 1, 1)))
    }

    @Test
    fun `a snapshot row with an unknown store is skipped`() = runTest {
        val directory = Files.createTempDirectory("snapshot-test").toFile()
        val (repository, store) = repository(directory = directory)
        store.save(LocalDate.of(2024, 1, 1), listOf(steamDeal("steam_1")))

        // Append an entry naming a store that does not exist.
        val file = snapshotFile(directory, LocalDate.of(2024, 1, 1))
        val existing = file.readText().removeSuffix("]")
        file.writeText("$existing,{\"id\":\"x\",\"store\":999,\"title\":\"Unknown Store\"}]")

        val restored = store.load(LocalDate.of(2024, 1, 1))
        assertEquals("the valid rows still load", 1, restored?.size)
        assertFalse(restored!!.any { it.title == "Unknown Store" })
    }

    /** The snapshot file for [date] in the default region, which is now a folder per region. */
    private fun snapshotFile(directory: java.io.File, date: java.time.LocalDate): java.io.File {
        val name = "${date}.json"
        return File(directory, "US").listFiles()?.first { it.name == name }
            ?: error("no snapshot for $date in ${directory.absolutePath}")
    }

    // ---------------------------------------------------------------- failing sources

    private object FailingEpic : EpicSource {
        override suspend fun fetchFreeGames(country: String): Outcome<Nothing> =
            Outcome.Failure(Outcome.Reason.NETWORK, "Epic is unreachable")

        override fun pageUrl(identity: com.discounthunter.android.core.model.GameIdentity) = ""
    }

    private object FailingSteam : com.discounthunter.android.data.source.SteamSource {
        override suspend fun fetchDeals(limit: Int, country: String) =
            Outcome.Failure(Outcome.Reason.NETWORK, "Steam is unreachable")

        override suspend fun search(term: String, country: String) =
            Outcome.Failure(Outcome.Reason.NETWORK, "Steam is unreachable")

        override suspend fun fetchAppDetails(
            identity: com.discounthunter.android.core.model.GameIdentity,
            country: String,
        ) = Outcome.Failure(Outcome.Reason.NETWORK, "Steam is unreachable")

        override suspend fun fetchPageDetails(
            identity: com.discounthunter.android.core.model.GameIdentity,
        ) = com.discounthunter.android.data.steam.SteamPageDetails()

        override fun storeUrl(identity: com.discounthunter.android.core.model.GameIdentity) = ""
    }

    private object FailingGog : com.discounthunter.android.data.source.GogSource {
        override suspend fun fetchDeals(limit: Int) =
            Outcome.Failure(Outcome.Reason.NETWORK, "GOG is unreachable")

        override suspend fun search(term: String, limit: Int) =
            Outcome.Failure(Outcome.Reason.NETWORK, "GOG is unreachable")

        override suspend fun probePageTitle(slug: String) = ""
        override suspend fun fetchGameDetails(identity: com.discounthunter.android.core.model.GameIdentity) =
            com.discounthunter.android.data.gog.GogGameDetails()

        override fun pageUrl(identity: com.discounthunter.android.core.model.GameIdentity) = ""
        override fun toDeal(product: com.discounthunter.android.data.gog.GogProduct): Deal =
            throw UnsupportedOperationException()
    }
}
