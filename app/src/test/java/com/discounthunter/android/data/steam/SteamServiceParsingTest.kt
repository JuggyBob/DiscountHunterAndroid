package com.discounthunter.android.data.steam

import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.TrailerAction
import com.discounthunter.android.core.model.TrailerKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steam payload parsing.
 *
 * These lock in two behaviours the desktop build got wrong:
 *
 *  * `appdetails` is accepted only when the response actually describes the requested app — the
 *    desktop build fell back to "the first successful entry", which could be a different game;
 *  * a screenshot whose asset path belongs to another app folder is discarded, so foreign artwork
 *    cannot reach a page even if the HTML contains it.
 */
class SteamServiceParsingTest {

    private val service = SteamService(
        http = com.discounthunter.android.data.net.HttpClient(
            okhttp3.OkHttpClient(),
            maxRetries = 0,
        ),
        cache = com.discounthunter.android.data.cache.PayloadCache(
            java.io.File(System.getProperty("java.io.tmpdir"), "steam-parsing-test"),
        ),
    )

    private val identity = requireNotNull(GameIdentity.steam(ELDEN_RING))

    // ---------------------------------------------------------------- app details

    @Test
    fun `appdetails for the requested app is parsed`() {
        val payload = """
            {"${ELDEN_RING}": {"success": true, "data": {
              "steam_appid": $ELDEN_RING,
              "name": "Elden Ring",
              "short_description": "Rise, Tarnished.",
              "detailed_description": "<p>Beasts roam.</p>",
              "header_image": "https://cdn.steamstatic.com/header.jpg",
              "developers": [{"name": "FromSoftware"}],
              "publishers": [{"name": "Bandai Namco"}],
              "release_date": {"date": "25 Feb, 2022"},
              "genres": [{"description": "Action"}, {"description": "RPG"}],
              "categories": [{"description": "Single-player"}],
              "price_overview": {"final": 3999, "initial": 5999, "discount_percent": 33, "currency": "USD"},
              "is_free": false,
              "pc_requirements": {"minimum": "OS: Windows 10", "recommended": "RAM: 12 GB"},
              "screenshots": [
                {"path_full": "https://cdn.steamstatic.com/full1.jpg", "path_thumbnail": "https://cdn.steamstatic.com/thumb1.jpg"},
                {"path_full": "https://cdn.steamstatic.com/full2.jpg", "path_thumbnail": ""}
              ],
              "movies": [
                {"name": "Launch Trailer", "thumbnail": "https://cdn.steamstatic.com/t.jpg",
                 "mp4": {"max": "https://cdn.steamstatic.com/trailer480.mp4", "480": "https://cdn.steamstatic.com/trailer480.mp4"}}
              ]
            }}}
        """.trimIndent()

        val data = com.discounthunter.android.core.text.Json.parse(payload)!!
        val details = service.parseAppDetails(identity, data.jsonObject["$ELDEN_RING"]!!.jsonObject["data"]!!.jsonObject)

        assertEquals("Elden Ring", details.name)
        assertEquals("Rise, Tarnished.", details.shortDescription)
        assertEquals(listOf("FromSoftware"), details.developers)
        assertEquals(listOf("Bandai Namco"), details.publishers)
        assertEquals("25 Feb, 2022", details.releaseDate)
        assertEquals(listOf("Action", "RPG"), details.genres)
        assertEquals(listOf("Single-player"), details.categories)
        assertEquals(39.99, details.price?.finalPrice ?: 0.0, 0.001)
        assertEquals(59.99, details.price?.initialPrice ?: 0.0, 0.001)
        assertEquals(33, details.price?.discountPercent)
        assertEquals("OS: Windows 10", details.requirementsMinimum)
        assertEquals(2, details.screenshots.size)
        // A missing thumbnail falls back to the full-size image rather than dropping the shot.
        assertEquals("https://cdn.steamstatic.com/full2.jpg", details.screenshots[1].thumbUrl)
        assertEquals(1, details.trailers.size)
        assertEquals(TrailerKind.DIRECT_VIDEO, details.trailers.single().kind)
        // Every media item is stamped with the app it came from.
        assertTrue(details.screenshots.all { it.owner == identity })
        assertTrue(details.trailers.all { it.owner == identity })
    }

    @Test
    fun `a response describing a different app is not resolved`() {
        // Steam occasionally answers under another key. Accepting the payload anyway is how a
        // different game's data ends up on this game's page.
        val payload = """
            {"$OTHER_APP": {"success": true, "data": {
              "steam_appid": $OTHER_APP,
              "name": "Metal Eden",
              "screenshots": [{"path_full": "https://cdn.steamstatic.com/foreign.jpg"}]
            }}}
        """.trimIndent()

        val data = com.discounthunter.android.core.text.Json.parse(payload)!!
        val resolved = resolveDataBlockForTest(service, data, ELDEN_RING)

        assertEquals(null, resolved)
    }

    @Test
    fun `a response keyed correctly but reporting another app is rejected`() {
        val payload = """
            {"$ELDEN_RING": {"success": true, "data": {
              "steam_appid": $OTHER_APP,
              "name": "Metal Eden"
            }}}
        """.trimIndent()

        val data = com.discounthunter.android.core.text.Json.parse(payload)!!
        assertEquals(null, resolveDataBlockForTest(service, data, ELDEN_RING))
    }

    @Test
    fun `a failed appdetails entry resolves to nothing`() {
        val payload = """{"$ELDEN_RING": {"success": false, "data": null}}"""
        val data = com.discounthunter.android.core.text.Json.parse(payload)!!
        assertEquals(null, resolveDataBlockForTest(service, data, ELDEN_RING))
    }

    // ------------------------------------------------------------ api trailers

    private fun parseApiTrailers(movies: String) = service.parseAppDetails(
        identity,
        com.discounthunter.android.core.text.Json.parse(
            """{"$ELDEN_RING": {"success": true, "data": {"name": "Elden Ring", "movies": $movies}}}""",
        )!!.jsonObject["$ELDEN_RING"]!!.jsonObject["data"]!!.jsonObject,
    ).trailers

    @Test
    fun `an hls manifest is treated as an in-app stream`() {
        // Steam stopped shipping progressive MP4 for many titles; only `mp4` was read, so every one
        // of these trailers degraded to a "watch on the store page" link.
        val trailer = parseApiTrailers(
            """[{"name": "Accolades Trailer", "thumbnail": "https://cdn.steamstatic.com/t.jpg",
                 "hls_h264": "https://video.steamstatic.com/store_trailers/hls_264_master.m3u8"}]""",
        ).single()

        assertEquals(TrailerKind.ADAPTIVE_STREAM, trailer.kind)
        assertTrue(trailer.canPlayInApp)
        assertEquals(TrailerAction.PLAY_IN_APP, trailer.action)
        assertEquals(
            "https://video.steamstatic.com/store_trailers/hls_264_master.m3u8",
            trailer.streamUrl,
        )
    }

    @Test
    fun `a progressive mp4 wins over an hls manifest`() {
        val trailer = parseApiTrailers(
            """[{"name": "T", "thumbnail": "https://cdn.steamstatic.com/t.jpg",
                 "mp4": {"max": "https://cdn.steamstatic.com/big.mp4", "480": "https://cdn.steamstatic.com/small.mp4"},
                 "hls_h264": "https://video.steamstatic.com/x.m3u8"}]""",
        ).single()

        assertEquals(TrailerKind.DIRECT_VIDEO, trailer.kind)
        assertEquals("https://cdn.steamstatic.com/big.mp4", trailer.streamUrl)
    }

    @Test
    fun `an api movie with no stream at all is a store hand-off, not a fake play control`() {
        val trailer = parseApiTrailers(
            """[{"name": "T", "thumbnail": "https://cdn.steamstatic.com/t.jpg",
                 "dash_av1": "https://video.steamstatic.com/a.mpd"}]""",
        ).single()

        assertEquals(TrailerKind.STORE_LINK, trailer.kind)
        assertFalse(trailer.canPlayInApp)
        assertEquals(TrailerAction.OPEN_EXTERNAL, trailer.action)
        assertEquals("Store page", trailer.kindLabel)
    }

    @Test
    fun `a movie with neither a stream nor a poster is dropped`() {
        assertTrue(parseApiTrailers("""[{"name": "T"}]""").isEmpty())
    }

    @Test
    fun `api trailers keep the app they came from as their owner`() {
        val trailers = parseApiTrailers(
            """[{"name": "T", "thumbnail": "https://cdn.steamstatic.com/t.jpg",
                 "hls_h264": "https://video.steamstatic.com/a.m3u8"}]""",
        )
        assertTrue(trailers.all { it.owner == identity })
    }

    @Test
    fun `a missing appdetails field yields an empty value rather than throwing`() {        val payload = """{"$ELDEN_RING": {"success": true, "data": {"name": "Sparse"}}}"""
        val data = com.discounthunter.android.core.text.Json.parse(payload)!!
        val details = service.parseAppDetails(identity, data.jsonObject["$ELDEN_RING"]!!.jsonObject["data"]!!.jsonObject)

        assertEquals("Sparse", details.name)
        assertTrue(details.screenshots.isEmpty())
        assertTrue(details.trailers.isEmpty())
        assertEquals(emptyList<String>(), details.genres)
        assertEquals(null, details.price)
        assertFalse(details.isFree)
    }

    @Test
    fun `a free game reports a zero price`() {
        val payload = """
            {"$ELDEN_RING": {"success": true, "data": {"name": "Free Game", "is_free": true}}}
        """.trimIndent()
        val data = com.discounthunter.android.core.text.Json.parse(payload)!!
        val details = service.parseAppDetails(identity, data.jsonObject["$ELDEN_RING"]!!.jsonObject["data"]!!.jsonObject)

        assertTrue(details.isFree)
    }

    // ---------------------------------------------------------------- store page screenshots

    @Test
    fun `store page screenshots are only accepted from the requested app folder`() {
        val html = """
            <div id="apphub_AppName">Elden Ring</div>
            <img src="https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$ELDEN_RING/ss_aaaa.1920x1080.jpg">
            <img src="https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$OTHER_APP/ss_bbbb.1920x1080.jpg">
            <img src="https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$ELDEN_RING/ss_cccc.1920x1080.png">
        """.trimIndent()

        val shots = SteamPageParser.parseScreenshots(html, identity)

        assertEquals(2, shots.size)
        assertTrue(shots.all { it.owner == identity })
        assertTrue(shots.none { it.fullUrl.contains("/apps/$OTHER_APP/") })
        assertTrue(shots.any { it.fullUrl.endsWith(".jpg") })
        assertTrue(shots.any { it.fullUrl.endsWith(".png") })
    }

    @Test
    fun `duplicate screenshot URLs are collapsed`() {
        val html = """
            <img src="https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$ELDEN_RING/ss_aaaa.1920x1080.jpg">
            <img src="https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/$ELDEN_RING/ss_aaaa.1920x1080.jpg">
        """.trimIndent()

        assertEquals(1, SteamPageParser.parseScreenshots(html, identity).size)
    }

    @Test
    fun `a small screenshot variant is derived for the gallery`() {
        val full = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1/ss_aa.1920x1080.jpg"
        assertEquals(
            "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1/ss_aa.600x338.jpg",
            SteamPageParser.thumbUrlFor(full),
        )
    }

    @Test
    fun `an age-gate page is not parsed as a product page`() {
        val html = """
            <div class="agecheck">Please verify your age</div>
            <img src="https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$ELDEN_RING/ss_aaaa.1920x1080.jpg">
        """.trimIndent()

        val page = SteamPageParser.parse(html, ELDEN_RING)

        assertTrue(page.screenshots.isEmpty())
        assertTrue(page.trailers.isEmpty())
        assertFalse(page.hasContent)
    }

    // ---------------------------------------------------------------- trailers

    @Test
    fun `store page trailers prefer a progressive file`() {
        val html = buildCarouselPage(
            """{"trailers":[{"title":"Trailer","poster":"p.jpg","mp4":"https://cdn.steamstatic.com/a.mp4?x=1"}]}""",
        )

        val trailers = SteamPageParser.parseTrailers(html, identity)

        assertEquals(1, trailers.size)
        assertEquals(TrailerKind.DIRECT_VIDEO, trailers.single().kind)
        assertTrue(trailers.single().canPlayInApp)
        // The query string is dropped so the URL is a stable cache key.
        assertEquals("https://cdn.steamstatic.com/a.mp4", trailers.single().streamUrl)
    }

    @Test
    fun `an hls manifest is recognised as an adaptive stream`() {
        val html = buildCarouselPage(
            """{"trailers":[{"title":"Trailer","poster":"p.jpg","hlsManifest":"https://cdn.steamstatic.com/a.m3u8?token=1"}]}""",
        )

        val trailer = SteamPageParser.parseTrailers(html, identity).single()

        assertEquals(TrailerKind.ADAPTIVE_STREAM, trailer.kind)
        assertTrue(trailer.canPlayInApp)
        assertEquals("https://cdn.steamstatic.com/a.m3u8", trailer.streamUrl)
    }

    @Test
    fun `a dash manifest is used only when it offers h264`() {
        val h264 = buildCarouselPage(
            """{"trailers":[{"title":"T","dashManifests":["https://cdn.steamstatic.com/av1.mpd","https://cdn.steamstatic.com/h264.mpd"]}]}""",
        )
        val av1Only = buildCarouselPage(
            """{"trailers":[{"title":"T","dashManifests":["https://cdn.steamstatic.com/av1.mpd"]}]}""",
        )

        assertEquals(
            "https://cdn.steamstatic.com/h264.mpd",
            SteamPageParser.parseTrailers(h264, identity).single().streamUrl,
        )
        // Nothing playable: the app must not pretend otherwise.
        val fallback = SteamPageParser.parseTrailers(av1Only, identity).single()
        assertFalse(fallback.canPlayInApp)
        assertEquals(TrailerKind.STORE_LINK, fallback.kind)
    }

    @Test
    fun `a trailer with no stream is marked as store link rather than playable`() {
        val html = buildCarouselPage("""{"trailers":[{"title":"T","poster":"p.jpg"}]}""")

        val trailer = SteamPageParser.parseTrailers(html, identity).single()

        assertFalse(trailer.canPlayInApp)
        assertEquals(TrailerKind.STORE_LINK, trailer.kind)
    }

    @Test
    fun `a malformed carousel payload yields no trailers instead of throwing`() {
        val html = buildCarouselPage("""{"trailers": [ this is not json""")
        assertTrue(SteamPageParser.parseTrailers(html, identity).isEmpty())
    }

    @Test
    fun `trailers are owned by the requested app`() {
        val html = buildCarouselPage(
            """{"trailers":[{"title":"T","mp4":"https://cdn.steamstatic.com/a.mp4"}]}""",
        )
        assertTrue(SteamPageParser.parseTrailers(html, identity).all { it.owner == identity })
    }

    // ---------------------------------------------------------------- requirements

    /**
     * Requirements are returned as plain text, ready to display.
     *
     * The desktop build returned raw HTML and left the conversion to the view model, which meant the
     * same string was cleaned twice in some paths and not at all in others. Parsing and cleaning
     * belong together.
     */
    @Test
    fun `two-column requirements are split into minimum and recommended`() {
        val html = """
            <div class="game_area_sys_req" data-os="win">
              <div class="game_area_sys_req_leftCol">OS: Windows 10<br>RAM: 8 GB</div>
              <div class="game_area_sys_req_rightCol">RAM: 16 GB</div>
            </div><div style="clear: both;">
        """.trimIndent()

        val (minimum, recommended) = SteamPageParser.parseRequirements(html)

        assertEquals("OS: Windows 10\nRAM: 8 GB", minimum)
        assertEquals("RAM: 16 GB", recommended)
    }

    @Test
    fun `a single block containing both sections is split on the label`() {
        val html = """
            <div class="game_area_sys_req" data-os="win">
              <div class="game_area_sys_req_full">
                <strong>Minimum:</strong> OS: Windows 10
                <strong>Recommended:</strong> RAM: 16 GB
              </div>
            </div><div style="clear: both;">
        """.trimIndent()

        val (minimum, recommended) = SteamPageParser.parseRequirements(html)

        assertEquals("OS: Windows 10", minimum)
        assertEquals("RAM: 16 GB", recommended)
    }

    @Test
    fun `requirements are decoded into readable text`() {
        val html = """
            <div class="game_area_sys_req" data-os="win">
              <div class="game_area_sys_req_leftCol">
                <strong>Minimum:</strong><br>
                OS: Windows&nbsp;10<br>
                Processor: Intel&nbsp;i5&nbsp;8400
              </div>
              <div class="game_area_sys_req_rightCol">RAM: 12&nbsp;GB</div>
            </div><div style="clear: both;">
        """.trimIndent()

        val (minimum, recommended) = SteamPageParser.parseRequirements(html)

        // No leftover markup, no duplicated "Minimum:" label, no non-breaking spaces.
        assertFalse(minimum.contains("<"))
        assertFalse(minimum.contains("&nbsp;"))
        assertFalse(minimum.lowercase().startsWith("minimum"))
        assertTrue(minimum.contains("OS: Windows 10"))
        assertTrue(minimum.contains("Processor: Intel i5 8400"))
        assertEquals("RAM: 12 GB", recommended)
    }

    @Test
    fun `no requirements block yields empty strings`() {
        assertEquals("" to "", SteamPageParser.parseRequirements("<div>nothing here</div>"))
    }

    // ---------------------------------------------------------------- deal listing

    @Test
    fun `discounted listing rows are parsed with their own prices`() {
        val html = buildSpecialsHtml(
            """
            <a href="https://store.steampowered.com/app/1245620/" data-ds-appid="1245620">
              <span class="title">Elden Ring</span>
              <div class="discount_pct">-33%</div>
              <div class="discount_original_price">$59.99</div>
              <div class="discount_final_price">$39.99</div>
              <img src="https://cdn.steamstatic.com/cap.jpg">
            </a>
            """.trimIndent(),
        )

        val deals = service.parseSpecialsHtml(html)

        assertEquals(1, deals.size)
        val deal = deals.single()
        assertEquals("Elden Ring", deal.title)
        assertEquals(39.99, deal.salePrice, 0.001)
        assertEquals(59.99, deal.normalPrice, 0.001)
        assertEquals(33.0, deal.savingsPercent, 0.1)
        assertEquals("steam_1245620", deal.id)
        assertEquals(identity, deal.identity)
    }

    @Test
    fun `a listing row with a missing discount derives the percentage from prices`() {
        val html = buildSpecialsHtml(
            """
            <a href="#" data-ds-appid="999">
              <span class="title">No Badge</span>
              <div class="discount_original_price">$40.00</div>
              <div class="discount_final_price">$10.00</div>
              <img src="https://cdn.steamstatic.com/cap.jpg">
            </a>
            """.trimIndent(),
        )

        val deal = service.parseSpecialsHtml(html).single()

        assertEquals(75.0, deal.savingsPercent, 0.1)
        assertTrue(deal.isFree.not())
    }

    @Test
    fun `a row with neither price nor discount is skipped`() {
        val html = buildSpecialsHtml(
            """<a href="#" data-ds-appid="999"><span class="title">Broken</span><img src="x.jpg"></a>""",
        )

        assertTrue(service.parseSpecialsHtml(html).isEmpty())
    }

    @Test
    fun `a malformed row does not abort the whole listing`() {
        val html = buildSpecialsHtml(
            """
            <a href="#" data-ds-appid="not-a-number"><span class="title">Bad</span></a>
            <a href="#" data-ds-appid="111"><span class="title">Good</span>
              <div class="discount_pct">-50%</div>
              <div class="discount_final_price">$5.00</div>
            </a>
            """.trimIndent(),
        )

        val deals = service.parseSpecialsHtml(html)

        assertEquals(1, deals.size)
        assertEquals("Good", deals.single().title)
    }

    @Test
    fun `empty html yields no deals`() {
        assertTrue(service.parseSpecialsHtml("").isEmpty())
        assertTrue(service.parseSpecialsHtml("   ").isEmpty())
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Exercises the "which app does this payload actually describe" rule.
     *
     * The resolver is internal rather than private so the rule can be tested directly — it is the
     * gate that stops Steam answering under the wrong key from attaching another game's data.
     */
    private fun resolveDataBlockForTest(
        service: SteamService,
        root: JsonElement,
        appId: Int,
    ): JsonObject? = service.resolveDataBlock(root, appId)

    /**
     * Wraps trailer JSON the way Steam actually embeds it: inside a `data-props` attribute, so every
     * quote is HTML-escaped. The parser depends on that, and the fixtures must be faithful.
     */
    private fun buildCarouselPage(carouselProps: String): String = """
        <div id="apphub_AppName">Elden Ring</div>
        <div class="gamehighlight_desktopcarousel"
             data-props="${carouselProps.replace("&", "&amp;").replace("\"", "&quot;")}"></div>
    """.trimIndent()

    private fun buildSpecialsHtml(rows: String): String =
        """{"results_html": "<div class=\"search_results_rows\">$rows</div>"}"""

    private companion object {
        const val ELDEN_RING = 1245620
        const val OTHER_APP = 2415780
    }
}

