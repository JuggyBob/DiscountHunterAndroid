package com.discounthunter.android.data.repo

import com.discounthunter.android.core.model.GameDetail
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.GameReference
import com.discounthunter.android.core.model.Screenshot
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.data.gog.GogGameDetails
import com.discounthunter.android.data.source.FakeEpic
import com.discounthunter.android.data.source.FakeGog
import com.discounthunter.android.data.source.FakeSteam
import com.discounthunter.android.data.source.gogProduct
import com.discounthunter.android.data.source.gogScreenshot
import com.discounthunter.android.data.source.steamDetails
import com.discounthunter.android.data.source.steamHit
import com.discounthunter.android.data.source.steamScreenshot
import com.discounthunter.android.data.source.steamTrailer
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media-ownership guarantee.
 *
 * These tests are the reason the repository depends on source interfaces. Each one reproduces a
 * way the desktop build could attach the wrong game's media, then asserts the Android build cannot:
 *
 *  * a fuzzy search match (*Elden Ring* -> *Metal Eden*);
 *  * a search result for a different product that the matcher cannot prove is the same game;
 *  * a GOG page whose slug has been retired and re-used for another title;
 *  * a source that returns media stamped with the wrong owner;
 *  * two concurrent loads racing to populate the same screen.
 *
 * The invariant asserted throughout is that **every** screenshot and trailer in a `GameDetail` is
 * owned by an identity in `acceptedIdentities`.
 */
class GameDetailRepositoryMediaTest {

    private val country = "US"

    // ---------------------------------------------------------------- the reported bug

    @Test
    fun `elden ring never shows metal eden screenshots`() = runTest {
        // Steam's search for "Elden Ring" returns Metal Eden first — the exact situation the desktop
        // build mishandled, where a 0.5556 similarity was accepted.
        val steam = FakeSteam(
            searchByQuery = mapOf("elden ring" to listOf(steamHit(METAL_EDEN_APP_ID, "Metal Eden"))),
            detailsByAppId = mapOf(
                METAL_EDEN_APP_ID.toString() to steamDetails(
                    appId = METAL_EDEN_APP_ID,
                    name = "Metal Eden",
                    screenshots = (0..5).map { steamScreenshot(METAL_EDEN_APP_ID, it) },
                    trailers = listOf(steamTrailer(METAL_EDEN_APP_ID)),
                    detailedDescription = "A sci-fi soulslike.",
                ),
            ),
        )

        val detail = repository(steam).load(
            GameReference(
                identity = requireNotNull(GameIdentity.gog("elden_ring")),
                title = "Elden Ring",
            ),
            country,
        )

        assertNoForeignMedia(detail)
        assertEquals(
            "Elden Ring",
            detail.title,
        )
    }

    @Test
    fun `a mismatched search result is not used even when it is the only hit`() = runTest {
        val steam = FakeSteam(
            searchByQuery = mapOf("Cyberpunk 2077" to listOf(steamHit(9999, "Cyberpunk 2077: Apocalyptic Future Edition"))),
            detailsByAppId = mapOf(
                "9999" to steamDetails(
                    appId = 9999,
                    name = "Cyberpunk 2077: Apocalyptic Future Edition",
                    screenshots = (0..3).map { steamScreenshot(9999, it) },
                ),
            ),
        )

        val detail = repository(steam).load(
            GameReference(requireNotNull(GameIdentity.gog("cyberpunk_2077")), "Cyberpunk 2077"),
            country,
        )

        assertNoForeignMedia(detail)
        assertTrue(
            "the wrong app id must not be adopted",
            detail.acceptedIdentities.none { it.id == "9999" },
        )
    }

    @Test
    fun `a proven match does supply media`() = runTest {
        // The counterpart to the two tests above: when the matcher *can* prove the match, the media
        // is used. Otherwise the app would simply never enrich across stores.
        val steam = FakeSteam(
            searchByQuery = mapOf("elden ring" to listOf(steamHit(ELDEN_RING_APP_ID, "Elden Ring"))),
            detailsByAppId = mapOf(
                ELDEN_RING_APP_ID.toString() to steamDetails(
                    appId = ELDEN_RING_APP_ID,
                    name = "Elden Ring",
                    screenshots = (0..5).map { steamScreenshot(ELDEN_RING_APP_ID, it) },
                    trailers = listOf(steamTrailer(ELDEN_RING_APP_ID)),
                    detailedDescription = "An open world action RPG.",
                    developers = listOf("FromSoftware"),
                    genres = listOf("Action", "RPG"),
                ),
            ),
        )

        val detail = repository(steam).load(
            GameReference(requireNotNull(GameIdentity.gog("elden_ring")), "Elden Ring"),
            country,
        )

        assertNoForeignMedia(detail)
        assertEquals(6, detail.screenshots.size)
        assertEquals(1, detail.trailers.size)
        assertTrue(requireNotNull(GameIdentity.steam(ELDEN_RING_APP_ID)) in detail.acceptedIdentities)
        assertEquals(listOf("FromSoftware"), detail.developers)
    }

    @Test
    fun `a re-used GOG slug does not leak the new game's media`() = runTest {
        // A slug retired and re-used for a different title: the page's own title no longer matches,
        // so its content must not be trusted.
        val gog = FakeGog(
            pageTitles = mapOf("elden_ring" to "Metroid Dread"),
            detailsBySlug = mapOf(
                "elden_ring" to GogGameDetails(
                    descriptionHtml = "<p>Metroid Dread</p>",
                    screenshots = (0..4).map { gogScreenshot("elden_ring", it) },
                ),
            ),
        )

        val detail = repository(FakeSteam(), gog).load(
            GameReference(requireNotNull(GameIdentity.gog("elden_ring")), "Elden Ring"),
            country,
        )

        assertNoForeignMedia(detail)
        assertTrue(
            "a mismatched page must not contribute screenshots",
            detail.screenshots.isEmpty(),
        )
        assertFalse(detail.aboutText.contains("Metroid", ignoreCase = true))
    }

    @Test
    fun `media stamped with an unaccepted owner is filtered out`() = runTest {
        // Defence in depth: even if a source returned the wrong owner's media, the final filter
        // drops it. This is the invariant the whole design rests on.
        val elden = requireNotNull(GameIdentity.steam(ELDEN_RING_APP_ID))
        val stranger = requireNotNull(GameIdentity.steam(31380))

        val steam = FakeSteam(
            searchByQuery = mapOf("elden ring" to listOf(steamHit(ELDEN_RING_APP_ID, "Elden Ring"))),
            detailsByAppId = mapOf(
                ELDEN_RING_APP_ID.toString() to steamDetails(
                    appId = ELDEN_RING_APP_ID,
                    name = "Elden Ring",
                    screenshots = listOf(
                        steamScreenshot(ELDEN_RING_APP_ID, 0),
                        // A stray screenshot that belongs to a completely different app.
                        Screenshot(stranger, "https://cdn.steamstatic.com/stranger.thumb.jpg", "https://cdn.steamstatic.com/stranger.jpg"),
                    ),
                    trailers = listOf(steamTrailer(ELDEN_RING_APP_ID)),
                ),
            ),
        )

        val detail = repository(steam).load(GameReference(elden, "Elden Ring"), country)

        assertNoForeignMedia(detail)
        assertEquals(1, detail.screenshots.size)
        assertEquals(elden, detail.screenshots.single().owner)
    }

    @Test
    fun `the native store is trusted without any title comparison`() = runTest {
        // Opening a game *on its own store* needs no matching at all: the app id is authoritative.
        val steam = FakeSteam(
            detailsByAppId = mapOf(
                ELDEN_RING_APP_ID.toString() to steamDetails(
                    appId = ELDEN_RING_APP_ID,
                    name = "Elden Ring",
                    screenshots = (0..15).map { steamScreenshot(ELDEN_RING_APP_ID, it) },
                ),
            ),
        )

        val detail = repository(steam).load(
            GameReference(requireNotNull(GameIdentity.steam(ELDEN_RING_APP_ID)), "Elden Ring"),
            country,
        )

        assertEquals(16, detail.screenshots.size)
        assertTrue(steam.requestedSearches.isEmpty())
    }

    @Test
    fun `screenshot and trailer counts are capped`() = runTest {
        val steam = FakeSteam(
            detailsByAppId = mapOf(
                ELDEN_RING_APP_ID.toString() to steamDetails(
                    appId = ELDEN_RING_APP_ID,
                    name = "Elden Ring",
                    screenshots = (0..40).map { steamScreenshot(ELDEN_RING_APP_ID, it) },
                    trailers = (0..20).map { steamTrailer(ELDEN_RING_APP_ID, "Trailer $it") },
                ),
            ),
        )

        val detail = repository(steam).load(
            GameReference(requireNotNull(GameIdentity.steam(ELDEN_RING_APP_ID)), "Elden Ring"),
            country,
        )

        assertEquals(MAX_SCREENSHOTS, detail.screenshots.size)
        assertEquals(MAX_TRAILERS, detail.trailers.size)
        assertNoForeignMedia(detail)
    }

    // ---------------------------------------------------------------- degraded data

    @Test
    fun `a game with no screenshots or trailers still produces a usable page`() = runTest {
        val steam = FakeSteam(
            detailsByAppId = mapOf(
                ELDEN_RING_APP_ID.toString() to steamDetails(
                    appId = ELDEN_RING_APP_ID,
                    name = "Elden Ring",
                    detailedDescription = "An open world action RPG.",
                    developers = listOf("FromSoftware"),
                    releaseDate = "25 Feb, 2022",
                    genres = listOf("Action", "RPG"),
                ),
            ),
        )

        val detail = repository(steam).load(
            GameReference(requireNotNull(GameIdentity.steam(ELDEN_RING_APP_ID)), "Elden Ring"),
            country,
        )

        assertTrue(detail.screenshots.isEmpty())
        assertTrue(detail.trailers.isEmpty())
        assertEquals("An open world action RPG.", detail.aboutText)
        assertEquals(listOf("FromSoftware"), detail.developers)
        // The UI must be able to hide sections that have no content.
        assertFalse(detail.shortDescription.isBlank() && detail.aboutText.isBlank())
    }

    @Test
    fun `a title with no reachable store still returns a page`() = runTest {
        val detail = repository(FakeSteam(), FakeGog(), FakeEpic()).load(
            GameReference(requireNotNull(GameIdentity.gog("some_never_released_game")), "Some Never Released Game"),
            country,
        )

        assertEquals("Some Never Released Game", detail.title)
        assertTrue(detail.screenshots.isEmpty())
        assertTrue(detail.trailers.isEmpty())
        assertNoForeignMedia(detail)
    }

    @Test
    fun `no details are fetched for a game that was rejected`() = runTest {
        val steam = FakeSteam(
            searchByQuery = mapOf("elden ring" to listOf(steamHit(METAL_EDEN_APP_ID, "Metal Eden"))),
            detailsByAppId = mapOf(
                METAL_EDEN_APP_ID.toString() to steamDetails(
                    appId = METAL_EDEN_APP_ID,
                    name = "Metal Eden",
                    screenshots = (0..5).map { steamScreenshot(METAL_EDEN_APP_ID, it) },
                ),
            ),
        )

        repository(steam).load(GameReference(requireNotNull(GameIdentity.gog("elden_ring")), "Elden Ring"), country)

        assertFalse(
            "details for a rejected app id must never be fetched",
            steam.requestedDetails.contains(METAL_EDEN_APP_ID.toString()),
        )
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    fun `concurrent loads for different games do not contaminate each other`() = runTest {
        val steam = FakeSteam(
            searchByQuery = mapOf(
                "elden ring" to listOf(steamHit(ELDEN_RING_APP_ID, "Elden Ring")),
                "metal eden" to listOf(steamHit(METAL_EDEN_APP_ID, "Metal Eden")),
            ),
            detailsByAppId = mapOf(
                ELDEN_RING_APP_ID.toString() to steamDetails(
                    appId = ELDEN_RING_APP_ID,
                    name = "Elden Ring",
                    screenshots = (0..3).map { steamScreenshot(ELDEN_RING_APP_ID, it) },
                    trailers = listOf(steamTrailer(ELDEN_RING_APP_ID)),
                ),
                METAL_EDEN_APP_ID.toString() to steamDetails(
                    appId = METAL_EDEN_APP_ID,
                    name = "Metal Eden",
                    screenshots = (0..3).map { steamScreenshot(METAL_EDEN_APP_ID, it) },
                    trailers = listOf(steamTrailer(METAL_EDEN_APP_ID)),
                ),
            ),
        )
        val repository = repository(steam)

        val elden = async {
            repository.load(GameReference(requireNotNull(GameIdentity.gog("elden_ring")), "Elden Ring"), country)
        }
        val metal = async {
            repository.load(GameReference(requireNotNull(GameIdentity.gog("metal_eden")), "Metal Eden"), country)
        }
        // Let both loads actually interleave rather than completing one after the other.

        val eldenDetail = elden.await()
        val metalDetail = metal.await()

        assertNoForeignMedia(eldenDetail)
        assertNoForeignMedia(metalDetail)

        assertEquals("Elden Ring", eldenDetail.title)
        assertEquals("Metal Eden", metalDetail.title)
        // Every shot on each page must belong to that game and no other.
        assertTrue(eldenDetail.screenshots.all { it.owner.id == ELDEN_RING_APP_ID.toString() })
        assertTrue(metalDetail.screenshots.all { it.owner.id == METAL_EDEN_APP_ID.toString() })
        assertTrue(eldenDetail.trailers.all { it.owner.id == ELDEN_RING_APP_ID.toString() })
        assertTrue(metalDetail.trailers.all { it.owner.id == METAL_EDEN_APP_ID.toString() })
    }

    // ---------------------------------------------------------------- cross-store offers

    @Test
    fun `a proven cross-store match contributes a price but no foreign media`() = runTest {
        val gog = FakeGog(
            searchByQuery = mapOf(
                "elden ring" to listOf(gogProduct("elden_ring", "Elden Ring", price = 29.99, basePrice = 59.99)),
            ),
            pageTitles = mapOf("elden_ring" to "Elden Ring"),
            detailsBySlug = mapOf(
                "elden_ring" to GogGameDetails(
                    descriptionHtml = "<p>Elden Ring description.</p>",
                    screenshots = (0..3).map { gogScreenshot("elden_ring", it) },
                    requirementsMinimum = "OS: Windows 10",
                ),
            ),
        )
        val steam = FakeSteam(
            searchByQuery = mapOf("elden ring" to listOf(steamHit(ELDEN_RING_APP_ID, "Elden Ring"))),
            detailsByAppId = mapOf(
                ELDEN_RING_APP_ID.toString() to steamDetails(
                    appId = ELDEN_RING_APP_ID,
                    name = "Elden Ring",
                    finalPrice = 59.99,
                    initialPrice = 59.99,
                    screenshots = (0..3).map { steamScreenshot(ELDEN_RING_APP_ID, it) },
                ),
            ),
        )

        val detail = repository(steam, gog).load(
            GameReference(requireNotNull(GameIdentity.gog("elden_ring")), "Elden Ring"),
            country,
        )

        assertNoForeignMedia(detail)
        // Media from both stores is present, and every item is owned by an accepted identity.
        assertTrue(detail.screenshots.isNotEmpty())
        assertEquals(
            setOf(
                requireNotNull(GameIdentity.gog("elden_ring")),
                requireNotNull(GameIdentity.steam(ELDEN_RING_APP_ID)),
            ),
            detail.screenshots.map { it.owner }.toSet(),
        )
        assertEquals("OS: Windows 10", detail.minimumRequirements)
        assertNotNull(detail.bestOffer())
        // The cheapest offer leads the table.
        assertEquals(StoreId.GOG, detail.bestOffer()?.store)
    }

    @Test
    fun `an unproven gog match contributes nothing`() = runTest {
        // The catalogue returns "Metal Eden" for a search on "Elden Ring". It must be rejected, so
        // none of its media or its price may reach the page.
        val stranger = "metal_eden_edition"
        val gog = FakeGog(
            searchByQuery = mapOf("elden ring" to listOf(gogProduct(stranger, "Metal Eden"))),
            detailsBySlug = mapOf(
                stranger to GogGameDetails(
                    descriptionHtml = "<p>Metal Eden description.</p>",
                    screenshots = (0..3).map { gogScreenshot(stranger, it) },
                ),
            ),
        )

        val detail = repository(FakeSteam(), gog).load(
            GameReference(requireNotNull(GameIdentity.gog("elden_ring")), "Elden Ring"),
            country,
        )

        assertNoForeignMedia(detail)
        assertTrue(
            "a rejected GOG product must not contribute screenshots",
            detail.screenshots.isEmpty(),
        )
        assertFalse(detail.aboutText.contains("Metal Eden", ignoreCase = true))
        assertTrue(
            "the rejected slug must not be adopted as this game's GOG page",
            detail.gogSlug != stranger,
        )
        assertTrue(
            "the rejected product's details must never be fetched",
            gog.requestedDetails.none { it == stranger },
        )
        assertTrue(
            "no offer row may point at the rejected product",
            detail.offers.none { it.url.contains(stranger) },
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun repository(
        steam: FakeSteam = FakeSteam(),
        gog: FakeGog = FakeGog(),
        epic: FakeEpic = FakeEpic(),
    ) = GameDetailRepository(steam, gog, epic)

    /**
     * The invariant. Asserted after every scenario so a regression anywhere in the merge shows up as
     * a failure naming this method rather than somewhere far downstream.
     */
    private fun assertNoForeignMedia(detail: GameDetail) {
        val accepted = detail.acceptedIdentities

        detail.screenshots.forEach { shot ->
            assertTrue(
                "screenshot ${shot.fullUrl} is owned by ${shot.owner.key}, which is not an accepted " +
                    "identity (${accepted.map { it.key }})",
                shot.owner in accepted,
            )
        }
        detail.trailers.forEach { trailer ->
            assertTrue(
                "trailer '${trailer.name}' is owned by ${trailer.owner.key}, which is not an accepted " +
                    "identity (${accepted.map { it.key }})",
                trailer.owner in accepted,
            )
        }
    }

    private companion object {
        const val ELDEN_RING_APP_ID = 1245620
        const val METAL_EDEN_APP_ID = 2415780
        const val MAX_SCREENSHOTS = 16
        const val MAX_TRAILERS = 10
    }
}
