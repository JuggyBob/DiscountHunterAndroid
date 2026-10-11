package com.discounthunter.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Identity parsing.
 *
 * This is the root of every media-correctness guarantee in the app: a screenshot is only ever shown
 * when its owner's identity is in the accepted set for the opened game. So a parser that quietly
 * invents an identity — rather than refusing one — is not a cosmetic problem, it is the defect the
 * whole design exists to prevent.
 */
class GameIdentityTest {

    // ------------------------------------------------------------------ fromUrl

    @Test
    fun `a steam app url resolves to that app id`() {
        assertEquals(
            GameIdentity.steam(1245620),
            GameIdentity.fromUrl("https://store.steampowered.com/app/1245620/ELDEN_RING/"),
        )
    }

    @Test
    fun `a steam app url without a trailing path still resolves`() {
        assertEquals(
            GameIdentity.steam(1245620),
            GameIdentity.fromUrl("https://store.steampowered.com/app/1245620"),
        )
    }

    @Test
    fun `a steam age gate url resolves`() {
        assertEquals(
            GameIdentity.steam(379720),
            GameIdentity.fromUrl("https://store.steampowered.com/app/379720/DOOM/?agecheckage=1-January-1990"),
        )
    }

    @Test
    fun `a gog game url resolves to its slug`() {
        assertEquals(
            GameIdentity.gog("elden_ring"),
            GameIdentity.fromUrl("https://www.gog.com/en/game/elden_ring"),
        )
    }

    @Test
    fun `a gog url with a query string keeps only the slug`() {
        assertEquals(
            GameIdentity.gog("elden_ring"),
            GameIdentity.fromUrl("https://www.gog.com/en/game/elden_ring#offers"),
        )
    }

    @Test
    fun `an epic product url resolves to its slug`() {
        assertEquals(
            GameIdentity.epic("jotun"),
            GameIdentity.fromUrl("https://store.epicgames.com/en-US/p/jotun"),
        )
    }

    @Test
    fun `a url for another site resolves to nothing`() {
        assertNull(GameIdentity.fromUrl("https://example.com/app/1245620"))
        assertNull(GameIdentity.fromUrl("https://store.steampowered.com/"))
        assertNull(GameIdentity.fromUrl(""))
    }

    @Test
    fun `a non numeric steam app id is rejected rather than guessed at`() {
        assertNull(GameIdentity.fromUrl("https://store.steampowered.com/app/steam/"))
        assertNull(GameIdentity.fromUrl("https://store.steampowered.com/app/0/"))
        assertNull(GameIdentity.fromUrl("https://store.steampowered.com/app/-5/"))
    }

    // ------------------------------------------------------------------ parse

    @Test
    fun `a route argument parses back to the same identity`() {
        val original = requireNotNull(GameIdentity.steam(1245620))
        assertEquals(original, GameIdentity.parse(original.key))
    }

    @Test
    fun `gog and epic route arguments parse too`() {
        assertEquals(GameIdentity(StoreId.GOG, "elden_ring"), GameIdentity.parse("gog:elden_ring"))
        assertEquals(GameIdentity(StoreId.EPIC, "jotun"), GameIdentity.parse("EPIC:jotun"))
    }

    @Test
    fun `an argument with no store separator is rejected`() {
        // The bug this pins down: "steam" used to parse as store STEAM with the id "steam",
        // producing a detail page for a game that does not exist.
        assertNull(GameIdentity.parse("steam"))
        assertNull(GameIdentity.parse("gog"))
        assertNull(GameIdentity.parse("epic"))
    }

    @Test
    fun `an argument with an unknown store or a blank id is rejected`() {
        assertNull(GameIdentity.parse("nintendo:1245620"))
        assertNull(GameIdentity.parse("steam:"))
        assertNull(GameIdentity.parse(":1245620"))
        assertNull(GameIdentity.parse(null))
        assertNull(GameIdentity.parse("   "))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(GameIdentity.steam(1245620), GameIdentity.parse("  steam:1245620 "))
    }

    // ------------------------------------------------------------------ key

    @Test
    fun `the key is lower case and stable`() {
        assertEquals("gog:elden_ring", requireNotNull(GameIdentity.gog("Elden_Ring")).key)
        assertEquals("steam:1245620", requireNotNull(GameIdentity.steam(1245620)).key)
    }

    @Test
    fun `slugs are reduced to their last segment`() {
        assertEquals("elden_ring", requireNotNull(GameIdentity.gog("/games/elden_ring/")).id)
        assertEquals("jotun", requireNotNull(GameIdentity.epic("https://x/p/jotun")).id)
    }

    @Test
    fun `a blank slug is not an identity`() {
        assertNull(GameIdentity.gog(""))
        assertNull(GameIdentity.gog("  "))
        assertNull(GameIdentity.epic("/"))
        assertNull(GameIdentity.steam(0))
        assertNull(GameIdentity.steam(-1))
    }

    @Test
    fun `identities are compared by store and id, not by formatting`() {
        assertEquals(GameIdentity(StoreId.GOG, "elden_ring"), GameIdentity(StoreId.GOG, "elden_ring"))
        assertEquals(
            GameIdentity(StoreId.GOG, "elden_ring").hashCode(),
            GameIdentity(StoreId.GOG, "elden_ring").hashCode(),
        )
    }
}
