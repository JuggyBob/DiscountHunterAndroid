package com.discounthunter.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Price-history source selection.
 *
 * The rule the product asked for is blunt: a database is only offered for a game that is actually
 * listed on a store that database covers. Getting this wrong would send an Epic-only game to
 * SteamDB, where it can never appear.
 */
class PriceHistoryCatalogTest {

    private fun detail(
        title: String = "Elden Ring",
        steamAppId: Int? = null,
        offers: List<StoreOffer> = emptyList(),
    ) = GameDetail(
        identity = null,
        acceptedIdentities = emptySet(),
        title = title,
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
        offers = offers,
        sources = emptyList(),
        storeUrls = emptyMap(),
        steamAppId = steamAppId,
        gogSlug = "",
        epicSlug = "",
        epicUpcoming = false,
    )

    private fun epicOffer() = StoreOffer(
        store = StoreId.EPIC,
        price = 0.0,
        retailPrice = 19.99,
        savingsPercent = 100.0,
        url = "https://store.epicgames.com/en-US/p/x",
    )

    @Test
    fun `an epic only game is not offered SteamDB, GOG Database or PSPrices`() {
        val sources = PriceHistoryCatalog.sourcesFor(setOf(StoreId.EPIC)).map { it.label }

        assertTrue(sources.contains("EGData"))
        assertTrue(!sources.contains("SteamDB"))
        assertTrue(!sources.contains("GOG Database"))
        assertTrue(!sources.contains("PSPrices"))
    }

    @Test
    fun `a steam game is offered the steam databases`() {
        val sources = PriceHistoryCatalog.sourcesFor(setOf(StoreId.STEAM)).map { it.label }

        assertTrue(sources.contains("SteamDB"))
        assertTrue(sources.contains("PSPrices"))
        assertTrue(sources.contains("CheapShark"))
        assertTrue(!sources.contains("EGData"))
        assertTrue(!sources.contains("GOG Database"))
    }

    @Test
    fun `a game on several stores is offered the union`() {
        val sources = PriceHistoryCatalog.sourcesFor(StoreId.MAJOR.toSet()).map { it.label }

        listOf("SteamDB", "EGData", "GOG Database", "CheapShark", "PSPrices").forEach {
            assertTrue("expected $it", sources.contains(it))
        }
    }

    @Test
    fun `the most specific database comes first`() {
        val sources = PriceHistoryCatalog.sourcesFor(StoreId.MAJOR.toSet())

        // SteamDB covers one store, IsThereAnyDeal covers three, so SteamDB must lead.
        assertEquals(PriceHistorySource.STEAMDB, sources.first())
    }

    @Test
    fun `a steam database with an app id links straight to the game`() {
        val url = PriceHistoryCatalog.urlFor(
            source = PriceHistorySource.STEAMDB,
            detail = detail(steamAppId = 1245620),
        )

        assertEquals("https://steamdb.info/app/1245620/", url)
    }

    @Test
    fun `without a steam app id the steam database has no page to link to`() {
        assertNull(PriceHistoryCatalog.urlFor(PriceHistorySource.STEAMDB, detail()))
    }

    @Test
    fun `title based databases get an encoded search for the game`() {
        val url = PriceHistoryCatalog.urlFor(
            source = PriceHistorySource.GGDEALS,
            detail = detail(title = "Baldur's Gate 3"),
        )

        assertEquals("https://gg.deals/search/?q=Baldur%27s+Gate+3", url)
    }

    @Test
    fun `a blank title produces no link rather than a broken one`() {
        assertNull(PriceHistoryCatalog.urlFor(PriceHistorySource.CHEAPSHARK, detail(title = "   ")))
    }

    @Test
    fun `every title based database has a url`() {
        val game = detail(title = "Hades")
        val titleBased = PriceHistorySource.entries.filter { it.appUrl == null }

        titleBased.forEach { source ->
            val url = PriceHistoryCatalog.urlFor(source, game)
            assertTrue("$source has no url", url?.startsWith("https://") == true)
        }
    }
}