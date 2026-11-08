package com.discounthunter.android.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the next alarm is armed.
 *
 * This rule exists because of a real background failure: `AppContainer.start()` ran on every
 * process creation, and each run pushed the alarm an hour into the future. WorkManager creates a
 * process for its periodic worker, so the alarm was reset by the very thing that was supposed to
 * be its backstop. On a phone whose battery manager defers periodic work, that left no punctual
 * path at all — which is exactly "notifications do not work when the app is closed".
 */
class AlarmScheduleTest {

    private val interval = DealAlertScheduler.CHECK_INTERVAL_MINUTES * 60_000L

    /** A realistic epoch-millis instant: negative values are not wall-clock times. */
    private val now = 1_794_000_000_000L

    @Test
    fun `an alarm that is not pending is armed`() {
        assertTrue(
            AlarmSchedule.shouldArm(
                now = now,
                nextAlarmAt = 0L,
                hasPendingAlarm = false,
                force = false,
                intervalMillis = interval,
            ),
        )
    }

    @Test
    fun `an alarm that is already armed is left alone`() {
        assertFalse(
            AlarmSchedule.shouldArm(
                now = now,
                nextAlarmAt = now + 10 * 60_000L,
                hasPendingAlarm = true,
                force = false,
                intervalMillis = interval,
            ),
        )
    }

    @Test
    fun `a slightly late alarm is not reset`() {
        // Doze can deliver an alarm well after its due time. Re-arming then would push it another
        // full interval away, which is the bug, not the fix.
        assertFalse(
            AlarmSchedule.shouldArm(
                now = now,
                nextAlarmAt = now - interval,
                hasPendingAlarm = true,
                force = false,
                intervalMillis = interval,
            ),
        )
    }

    @Test
    fun `an alarm that is many intervals overdue is re-armed`() {
        assertTrue(
            AlarmSchedule.shouldArm(
                now = now,
                nextAlarmAt = now - (AlarmSchedule.STALE_AFTER_INTERVALS + 1) * interval,
                hasPendingAlarm = true,
                force = false,
                intervalMillis = interval,
            ),
        )
    }

    @Test
    fun `a forced arm always happens`() {
        assertTrue(
            AlarmSchedule.shouldArm(
                now = now,
                nextAlarmAt = now + interval,
                hasPendingAlarm = true,
                force = true,
                intervalMillis = interval,
            ),
        )
    }

    @Test
    fun `a pending alarm with no stored time is left alone`() {
        // The platform has it, and the receiver will arm the next one when it fires. Re-arming on
        // unknown information would reintroduce the push-back this rule exists to prevent.
        assertFalse(
            AlarmSchedule.shouldArm(
                now = now,
                nextAlarmAt = 0L,
                hasPendingAlarm = true,
                force = false,
                intervalMillis = interval,
            ),
        )
    }
}
