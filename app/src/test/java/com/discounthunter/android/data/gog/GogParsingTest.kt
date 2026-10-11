package com.discounthunter.android.data.gog

import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.TrailerKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GOG payload parsing.
 *
 * GOG's game page embeds its product data as JSON inside HTML and its catalogue is the one source
 * that returns generic bestsellers for multi-word queries — both are places where the wrong game's
 * content could reach a page. Every parsed screenshot and trailer is therefore stamped with the slug
 * that was requested, and the tests below assert that.
 */
class GogParsingTest {

    private val identity = requireNotNull(GameIdentity.gog("elden_ring"))

    /** A service whose network and cache are inert; only its pure parsing is exercised. */
    private val service = GogService(
        http = com.discounthunter.android.data.net.HttpClient(okhttp3.OkHttpClient(), maxRetries = 0),
        cache = com.discounthunter.android.data.cache.PayloadCache(
            File(System.getProperty("java.io.tmpdir"), "gog-parsing-test"),
        ),
    )

    private fun parseObject(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // ---------------------------------------------------------------- screenshots

    @Test
    fun `screenshots are read from the current layout`() {
        val html = """
            <script>{"product":{"screenshots":[
              {"imageUrl":"https://images.gog-statics.com/1.jpg"},
              {"imageUrl":"https://images.gog-statics.com/2.jpg"}
            ]}}</script>
        """.trimIndent()

        val shots = GogPageParser.parseScreenshots(html, identity)

        assertEquals(2, shots.size)
        assertTrue(shots.all { it.owner == identity })
    }

    /**
     * An older payload published screenshots as a bare array of URL strings rather than objects.
     *
     * Inside the page those URLs are JSON-escaped, so the literal text contains `\/` — which is what
     * the fixture below reproduces, and what the matcher has to cope with.
     */
    @Test
    fun `an older bare-array layout is still read`() {
        val html = """<script>{"screenshots":["https:\/\/images.gog-statics.com\/1.jpg"]}</script>"""

        val shots = GogPageParser.parseScreenshots(html, identity)

        assertEquals(1, shots.size)
        assertEquals("https://images.gog-statics.com/1.jpg", shots.single().fullUrl)
        assertEquals(identity, shots.single().owner)
    }

    @Test
    fun `the image formatter placeholder is resolved`() {
        val html = """
            <script>{"screenshots":[{"imageUrl":"https://images.gog-statics.com/{formatter}/1.jpg"}]}</script>
        """.trimIndent()

        val shot = GogPageParser.parseScreenshots(html, identity).single()

        assertFalse(shot.fullUrl.contains("{formatter}"))
        // The gallery asks for a phone-sized image, not the multi-megabyte original.
        assertTrue(shot.fullUrl.contains("w_679"))
        assertTrue(shot.thumbUrl.contains("w_679"))
    }

    @Test
    fun `a url without a jpg extension gets one`() {
        val html = """<script>{"screenshots":[{"imageUrl":"https://images.gog-statics.com/1"}]}</script>"""

        assertTrue(GogPageParser.parseScreenshots(html, identity).single().fullUrl.endsWith(".jpg"))
    }

    @Test
    fun `screenshots are capped and de-duplicated`() {
        val html = buildString {
            append("""<script>{"screenshots":[""")
            repeat(20) { append("""{"imageUrl":"https://images.gog-statics.com/$it.jpg"},""") }
            append("""{"imageUrl":"https://images.gog-statics.com/0.jpg"}""")
            append("""]}</script>""")
        }

        val shots = GogPageParser.parseScreenshots(html, identity)

        assertTrue("the cap should apply", shots.size <= 12)
        assertEquals(shots.map { it.fullUrl }.distinct().size, shots.size)
    }

    @Test
    fun `no screenshots block yields nothing`() {
        assertTrue(GogPageParser.parseScreenshots("<div>nothing</div>", identity).isEmpty())
    }

    @Test
    fun `media without an owning identity is never returned`() {
        // A screenshot must always be attributable to a game. Without a slug there is no owner, so
        // emitting it would make the item unattributable — and unattributable media is exactly how
        // one game's pictures end up on another's page.
        val html = """<script>{"screenshots":[{"imageUrl":"https://images.gog-statics.com/1.jpg"}]}</script>"""

        assertTrue(GogPageParser.parseScreenshots(html, GameIdentity.NONE).isEmpty())
        assertTrue(GogPageParser.parseTrailers(html, GameIdentity.NONE).isEmpty())
        assertFalse(GogPageParser.parse(html, GameIdentity.NONE).hasContent)
    }

    // ---------------------------------------------------------------- trailers

    @Test
    fun `youtube trailers carry an id and are marked as youtube`() {
        val html = """
            <script>{"videos":[
              {"provider":"youtube","url":"https://www.youtube.com/embed/dQw4w9WgXcQ","imageUrl":"https://images.gog-statics.com/t.jpg"}
            ]}</script>
        """.trimIndent()

        val trailer = GogPageParser.parseTrailers(html, identity).single()

        assertEquals(TrailerKind.YOUTUBE, trailer.kind)
        assertEquals("dQw4w9WgXcQ", trailer.youtubeId)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", trailer.webUrl)
        // GOG hosts no stream the app may use, so none is claimed.
        assertEquals("", trailer.streamUrl)
        assertFalse(trailer.canPlayInApp)
        assertEquals(identity, trailer.owner)
    }

    @Test
    fun `a non-youtube video is not given a youtube id`() {
        val html = """
            <script>{"videos":[{"provider":"vimeo","url":"https://vimeo.com/12345","imageUrl":"https://i.jpg"}]}</script>
        """.trimIndent()

        val trailer = GogPageParser.parseTrailers(html, identity).single()

        assertEquals("", trailer.youtubeId)
        assertFalse(trailer.canPlayInApp)
    }

    @Test
    fun `trailers are capped`() {
        val html = buildString {
            append("""<script>{"videos":[""")
            repeat(12) { append("""{"provider":"youtube","url":"https://www.youtube.com/embed/abc$it","imageUrl":"https://i.jpg"},""") }
            append("""]}</script>""")
        }

        assertTrue(GogPageParser.parseTrailers(html, identity).size <= 6)
    }

    @Test
    fun `no videos block yields nothing`() {
        assertTrue(GogPageParser.parseTrailers("<div>nothing</div>", identity).isEmpty())
    }

    // ---------------------------------------------------------------- text fields

    @Test
    fun `description and overview are extracted`() {
        val html = """
            <script>{"description":"The first description.","overview":"An overview."}</script>
        """.trimIndent()

        assertEquals("The first description.", GogPageParser.parse(html, identity).descriptionHtml)
        assertEquals("An overview.", GogPageParser.parse(html, identity).overviewHtml)
    }

    @Test
    fun `escaped quotes in a description are decoded`() {
        val html = """<script>{"description":"He said \"hello\" loudly."}</script>"""

        val description = GogPageParser.parse(html, identity).descriptionHtml

        assertTrue(description.contains("\"hello\""))
    }

    @Test
    fun `features are extracted`() {
        val html = """<script>{"features":[{"name":"Single-player"},{"name":"Steam Cloud"}]}</script>"""

        assertEquals(listOf("Single-player", "Steam Cloud"), GogPageParser.parse(html, identity).features)
    }

    @Test
    fun `requirements are split into minimum and recommended`() {
        val html = """
            <script>{"requirements":[
              {"type":"minimum","description":"OS: Windows 10"},
              {"type":"recommended","description":"RAM: 16 GB"}
            ]}</script>
        """.trimIndent()

        val details = GogPageParser.parse(html, identity)

        assertEquals("OS: Windows 10", details.requirementsMinimum)
        assertEquals("RAM: 16 GB", details.requirementsRecommended)
    }

    @Test
    fun `a structured requirements block is a fallback`() {
        // Some titles publish `requirements` as name/description rows rather than typed blocks.
        val html = """
            <script>{"requirements":[{"id":1,"name":"Processor:","description":"Intel i5"},
            {"id":2,"name":"Memory:","description":"8 GB RAM"}]}</script>
        """.trimIndent()

        val minimum = GogPageParser.parseRequirements(html).first

        assertTrue(minimum.contains("Processor:"))
        assertTrue(minimum.contains("Intel i5"))
        assertTrue(minimum.contains("Memory:"))
    }

    @Test
    fun `an empty page yields empty details`() {
        val details = GogPageParser.parse("", identity)
        assertFalse(details.hasContent)
    }

    @Test
    fun `a blank slug yields empty details`() {
        val html = """<script>{"description":"Something"}</script>"""
        assertFalse(GogPageParser.parse(html, GameIdentity.NONE).hasContent)
    }

    // ---------------------------------------------------------------- page title

    @Test
    fun `the og title is preferred and the store suffix removed`() {
        val html = """<meta property="og:title" content="Elden Ring | GOG.com"><title>Elden Ring</title>"""

        assertEquals("Elden Ring", GogPageParser.parsePageTitle(html))
    }

    @Test
    fun `the title element is a fallback`() {
        assertEquals("Elden Ring", GogPageParser.parsePageTitle("<title>Elden Ring</title>"))
    }

    @Test
    fun `a page with no title yields nothing`() {
        assertEquals("", GogPageParser.parsePageTitle("<div>hello</div>"))
    }

    // ---------------------------------------------------------------- catalogue products

    @Test
    fun `a catalogue product is parsed with its money amounts`() {
        val json = """
            {"title":"Elden Ring","slug":"elden_ring","storeLink":"https://www.gog.com/en/game/elden_ring",
             "coverHorizontal":"https://images.gog-statics.com/{formatter}/cover.jpg",
             "price":{"finalMoney":{"amount":29.99,"currency":"USD"},
                      "baseMoney":{"amount":59.99,"currency":"USD"},"discount":"-50"}}
        """.trimIndent()

        val product = service.parseProduct(parseObject(json))

        assertEquals("Elden Ring", product?.title)
        assertEquals("elden_ring", product?.identity?.id)
        assertEquals(29.99, product?.price ?: 0.0, 0.001)
        assertEquals(59.99, product?.basePrice ?: 0.0, 0.001)
        assertEquals(50.0, product?.discountPercent ?: 0.0, 0.1)
        assertFalse(product?.thumbUrl?.contains("{formatter}") ?: true)
    }

    @Test
    fun `a product with no discount derives the percentage from prices`() {
        val json = """
            {"title":"X","slug":"x","price":{"finalMoney":{"amount":25.0},"baseMoney":{"amount":100.0}}}
        """.trimIndent()

        val product = service.parseProduct(parseObject(json))

        assertEquals(75.0, product?.discountPercent ?: 0.0, 0.1)
    }

    @Test
    fun `a product with no title or slug is rejected`() {
        // Without a slug there is no stable identity, so the product cannot be linked to a page.
        assertNull(service.parseProduct(null))
        assertNull(service.parseProduct(parseObject("{}")))
        assertNull(service.parseProduct(parseObject("""{"title":"No Slug"}""")))
    }
}
