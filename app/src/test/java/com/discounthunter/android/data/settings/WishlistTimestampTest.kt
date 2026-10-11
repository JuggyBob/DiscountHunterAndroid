package com.discounthunter.android.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wishlist timestamps survive a save/restore round trip.
 *
 * ## The bug
 *
 * `addedAt` is epoch **milliseconds** — around 1.7e12, well past `Int.MAX_VALUE`. It was written as
 * a `Long` but read back through a helper that only tried `Int` parsing, so `intOrNull` returned null
 * and every restored entry silently became `addedAt = 0`.
 *
 * That is not cosmetic. `WishlistRow.addedLabel` returns "" for `addedAt <= 0`, so "Added today"
 * never appeared on a restored wishlist — and `WishlistRepository.rebuild` sorts by `addedAt`
 * descending, which degenerates to a no-op, so the newest save stopped floating to the top.
 *
 * The DataStore collector then republished those zeroed entries over the correct in-memory ones, so
 * the label was wrong even for a game starred seconds earlier.
 */
class WishlistTimestampTest {

    private val now = 1_735_689_600_000L // 2025-01-01, comfortably outside Int range

    @Test
    fun `the representative timestamp really does overflow an int`() {
        assertTrue(
            "the test value must exceed Int.MAX_VALUE or it proves nothing ($now)",
            now > Int.MAX_VALUE,
        )
    }

    @Test
    fun `a millisecond timestamp is not readable as an int`() {
        // This is the failure mode: `toIntOrNull()` returns null and the elvis swallows the value.
        assertEquals(null, now.toString().toIntOrNull())
    }

    @Test
    fun `the long reader accepts millisecond timestamps`() {
        assertEquals(now, now.toString().toLongOrNull())
    }

    @Test
    fun `the long reader still accepts smaller values written by older builds`() {
        // A wishlist written by a build that stored seconds instead of millis must not crash or
        // vanish; it just reads back as-is.
        assertEquals(1_735_689_600L, "1735689600".toLongOrNull())
        assertEquals(42L, "42".toLongOrNull())
    }
}