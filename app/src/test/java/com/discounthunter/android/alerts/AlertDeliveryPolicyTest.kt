package com.discounthunter.android.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a check may record what it saw as announced.
 *
 * If the app detected a sale but could not post the notification, the record must not advance:
 * otherwise the news is consumed silently and granting the permission later brings nothing back.
 */
class AlertDeliveryPolicyTest {

    @Test
    fun `nothing to announce means the state is still recorded`() {
        assertTrue(
            AlertDeliveryPolicy.shouldAdvanceState(
                hasAnythingToAnnounce = false,
                posted = 0,
                previouslyInitialised = true,
            ),
        )
    }

    @Test
    fun `a posted announcement advances the state`() {
        assertTrue(
            AlertDeliveryPolicy.shouldAdvanceState(
                hasAnythingToAnnounce = true,
                posted = 1,
                previouslyInitialised = true,
            ),
        )
    }

    @Test
    fun `an announcement that could not be posted is left pending`() {
        assertFalse(
            AlertDeliveryPolicy.shouldAdvanceState(
                hasAnythingToAnnounce = true,
                posted = 0,
                previouslyInitialised = true,
            ),
        )
    }

    @Test
    fun `the first run establishes a baseline even when nothing was posted`() {
        // The first run has no announcements by definition. Without the baseline, every later run
        // would also look like a first run and announce nothing.
        assertTrue(
            AlertDeliveryPolicy.shouldAdvanceState(
                hasAnythingToAnnounce = false,
                posted = 0,
                previouslyInitialised = false,
            ),
        )
    }
}
