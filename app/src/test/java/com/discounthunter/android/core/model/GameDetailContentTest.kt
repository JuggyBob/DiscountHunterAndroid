package com.discounthunter.android.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "no store has this game" signal.
 *
 * A detail page must never render as a bare title over an empty body. These tests pin the
 * difference between "we learned something about this game" and "nothing at all came back", because
 * the two look identical on screen and only one of them should say so.
 */
class GameDetailContentTest {

    private fun detail(
        shortDescription: String = "",
        aboutText: String = "",
        screenshots: List<Screenshot> = emptyList(),
        trailers: List<Trailer> = emptyList(),
        developers: List<String> = emptyList(),
        publishers: List<String> = emptyList(),
        releaseDate: String = "",
        genres: List<String> = emptyList(),
        categories: List<String> = emptyList(),
        features: List<String> = emptyList(),
        price: PriceOverview? = null,
        offers: List<StoreOffer> = emptyList(),
        sources: List<String> = emptyList(),
    ) = GameDetail(
        identity = null,
        acceptedIdentities = emptySet(),
        title = "Some Game",
        heroUrl = "",
        shortDescription = shortDescription,
        aboutText = aboutText,
        minimumRequirements = "",
        recommendedRequirements = "",
        developers = developers,
        publishers = publishers,
        releaseDate = releaseDate,
        genres = genres,
        categories = categories,
        features = features,
        price = price,
        isFreeToPlay = false,
        screenshots = screenshots,
        trailers = trailers,
        offers = offers,
        sources = emptyList(),
        storeUrls = emptyMap(),
        steamAppId = null,
        gogSlug = "",
        epicSlug = "",
        epicUpcoming = false,
    )

    @Test
    fun `a page with nothing at all is reported as empty`() {
        assertFalse(detail().hasAnyContent)
    }

    @Test
    fun `a store search hit on its own is not content`() {
        // A title match that produced no media, no metadata and no offer is not a detail page.
        assertFalse(detail(sources = listOf("Steam")).hasAnyContent)
    }

    @Test
    fun `an offer alone means the store knows the game`() {
        val offer = StoreOffer(
            store = StoreId.GOG,
            price = 9.99,
            retailPrice = 19.99,
            savingsPercent = 50.0,
            url = "https://www.gog.com/en/game/x",
        )

        assertTrue(detail(offers = listOf(offer)).hasAnyContent)
    }

    @Test
    fun `each kind of real content counts`() {
        assertTrue(detail(shortDescription = "A game").hasAnyContent)
        assertTrue(detail(aboutText = "Longer text").hasAnyContent)
        assertTrue(detail(developers = listOf("Someone")).hasAnyContent)
        assertTrue(detail(publishers = listOf("Someone")).hasAnyContent)
        assertTrue(detail(releaseDate = "Jan 1, 2020").hasAnyContent)
        assertTrue(detail(genres = listOf("RPG")).hasAnyContent)
        assertTrue(detail(categories = listOf("Single-player")).hasAnyContent)
        assertTrue(detail(features = listOf("Cloud saves")).hasAnyContent)
        assertTrue(detail(price = PriceOverview(9.99, 19.99, 50, "USD")).hasAnyContent)
    }

    @Test
    fun `a screenshot or trailer alone counts`() {
        val shot = Screenshot(
            owner = GameIdentity(StoreId.STEAM, "1245620"),
            thumbUrl = "https://a/thumb.jpg",
            fullUrl = "https://a/full.jpg",
        )
        assertTrue(detail(screenshots = listOf(shot)).hasAnyContent)
    }

    @Test
    fun `blank strings are not content`() {
        assertFalse(detail(shortDescription = "   ", aboutText = "  ").hasAnyContent)
    }
}