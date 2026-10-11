package com.discounthunter.android.core.model

/** How a trailer can be delivered to the user. */
enum class TrailerKind {
    /** A progressive MP4 — played in-app. */
    DIRECT_VIDEO,

    /** An HLS manifest — played in-app by ExoPlayer's HLS module. */
    ADAPTIVE_STREAM,

    /** A YouTube video — handed to the YouTube app / browser. */
    YOUTUBE,

    /** No stream exists; the control opens the store page instead. */
    STORE_LINK,
}

/**
 * One screenshot.
 *
 * [owner] is the identity of the app that published the image. The detail assembler refuses to
 * show a screenshot under a game whose accepted identities do not include [owner], which is what
 * guarantees that game A never displays game B's screenshots.
 */
data class Screenshot(
    val owner: GameIdentity,
    val thumbUrl: String,
    val fullUrl: String,
)

/** One trailer / preview video, tagged with the game it belongs to for the same reason as [Screenshot]. */
data class Trailer(
    val owner: GameIdentity,
    val kind: TrailerKind,
    val name: String,
    val thumbUrl: String,
    val streamUrl: String,
    val webUrl: String,
    val youtubeId: String = "",
) {
    /** True when there is something the app can actually show the user. */
    val isUsable: Boolean
        get() = when (kind) {
            TrailerKind.DIRECT_VIDEO, TrailerKind.ADAPTIVE_STREAM -> streamUrl.isNotBlank()
            TrailerKind.YOUTUBE -> youtubeId.isNotBlank() || webUrl.isNotBlank()
            TrailerKind.STORE_LINK -> webUrl.isNotBlank()
        }

    /** True when the app can play it itself rather than handing off to another app. */
    val canPlayInApp: Boolean
        get() = when (kind) {
            TrailerKind.DIRECT_VIDEO -> streamUrl.hasMediaExtension("mp4")
            TrailerKind.ADAPTIVE_STREAM -> streamUrl.hasMediaExtension("m3u8")
            else -> false
        }

    /**
     * True when the URL's final path segment ends in [extension], ignoring the query and fragment.
     *
     * Steam appends a cache-busting token to every media URL —
     * `…/hls_264_master.m3u8?t=1716311566` — so a plain `endsWith(".m3u8")` said "no" for the very
     * URLs ExoPlayer plays perfectly well, and every such trailer silently degraded to a
     * browser hand-off.
     */
    private fun String.hasMediaExtension(extension: String): Boolean =
        substringBefore('?').substringBefore('#').trimEnd('/')
            .endsWith(".$extension", ignoreCase = true)

    /** What actually happens when the user taps the trailer. */
    val action: TrailerAction
        get() = when {
            // Nothing to open, so nothing happens — checked first so an unusable entry can never
            // advertise a hand-off to a URL that does not exist.
            !isUsable -> TrailerAction.NONE
            canPlayInApp -> TrailerAction.PLAY_IN_APP
            else -> TrailerAction.OPEN_EXTERNAL
        }

    /** Short, honest label describing the source — shown as the tile's subtitle. */
    val kindLabel: String
        get() = when (kind) {
            TrailerKind.DIRECT_VIDEO -> "Video file"
            TrailerKind.ADAPTIVE_STREAM -> "Adaptive stream"
            TrailerKind.YOUTUBE -> "YouTube"
            TrailerKind.STORE_LINK -> "Store page"
        }

    /** The URL that should be opened when playback is not possible in-app. */
    val externalUrl: String
        get() = when {
            kind == TrailerKind.YOUTUBE && youtubeId.isNotBlank() ->
                "https://www.youtube.com/watch?v=$youtubeId"
            else -> webUrl
        }
}

enum class TrailerAction { PLAY_IN_APP, OPEN_EXTERNAL, NONE }
