package com.discounthunter.android.data.steam

import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.Screenshot
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.Trailer
import com.discounthunter.android.core.model.TrailerKind
import com.discounthunter.android.core.text.HtmlEntities
import com.discounthunter.android.core.text.Json
import com.discounthunter.android.core.text.TextTools
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses a Steam store page.
 *
 * The page is the only source for a playable trailer on newer titles and for screenshots on titles
 * whose `appdetails` payload is incomplete, so the desktop app scraped it too.
 *
 * Every screenshot URL carries the app id in its path (`.../steam/apps/<appId>/ss_…`). The parser
 * **only accepts images whose folder matches the requested app id** — the desktop build accepted
 * any folder, which is another way foreign artwork could reach a game's page.
 */
object SteamPageParser {

    private val META_DESCRIPTION = Regex(
        """<meta[^>]+name=["']Description["'][^>]+content=["']([^"']{20,600})["']""",
        RegexOption.IGNORE_CASE,
    )

    private val WINDOW_REQUIREMENTS = Regex(
        """data-os=["']win["'][^>]*>(.*?)<div\s+style=["']clear:\s*both;["']""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val TWO_COLUMN_REQUIREMENTS = Regex(
        """game_area_sys_req_leftCol["'][^>]*>(.*?)</div>\s*<div[^>]*game_area_sys_req_rightCol["'][^>]*>(.*?)</div>\s*</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val FULL_REQUIREMENTS = Regex(
        """game_area_sys_req_full["'][^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val STRONG_LABEL = Regex(
        """<\s*strong\s*>\s*(Minimum|Recommended):?\s*</\s*strong\s*>""",
        RegexOption.IGNORE_CASE,
    )

    private val RECOMMENDED_SPLIT = Regex(
        """<\s*strong\s*>\s*Recommended:?\s*</\s*strong\s*>""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Steam screenshot assets. Group 1 is the app folder, which must equal the requested app id.
     */
    private val SCREENSHOT_URL = Regex(
        """https://shared\.(?:akamai|fastly|cloudflare)\.steamstatic\.com/store_item_assets/steam/apps/(\d+)/ss_([A-Fa-f0-9]+)\.1920x1080\.([A-Za-z]+)""",
    )

    private val HIGHLIGHT_CAROUSEL = Regex(
        """gamehighlight_desktopcarousel["'][^>]*?data-props=["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private const val MAX_SCREENSHOTS = 12
    private const val MAX_TRAILERS = 6

    fun parse(html: String, appId: Int): SteamPageDetails {
        if (html.isBlank()) return SteamPageDetails()

        val identity = GameIdentity.steam(appId) ?: return SteamPageDetails()

        // A cookie-less request is answered with the age gate instead of the product page. Detecting
        // it lets the caller fall back gracefully rather than parsing nothing.
        if (!html.contains("apphub_AppName", ignoreCase = true) &&
            html.contains("agecheck", ignoreCase = true)
        ) {
            return SteamPageDetails()
        }

        val shortDescription = META_DESCRIPTION.find(html)
            ?.groupValues?.get(1)
            ?.let { HtmlEntities.decode(it) }
            .orEmpty()

        val (minimum, recommended) = parseRequirements(html)

        return SteamPageDetails(
            shortDescription = shortDescription,
            requirementsMinimum = minimum,
            requirementsRecommended = recommended,
            screenshots = parseScreenshots(html, identity),
            trailers = parseTrailers(html, identity),
        )
    }

    /**
     * Keeps only screenshots whose asset path belongs to [identity]. A screenshot hosted under a
     * different app folder belongs to a different game and is discarded.
     */
    fun parseScreenshots(html: String, identity: GameIdentity): List<Screenshot> {
        val expectedFolder = identity.id
        if (expectedFolder.isBlank()) return emptyList()

        val result = LinkedHashMap<String, Screenshot>()
        for (match in SCREENSHOT_URL.findAll(html)) {
            if (result.size >= MAX_SCREENSHOTS) break
            val folder = match.groupValues[1]
            if (folder != expectedFolder) continue // belongs to another app

            val hash = match.groupValues[2]
            val extension = match.groupValues[3]
            val full = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/" +
                "$folder/ss_$hash.1920x1080.$extension"
            result.putIfAbsent(full, Screenshot(identity, thumbUrlFor(full), full))
        }
        return result.values.toList()
    }

    /**
     * Derives a small variant of a Steam screenshot URL.
     *
     * Steam's asset CDN serves `…/ss_<hash>.<size>.<ext>` for several sizes. The 600x338 variant is
     * the one that fits a phone gallery tile; if a deployment ever stops serving it the image
     * loader falls back to the original (see `NetworkArtwork`), so this stays an optimisation
     * rather than a correctness requirement.
     */
    fun thumbUrlFor(fullUrl: String): String =
        fullUrl.replace(".1920x1080.", ".600x338.")

    fun parseTrailers(html: String, identity: GameIdentity): List<Trailer> {
        val props = HIGHLIGHT_CAROUSEL.find(html)?.groupValues?.get(1) ?: return emptyList()
        // The attribute value is HTML-escaped JSON.
        val blob = HtmlEntities.decode(props).replace("\\/", "/")
        val root = Json.parse(blob) ?: return emptyList()

        val result = mutableListOf<Trailer>()
        for ((index, element) in Json.items(root, "trailers").withIndex()) {
            if (result.size >= MAX_TRAILERS) break

            val name = Json.str(element, "title").ifBlank { Json.str(element, "name") }
                .ifBlank { "Trailer ${index + 1}" }
            val poster = Json.str(element, "poster").ifBlank { Json.str(element, "thumbnail") }

            // Steam publishes DASH and HLS manifests and, on older entries, a plain MP4. Prefer a
            // progressive file, then the HLS master playlist, then an H.264 DASH manifest.
            val mp4 = Json.str(element, "mp4")
            val stream = when {
                mp4.isNotBlank() -> mp4.substringBefore('?')
                Json.str(element, "hlsManifest").isNotBlank() ->
                    Json.str(element, "hlsManifest").substringBefore('?')
                else -> Json.items(element, "dashManifests")
                    .firstNotNullOfOrNull { dash ->
                        (dash as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?.takeIf { it.contains("h264", ignoreCase = true) }
                            ?.substringBefore('?')
                    }.orEmpty()
            }

            val kind = when {
                stream.endsWith(".mp4", ignoreCase = true) -> TrailerKind.DIRECT_VIDEO
                stream.endsWith(".m3u8", ignoreCase = true) -> TrailerKind.ADAPTIVE_STREAM
                stream.isNotBlank() -> TrailerKind.STORE_LINK
                else -> TrailerKind.STORE_LINK
            }

            val trailer = Trailer(
                owner = identity,
                kind = kind,
                name = name,
                thumbUrl = poster,
                streamUrl = stream,
                webUrl = "${storeUrl(identity)}#game_area_description",
            )
            if (trailer.isUsable) result += trailer
        }
        return result
    }

    /**
     * Reads the Windows requirements block.
     *
     * Returns plain text rather than markup. The desktop build returned raw HTML and left the
     * conversion to its view model, so the same value was cleaned twice on some paths and not at all
     * on others — a `Minimum:` label and `<br>` tags could both reach the screen. Converting here
     * gives the formatting one owner.
     */
    fun parseRequirements(html: String): Pair<String, String> {
        val block = WINDOW_REQUIREMENTS.find(html)?.groupValues?.get(1) ?: return "" to ""

        TWO_COLUMN_REQUIREMENTS.find(block)?.let { match ->
            return clean(match.groupValues[1]) to clean(match.groupValues[2])
        }

        val text = (FULL_REQUIREMENTS.find(block)?.groupValues?.get(1) ?: block).trim()

        // Some titles publish both sections inside a single block.
        val parts = RECOMMENDED_SPLIT.split(text, 2)
        if (parts.size == 2) {
            return clean(STRONG_LABEL.replace(parts[0], "")) to clean(parts[1])
        }
        return clean(STRONG_LABEL.replace(text, "")) to ""
    }

    /** Strips markup, decodes entities and removes the redundant section label. */
    private fun clean(fragment: String): String =
        TextTools.cleanRequirements(fragment).takeIf { it.isNotBlank() }.orEmpty()

    fun storeUrl(identity: GameIdentity): String =
        "https://store.steampowered.com/app/${identity.id}/"

    /** Extracts a Steam app id from a store URL, used when an external link opens the app. */
    fun appIdFromUrl(url: String?): Int? = GameIdentity.fromUrl(url.orEmpty())
        ?.takeIf { it.store == StoreId.STEAM }
        ?.id
        ?.toIntOrNull()
}
