package com.discounthunter.android.data.gog

import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.Screenshot
import com.discounthunter.android.core.model.Trailer
import com.discounthunter.android.core.model.TrailerKind
import com.discounthunter.android.core.text.HtmlEntities
import com.discounthunter.android.core.text.TextTools

/**
 * Parses a GOG game page.
 *
 * GOG embeds the product data as JSON inside the page HTML. Only the blocks belonging to this
 * page are read, and every screenshot and trailer is stamped with the page's own
 * [GameIdentity] — the slug that was requested — so a mis-routed page can never contribute media
 * to the wrong game.
 */
object GogPageParser {

    private val SCREENSHOT_BLOCK = Regex(
        """"screenshots"\s*:\s*\[(.*?)]""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val SCREENSHOT_IMAGE_URL = Regex(""""imageUrl"\s*:\s*"(https:[^"]+)"""")
    /**
     * Older payloads list screenshots as bare URL strings.
     *
     * The URLs are JSON-escaped inside the page (`https:\/\/…`), so both the escaped form and the
     * decoded form are matched — otherwise this fallback silently stops working.
     */
    private val SCREENSHOT_PLAIN_URL = Regex(
        // The tail may itself contain escaped slashes, so only a quote or whitespace terminates it.
        """https:(?:\\/|/){2}images\.gog-statics\.com[^"'\s]+""",
    )

    private val VIDEO_BLOCK = Regex(""""videos"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)

    /**
     * One `{ … }` entry of the videos array.
     *
     * An entry is a JSON object whose *string values may themselves contain braces* — GOG's image
     * URLs carry the literal `{formatter}` placeholder. So the body alternates between "any character
     * that cannot end the object" and "a complete JSON string", which lets `{formatter}` be consumed
     * as part of its string while a real `}` still terminates the entry. `[^{}]` on its own cannot do
     * that: it stops dead at the placeholder and silently loses every trailer.
     *
     * **Both braces are escaped, and that is load-bearing.** Android's regex engine is ICU, which
     * rejects an unmatched `}` outright, while the JVM's `java.util.regex` accepts one. A pattern
     * with a bare `}` therefore passes every unit test and then throws `PatternSyntaxException` from
     * the class initialiser the first time a GOG page is parsed on a device —
     * `ExceptionInInitializerError`, which takes the whole process down. `GogPageParser` is also
     * touched by the *Steam* detail path whenever a game is cross-matched to GOG, which is why the
     * crash looked intermittent and was not limited to GOG titles. `RegexBraceSafetyTest` enforces
     * both rules.
     */
    private val VIDEO_ENTRY = Regex(
        """\{(?:[^{}"]|"(?:[^"\\]|\\.)*")*\}""",
        RegexOption.DOT_MATCHES_ALL,
    )

    // The three fields are read independently of each other and of their order within the object:
    // the previous single mega-regex required exactly `provider`, `url`, `imageUrl`, in that order,
    // with nothing between them, and silently found no trailers for any other shape.
    private val VIDEO_PROVIDER = Regex(""""provider"\s*:\s*"([^"]*)"""")
    private val VIDEO_URL = Regex(""""url"\s*:\s*"([^"]*)"""")
    private val VIDEO_IMAGE = Regex(""""imageUrl"\s*:\s*"([^"]*)""")

    private val YOUTUBE_ID = Regex(
        """(?:embed/|v=|youtu\.be/)([A-Za-z0-9_-]{6,})""",
    )

    private val FEATURE_BLOCK = Regex(""""features"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
    private val FEATURE_NAME = Regex(""""name"\s*:\s*"([^"]{2,60})"""")

    private val DESCRIPTION_FIELD =
        Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""", RegexOption.DOT_MATCHES_ALL)
    private val OVERVIEW_FIELD =
        Regex(""""overview"\s*:\s*"((?:[^"\\]|\\.)*)"""", RegexOption.DOT_MATCHES_ALL)

    private val TYPED_REQUIREMENT = Regex(
        """"type"\s*:\s*"(minimum|recommended)"\s*,\s*"description"\s*:\s*"((?:[^"\\]|\\.)*)"""",
        RegexOption.DOT_MATCHES_ALL,
    )
    /** The `requirements` array opening, used to locate the structured fallback block. */
    private val REQUIREMENTS_ARRAY =
        Regex(""""requirements"\s*:\s*\[\s*\{"id"""")
    private val STRUCTURED_REQUIREMENT_ROW = Regex(
        """"name"\s*:\s*"([^"]{1,40})"\s*,\s*"description"\s*:\s*"((?:[^"\\]|\\.)*)"""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private const val MAX_SCREENSHOTS = 12
    private const val MAX_TRAILERS = 6
    private const val MAX_FEATURES = 12
    private const val MAX_REQUIREMENT_LINES = 10

    /** How far past the `requirements` key the structured rows are read from. */
    private const val REQUIREMENTS_WINDOW_CHARS = 4_000

    fun parse(html: String, identity: GameIdentity): GogGameDetails {
        if (html.isBlank() || !identity.isUsable) return GogGameDetails()

        val (minimum, recommended) = parseRequirements(html)
        return GogGameDetails(
            descriptionHtml = firstField(DESCRIPTION_FIELD, html),
            overviewHtml = firstField(OVERVIEW_FIELD, html),
            requirementsMinimum = minimum,
            requirementsRecommended = recommended,
            screenshots = parseScreenshots(html, identity),
            trailers = parseTrailers(html, identity),
            features = parseFeatures(html),
        )
    }

    fun parseScreenshots(html: String, identity: GameIdentity): List<Screenshot> {
        // Without an owning identity a screenshot is unattributable, so none is emitted. Media that
        // cannot be attributed to a game is how one game's pictures end up on another's page.
        if (!identity.isUsable) return emptyList()
        val content = SCREENSHOT_BLOCK.find(html)?.groupValues?.get(1) ?: return emptyList()
        val result = LinkedHashMap<String, Screenshot>()

        // Current layout: an array of objects carrying `imageUrl`.
        SCREENSHOT_IMAGE_URL.findAll(content).forEach { match ->
            addScreenshot(result, match.groupValues[1], identity)
        }
        // Older layout: a bare array of URL strings.
        if (result.isEmpty()) {
            SCREENSHOT_PLAIN_URL.findAll(content).forEach { match ->
                addScreenshot(result, match.value, identity)
            }
        }
        return result.values.toList()
    }

    private fun addScreenshot(
        into: MutableMap<String, Screenshot>,
        rawUrl: String,
        identity: GameIdentity,
    ) {
        if (into.size >= MAX_SCREENSHOTS) return
        val url = TextTools.resolveImageFormatter(rawUrl)
        if (url.isBlank()) return
        val full = if (url.endsWith(".jpg", ignoreCase = true)) url else "$url.jpg"
        into.putIfAbsent(full, Screenshot(identity, thumbUrlFor(full), full))
    }

    /**
     * Resolves GOG's `{formatter}` placeholder to a given width. GOG serves any width the CDN
     * supports, so the gallery asks for a phone-sized image instead of the 1920px original.
     */
    fun thumbUrlFor(url: String): String = TextTools.resolveImageFormatter(url, "w_679")

    fun parseTrailers(html: String, identity: GameIdentity): List<Trailer> {
        // A trailer without an owning identity could not be attributed to any game, so none is emitted.
        if (!identity.isUsable) return emptyList()
        val content = VIDEO_BLOCK.find(html)?.groupValues?.get(1) ?: return emptyList()
        val result = mutableListOf<Trailer>()

        for (match in VIDEO_ENTRY.findAll(content)) {
            if (result.size >= MAX_TRAILERS) break
            val entry = match.value
            val provider = VIDEO_PROVIDER.find(entry)?.groupValues?.get(1).orEmpty().lowercase()
            // Keep the full URL for a beat: the YouTube video id lives in the query string
            // (`watch?v=…`), so it must be extracted before the cache-buster is stripped.
            val url = VIDEO_URL.find(entry)?.groupValues?.get(1).orEmpty().replace("\\/", "/")
            val rawUrl = url.substringBefore('?')
            val thumb = TextTools.resolveImageFormatter(
                VIDEO_IMAGE.find(entry)?.groupValues?.get(1).orEmpty().replace("\\/", "/"),
            )
            if (rawUrl.isBlank() && thumb.isBlank()) continue

            val youtubeId = YOUTUBE_ID.find(url)?.groupValues?.get(1).orEmpty()
            val isYouTube = provider.contains("youtube") || youtubeId.isNotBlank()

            val trailer = Trailer(
                owner = identity,
                kind = if (isYouTube) TrailerKind.YOUTUBE else TrailerKind.STORE_LINK,
                name = "Trailer ${result.size + 1}",
                thumbUrl = thumb,
                // GOG hosts no playable stream of its own, so there is deliberately no stream URL
                // here; the control hands the YouTube link to the system instead of pretending.
                streamUrl = "",
                webUrl = if (isYouTube) "https://www.youtube.com/watch?v=$youtubeId" else rawUrl,
                youtubeId = youtubeId,
            )
            if (trailer.isUsable) result += trailer
        }
        return result
    }

    fun parseFeatures(html: String): List<String> {
        val block = FEATURE_BLOCK.find(html)?.groupValues?.get(1) ?: return emptyList()
        return FEATURE_NAME.findAll(block)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_FEATURES)
            .toList()
    }

    fun parseRequirements(html: String): Pair<String, String> {
        var minimum = ""
        var recommended = ""

        for (match in TYPED_REQUIREMENT.findAll(html)) {
            val text = decodeJsonString(match.groupValues[2])
            if (text.isBlank()) continue
            if (match.groupValues[1].equals("minimum", ignoreCase = true)) {
                if (minimum.isBlank()) minimum = text
            } else if (recommended.isBlank()) {
                recommended = text
            }
            if (minimum.isNotBlank() && recommended.isNotBlank()) break
        }

        if (TextTools.stripTags(minimum).isBlank()) {
            // Structured fallback: "requirements":[{"id":…,"name":"Processor:","description":"…"}].
            //
            // Only a bounded window after the key is scanned. Matching the whole document would pick
            // up unrelated `name`/`description` pairs from other product blocks, and the array's real
            // extent cannot be found reliably with a regex (its strings may contain brackets).
            val start = REQUIREMENTS_ARRAY.find(html)?.range?.last?.plus(1)
            if (start != null) {
                val window = html.substring(start, minOf(html.length, start + REQUIREMENTS_WINDOW_CHARS))
                val lines = STRUCTURED_REQUIREMENT_ROW.findAll(window)
                    .map { (it.groupValues[1] + " " + decodeJsonString(it.groupValues[2])).trim() }
                    .filter { it.isNotEmpty() }
                    .take(MAX_REQUIREMENT_LINES)
                    .toList()
                if (lines.isNotEmpty()) minimum = lines.joinToString("\n")
            }
        }

        return minimum to recommended
    }

    private fun firstField(regex: Regex, html: String): String =
        regex.findAll(html)
            .map { decodeJsonString(it.groupValues[1]) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    /** Decodes a JSON string literal that was extracted by regex. */
    fun decodeJsonString(literal: String): String = try {
        kotlinx.serialization.json.Json
            .parseToJsonElement("\"$literal\"")
            .let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.content
            .orEmpty()
    } catch (_: Exception) {
        literal
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
    }

    /** Reads the page's own title so a slug can be validated before its content is trusted. */
    private val PAGE_TITLE = Regex(
        """<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']{2,150})["']|<title>([^<]{2,150})</title>""",
        RegexOption.IGNORE_CASE,
    )
    private val TITLE_SUFFIX = Regex("""\s*(\||-|–)?\s*GOG\.com\s*$""", RegexOption.IGNORE_CASE)

    fun parsePageTitle(html: String): String {
        val match = PAGE_TITLE.find(html) ?: return ""
        val group = if (match.groupValues[1].isNotEmpty()) 1 else 2
        return TITLE_SUFFIX.replace(HtmlEntities.decode(match.groupValues[group]), "").trim()
    }
}
