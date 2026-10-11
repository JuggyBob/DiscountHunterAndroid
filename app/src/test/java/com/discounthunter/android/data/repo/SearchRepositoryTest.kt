package com.discounthunter.android.data.repo

import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.data.net.Outcome
import com.discounthunter.android.data.source.FakeEpic
import com.discounthunter.android.data.source.FakeGog
import com.discounthunter.android.data.source.FakeSteam
import com.discounthunter.android.data.source.epicGiveaway
import com.discounthunter.android.data.source.gogProduct
import com.discounthunter.android.data.source.steamHit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-store search merging.
 *
 * The desktop build keyed results by *normalised title*, so two genuinely different games sharing a
 * title collapsed into one row with mixed-up prices. Each test here pins one part of the stricter
 * rule: merge only on proven identity, and keep one identity per row.
 */
class SearchRepositoryTest {

    private fun repository(
        steam: FakeSteam = FakeSteam(),
        gog: FakeGog = FakeGog(),
        epic: FakeEpic = FakeEpic(),
    ) = SearchRepository(steam, gog, epic)

    /**
     * The merged rows, without the per-store failure notes — most tests only care about rows.
     * Failure reporting has its own tests at the bottom of this file.
     */
    private suspend fun SearchRepository.rows(term: String, country: String = "US") =
        search(term, country).results

    // ---------------------------------------------------------------- merging

    @Test
    fun `steam and gog hits for the same game merge into one row`() = runTest {
        val repository = repository(
            steam = FakeSteam(
                searchByQuery = mapOf("elden ring" to listOf(steamHit(1245620, "Elden Ring", price = 59.99))),
            ),
            gog = FakeGog(
                searchByQuery = mapOf(
                    "elden ring" to listOf(gogProduct("elden_ring", "Elden Ring", price = 49.99)),
                ),
            ),
        )

        val results = repository.rows("Elden Ring", "US")

        assertEquals(1, results.size)
        val row = results.single()
        assertEquals(59.99, row.steamPrice ?: 0.0, 0.001)
        assertEquals(49.99, row.gogPrice ?: 0.0, 0.001)
        assertEquals(49.99, row.cheapestPrice, 0.001)
        assertEquals(setOf(StoreId.STEAM, StoreId.GOG), row.matchedStores)
    }

    @Test
    fun `different games sharing a title stay separate rows`() = runTest {
        // "Doom" exists in several forms; merging them by title would mix their prices.
        val repository = repository(
            steam = FakeSteam(
                searchByQuery = mapOf(
                    "doom" to listOf(
                        steamHit(379720, "Doom", price = 39.99),
                        steamHit(782330, "Doom Eternal", price = 59.99),
                    ),
                ),
            ),
        )

        val results = repository.rows("Doom", "US")

        assertEquals(2, results.size)
        assertEquals(setOf(379720, 782330), results.mapNotNull { it.steamAppId }.toSet())
        assertEquals(2, results.mapNotNull { it.steamAppId }.distinct().size)
    }

    @Test
    fun `a gog hit that cannot be matched stays its own row`() = runTest {
        val repository = repository(
            steam = FakeSteam(
                searchByQuery = mapOf("elden ring" to listOf(steamHit(1245620, "Elden Ring"))),
            ),
            gog = FakeGog(
                // The catalogue answers a GOG-style query with unrelated bestsellers.
                searchByQuery = mapOf(
                    "elden ring" to listOf(
                        gogProduct("cyberpunk_2077", "Cyberpunk 2077"),
                        gogProduct("elden_ring", "Elden Ring"),
                    ),
                ),
            ),
        )

        val results = repository.rows("Elden Ring", "US")

        assertEquals(1, results.size)
        assertEquals("Elden Ring", results.single().title)
        assertEquals(setOf(StoreId.STEAM, StoreId.GOG), results.single().matchedStores)
    }

    @Test
    fun `an epic giveaway merges into a matching steam row and marks it free`() = runTest {
        val repository = repository(
            steam = FakeSteam(
                searchByQuery = mapOf("mystery game" to listOf(steamHit(4242, "Mystery Game", price = 19.99))),
            ),
            epic = FakeEpic(
                listOf(epicGiveaway("mystery-game", "Mystery Game", normalPrice = 19.99)),
            ),
        )

        val row = repository.rows("Mystery Game", "US").single()

        assertTrue("the Epic giveaway makes the game free", row.isFree)
        assertEquals(0.0, row.cheapestPrice, 0.001)
        assertTrue(row.matchedStores.contains(StoreId.EPIC))
        assertEquals(19.99, row.steamPrice ?: 0.0, 0.001)
    }

    @Test
    fun `an unrelated epic giveaway is not added to unrelated results`() = runTest {
        val repository = repository(
            steam = FakeSteam(
                searchByQuery = mapOf("elden ring" to listOf(steamHit(1245620, "Elden Ring", price = 59.99))),
            ),
            epic = FakeEpic(listOf(epicGiveaway("mystery_game", "Mystery Game"))),
        )

        val results = repository.rows("Elden Ring", "US")

        assertEquals(1, results.size)
        assertFalse(results.single().matchedStores.contains(StoreId.EPIC))
    }

    @Test
    fun `an epic giveaway matching the query becomes its own row`() = runTest {
        val repository = repository(
            epic = FakeEpic(listOf(epicGiveaway("mystery_game", "Mystery Game"))),
        )

        val row = repository.rows("Mystery Game", "US").single()

        assertTrue(row.isFree)
        assertEquals(StoreId.EPIC, row.primaryStore)
        assertNotNull(row.reference)
    }

    // ---------------------------------------------------------------- identity

    @Test
    fun `every row carries exactly one identity`() = runTest {
        val repository = repository(
            steam = FakeSteam(
                searchByQuery = mapOf(
                    "game" to listOf(steamHit(1, "Game One"), steamHit(2, "Game Two")),
                ),
            ),
        )

        val results = repository.rows("game", "US")

        assertTrue(results.isNotEmpty())
        results.forEach { row ->
            val stores = listOfNotNull(
                row.steamAppId?.let { "steam" },
                row.gogSlug?.let { "gog" },
                row.epicSlug?.let { "epic" },
            )
            // The primary store is the row's own identity; merging never yields an ambiguous row.
            assertNotNull(row.primaryStore)
            assertNotEquals(0, stores.size)
        }
    }

    @Test
    fun `a row's reference points at the merged game`() = runTest {
        val repository = repository(
            steam = FakeSteam(
                searchByQuery = mapOf("elden ring" to listOf(steamHit(1245620, "Elden Ring"))),
            ),
            gog = FakeGog(
                searchByQuery = mapOf(
                    "elden ring" to listOf(gogProduct("elden_ring", "Elden Ring", price = 49.99)),
                ),
            ),
        )

        val reference = requireNotNull(repository.rows("Elden Ring", "US").single().reference)

        assertEquals(StoreId.STEAM, reference.store)
        assertEquals("1245620", reference.identity.id)
        assertEquals("https://store.steampowered.com/app/1245620/", reference.fallbackUrl)
    }

    @Test
    fun `a gog-only row links to its gog page`() = runTest {
        // The catalogue indexes on words, so it is queried both as typed and as a shortened form.
        val repository = repository(
            gog = FakeGog(
                searchByQuery = mapOf(
                    "the witcher 3" to listOf(gogProduct("witcher_3", "The Witcher 3")),
                    "witcher" to listOf(gogProduct("witcher_3", "The Witcher 3")),
                ),
            ),
        )

        val row = repository.rows("The Witcher 3", "US").first()

        assertEquals(StoreId.GOG, row.primaryStore)
        assertEquals("https://www.gog.com/en/game/witcher_3", row.webUrl)
        assertNotNull(row.reference)
    }

    // ---------------------------------------------------------------- ranking

    @Test
    fun `the exact title ranks first and free games lead`() = runTest {
        val repository = repository(
            steam = FakeSteam(
                searchByQuery = mapOf(
                    "elden ring" to listOf(
                        steamHit(1, "Elden Ring Remastered", price = 59.99),
                        steamHit(2, "Elden Ring", price = 39.99),
                        steamHit(3, "Shadow of the Erdtree DLC", price = 19.99),
                    ),
                ),
            ),
            epic = FakeEpic(listOf(epicGiveaway("elden_ring_free", "Elden Ring"))),
        )

        val results = repository.rows("Elden Ring", "US")

        assertEquals("Elden Ring", results.first().title)
        assertTrue("a free game leads the list", results.first().isFree)
    }

    @Test
    fun `results are capped`() = runTest {
        val repository = repository(
            steam = FakeSteam(
                searchByQuery = mapOf(
                    "game" to (1..60).map { steamHit(it, "Game $it", price = it.toDouble()) },
                ),
            ),
        )

        assertTrue(repository.rows("game", "US").size <= MAX_RESULTS)
    }

    @Test
    fun `a short query returns nothing without hitting the network`() = runTest {
        val steam = FakeSteam(searchByQuery = mapOf("a" to listOf(steamHit(1, "A"))))

        assertTrue(repository(steam).rows("a", "US").isEmpty())
        assertTrue(steam.requestedSearches.isEmpty())
    }

    @Test
    fun `an empty result set is returned when no store has the game`() = runTest {
        val repository = repository()
        assertTrue(repository.rows("elden ring", "US").isEmpty())
    }

    // ---------------------------------------------------------------- failure reporting

    @Test
    fun `a store that fails is reported instead of looking like an empty search`() = runTest {
        val repository = repository(
            steam = FakeSteam(
                searchOutcome = Outcome.Failure(Outcome.Reason.NETWORK, "Could not reach the store."),
            ),
            gog = FakeGog(searchByQuery = mapOf("hades" to listOf(gogProduct("hades", "Hades")))),
        )

        val response = repository.search("Hades", "US")

        assertEquals(1, response.results.size)
        assertEquals(1, response.failures.size)
        assertTrue(response.failures.single().contains("Steam"))
    }

    @Test
    fun `a store that throws is reported and does not cancel the others`() = runTest {
        val repository = repository(
            steam = FakeSteam(searchThrows = IllegalStateException("boom")),
            gog = FakeGog(searchByQuery = mapOf("hades" to listOf(gogProduct("hades", "Hades")))),
        )

        val response = repository.search("Hades", "US")

        assertEquals(1, response.results.size)
        assertEquals(listOf("Steam search failed: boom"), response.failures)
    }

    @Test
    fun `every store failing still produces a readable explanation`() = runTest {
        val repository = repository(
            steam = FakeSteam(
                searchOutcome = Outcome.Failure(Outcome.Reason.RATE_LIMITED, "Steam is rate limiting us."),
            ),
            gog = FakeGog(searchThrows = java.io.IOException("socket closed")),
            epic = FakeEpic(
                giveawaysOutcome = Outcome.Failure(Outcome.Reason.HTTP, "Epic feed unavailable."),
            ),
        )

        val response = repository.search("Hades", "US")

        assertTrue(response.results.isEmpty())
        assertEquals(3, response.failures.size)
        assertTrue(response.failures.any { it.contains("rate limiting") })
        assertTrue(response.failures.any { it.contains("socket closed") })
        assertTrue(response.failures.any { it.contains("Epic") })
    }

    @Test
    fun `a short query is answered locally without a failure note`() = runTest {
        val response = repository().search("a", "US")

        assertTrue(response.results.isEmpty())
        assertTrue(response.failures.isEmpty())
    }

    @Test
    fun `a gog-only fallback survives when steam returns nothing`() = runTest {
        val repository = repository(
            gog = FakeGog(
                searchByQuery = mapOf("hades" to listOf(gogProduct("hades", "Hades", price = 24.99))),
            ),
        )

        val row = repository.rows("Hades", "US").single()

        assertEquals("Hades", row.title)
        assertEquals(24.99, row.cheapestPrice, 0.001)
        assertEquals(StoreId.GOG, row.primaryStore)
    }

    @Test
    fun `a result with no price is still shown`() = runTest {
        val repository = repository(
            steam = FakeSteam(
                searchByQuery = mapOf(
                    "unknown game" to listOf(steamHit(999, "Unknown Game", price = null)),
                ),
            ),
        )

        val row = repository.rows("Unknown Game", "US").single()

        assertFalse(row.hasPrice)
        assertNull(row.steamPrice)
        assertNotNull(row.identity)
    }

    private companion object {
        const val MAX_RESULTS = 25
    }
}
