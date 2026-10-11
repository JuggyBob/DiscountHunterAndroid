package com.discounthunter.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trailer delivery rules.
 *
 * The point of these is honesty: the app must only show a play control when it can actually play
 * the thing, and must only offer a hand-off when there is a hand-off to offer.
 */
class TrailerTest {

    private fun trailer(
        kind: TrailerKind,
        streamUrl: String = "",
        webUrl: String = "https://store.steampowered.com/app/1245620/",
        youtubeId: String = "",
    ) = Trailer(
        owner = requireNotNull(GameIdentity.steam(1245620)),
        kind = kind,
        name = "Trailer",
        thumbUrl = "https://cdn.steamstatic.com/t.jpg",
        streamUrl = streamUrl,
        webUrl = webUrl,
        youtubeId = youtubeId,
    )

    // ---------------------------------------------------------------- playable

    @Test
    fun `a progressive mp4 plays in app`() {
        val trailer = trailer(TrailerKind.DIRECT_VIDEO, "https://cdn.steamstatic.com/t.mp4")

        assertTrue(trailer.canPlayInApp)
        assertEquals(TrailerAction.PLAY_IN_APP, trailer.action)
        assertEquals("Video file", trailer.kindLabel)
    }

    @Test
    fun `an hls manifest plays in app`() {
        val trailer = trailer(TrailerKind.ADAPTIVE_STREAM, "https://video.steamstatic.com/master.m3u8")

        assertTrue(trailer.canPlayInApp)
        assertEquals(TrailerAction.PLAY_IN_APP, trailer.action)
        assertEquals("Adaptive stream", trailer.kindLabel)
    }

    @Test
    fun `a cache busting token does not hide a playable stream`() {
        // Steam appends ?t=<epoch> to every media URL. Judging playability by the raw string ending
        // made every one of those trailers look unplayable, when ExoPlayer plays them fine.
        assertTrue(
            trailer(
                TrailerKind.ADAPTIVE_STREAM,
                "https://video.akamai.steamstatic.com/store_trailers/1245620/x/hls_264_master.m3u8?t=1716311566",
            ).canPlayInApp,
        )
        assertTrue(
            trailer(TrailerKind.DIRECT_VIDEO, "https://cdn.steamstatic.com/t.mp4?token=abc#t=3").canPlayInApp,
        )
        assertTrue(trailer(TrailerKind.DIRECT_VIDEO, "https://cdn.steamstatic.com/t.MP4").canPlayInApp)
    }

    @Test
    fun `a stream url that is not a media file is not treated as playable`() {
        // An .mpd (MPEG-DASH) is not playable without another Media3 module, so offering a player
        // would be a lie.
        assertFalse(trailer(TrailerKind.ADAPTIVE_STREAM, "https://cdn.steamstatic.com/a.mpd").canPlayInApp)
        assertFalse(trailer(TrailerKind.DIRECT_VIDEO, "https://cdn.steamstatic.com/page.html").canPlayInApp)
        assertFalse(trailer(TrailerKind.DIRECT_VIDEO, "").canPlayInApp)
    }

    // ---------------------------------------------------------------- hand-offs

    @Test
    fun `a trailer with no stream is a hand-off to the store page`() {
        val trailer = trailer(TrailerKind.STORE_LINK, streamUrl = "")

        assertFalse(trailer.canPlayInApp)
        assertEquals(TrailerAction.OPEN_EXTERNAL, trailer.action)
        assertEquals("Store page", trailer.kindLabel)
        assertEquals("https://store.steampowered.com/app/1245620/", trailer.externalUrl)
        assertTrue(trailer.isUsable)
    }

    @Test
    fun `a youtube trailer is handed off with its watch url`() {
        val trailer = trailer(TrailerKind.YOUTUBE, youtubeId = "abc123")

        assertFalse(trailer.canPlayInApp)
        assertEquals(TrailerAction.OPEN_EXTERNAL, trailer.action)
        assertEquals("https://www.youtube.com/watch?v=abc123", trailer.externalUrl)
        assertEquals("YouTube", trailer.kindLabel)
    }

    @Test
    fun `a trailer with nothing at all is not usable and does nothing`() {
        val trailer = trailer(TrailerKind.STORE_LINK, streamUrl = "", webUrl = "")

        assertFalse(trailer.isUsable)
        assertEquals(TrailerAction.NONE, trailer.action)
    }

    @Test
    fun `a youtube id alone is enough to hand off`() {
        val trailer = trailer(TrailerKind.YOUTUBE, webUrl = "", youtubeId = "abc")

        assertTrue(trailer.isUsable)
        assertEquals(TrailerAction.OPEN_EXTERNAL, trailer.action)
        assertEquals("https://www.youtube.com/watch?v=abc", trailer.externalUrl)
    }

    @Test
    fun `a youtube trailer with neither an id nor a url does nothing`() {
        val trailer = trailer(TrailerKind.YOUTUBE, webUrl = "", youtubeId = "")

        assertFalse(trailer.isUsable)
        assertEquals(TrailerAction.NONE, trailer.action)
    }
}
