package com.discounthunter.android.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.discounthunter.android.diagnostics.AppLog
import java.util.concurrent.TimeUnit

/**
 * Decides when the deal check happens.
 *
 * ## Two mechanisms, on purpose
 *
 * The single most common reason an app's notifications "do not always work" is that it relies on
 * **one** mechanism, and that mechanism has a hole:
 *
 *  * **WorkManager** is the officially supported way to do background work, and it survives
 *    reboots. But a *periodic* worker is a request, not a promise: the system batches and defers
 *    it, Doze pushes it out, and several manufacturers' battery managers delay it further. A
 *    notification that arrives four hours late is the inconsistency users complain about.
 *  * **AlarmManager** is punctual, and `setExactAndAllowWhileIdle` is delivered inside a Doze
 *    maintenance window rather than being batched. But alarms do not survive a reboot on their own,
 *    and an app that is force-stopped loses them.
 *
 * So both are used. The alarm is the **punctual** path and WorkManager's periodic worker is the
 * **guaranteed** backstop, and both end up enqueueing the same idempotent worker. Whichever fires
 * first does the work; the other is a no-op, because the state that decides what to announce is
 * written as part of the same run.
 *
 * ## The exact-alarm permission
 *
 * `SCHEDULE_EXACT_ALARM` is needed for `setExactAndAllowWhileIdle` on Android 12 and above, and
 * the user can refuse it. Rather than treating that as a failure, the scheduler asks
 * [AlarmManager.canScheduleExactAlarms] and falls back to `setAndAllowWhileIdle`, which needs no
 * permission and still works inside Doze. A less punctual notification is much better than none.
 */
object DealAlertScheduler {

    /** The name of the periodic worker. Unique, so it is never scheduled twice. */
    const val PERIODIC_WORK = "discount_hunter_deal_check"

    /** The name of the one-off run the alarm triggers. */
    const val ONE_TIME_WORK = "discount_hunter_deal_check_now"

    /** How often the check should run. */
    const val CHECK_INTERVAL_MINUTES = 60L

    /**
     * Starts monitoring.
     *
     * Safe to call repeatedly: the periodic worker uses [ExistingPeriodicWorkPolicy.KEEP], so an
     * existing schedule is left alone rather than restarted on every app launch, and
     * [scheduleAlarm] leaves an alarm that is already armed where it is.
     *
     * [forceAlarm] is for the two moments when the alarm is genuinely gone: a reboot, and an app
     * replacement. Both clear every pending alarm, so restoring one is not "resetting" it.
     */
    fun start(context: Context, forceAlarm: Boolean = false) {
        val workManager = WorkManager.getInstance(context)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<DealAlertWorker>(
            CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES,
            // A flex window lets the system pick the best moment inside the interval, which is
            // both kinder to the battery and better for Doze.
            15, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .addTag(PERIODIC_WORK)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )

        scheduleAlarm(context, force = forceAlarm)
        AppLog.i("Alerts: monitoring scheduled (every $CHECK_INTERVAL_MINUTES min)")
    }

    /** Stops monitoring and cancels anything already queued. */
    fun stop(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK) }
        // The one-time checks are enqueued under a tag rather than a unique name (see
        // [enqueueNow]), so cancelling them has to be by tag. `cancelUniqueWork` would silently
        // cancel nothing here.
        runCatching { WorkManager.getInstance(context).cancelAllWorkByTag(ONE_TIME_WORK) }
        cancelAlarm(context)
        AppLog.i("Alerts: monitoring stopped")
    }

    /**
     * Schedules the next alarm.
     *
     * ## Why an existing alarm is left alone
     *
     * The alarm is a chain: each firing re-arms the next one. It must therefore never be pushed
     * back by anything other than a firing. This used to be called unconditionally from
     * [AppContainer.start], which runs on **every process creation** — including the ones
     * WorkManager creates for its periodic worker. Each of those starts moved the alarm another
     * hour into the future, so on a device that was woken often enough the punctual half of the
     * schedule never fired at all. Only the deferrable periodic worker was left, which is exactly
     * the "no notification when the app is closed" complaint on battery-managed phones.
     *
     * [force] is used by the receiver after a firing (the chain must continue) and by the boot and
     * package-replaced receivers (the system clears alarms at both moments).
     */
    fun scheduleAlarm(context: Context, force: Boolean = false) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val nextAt = storedNextAlarmAt(context)
        val pending = pendingAlarm(context)

        if (!AlarmSchedule.shouldArm(now, nextAt, pending != null, force)) {
            AppLog.i("Alerts: the next check is already armed; leaving it alone")
            return
        }

        val triggerAt = now + CHECK_INTERVAL_MINUTES * 60_000L
        val armed = arm(manager, triggerAt, pending ?: createAlarmIntent(context))
        if (armed) {
            // Remembered so the next process start can tell "armed and waiting" from "lost".
            schedulePrefs(context).edit().putLong(KEY_NEXT_ALARM_AT, triggerAt).apply()
        }
    }

    /** Cancels the pending alarm. */
    fun cancelAlarm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        pendingAlarm(context)?.let { runCatching { manager.cancel(it) } }
        schedulePrefs(context).edit().remove(KEY_NEXT_ALARM_AT).apply()
    }

    /**
     * When the next alarm should fire, or null when none is armed.
     *
     * Used by the Settings screen to say when the next check is due, so a user who is wondering
     * whether the feature is alive has something concrete to look at.
     */
    fun nextAlarmAt(context: Context): Long? = storedNextAlarmAt(context).takeIf { it > 0L }

    /** Arms the alarm, exact when permitted and inexact when not. Returns true when it took. */
    private fun arm(manager: AlarmManager, triggerAt: Long, pending: PendingIntent): Boolean {
        return try {
            if (canScheduleExact(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                // Inexact, but still delivered inside a Doze window, and it needs no permission.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            true
        } catch (error: SecurityException) {
            // The exact-alarm permission was revoked between the check and the call.
            AppLog.w("Alerts: exact alarms are not permitted, falling back to an inexact alarm")
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
                .isSuccess
        } catch (error: Exception) {
            AppLog.e("Alerts: the next check could not be armed", error)
            false
        }
    }

    /**
     * Queues the check to run now.
     *
     * The work goes through WorkManager rather than being done inside the receiver, because a
     * broadcast receiver has roughly ten seconds before the system considers it hung, and a store
     * feed takes longer than that. WorkManager runs it properly, off the main thread, with the
     * process allowed to be started for it.
     *
     * ## Why this is not a unique work name
     *
     * Both obvious policies are wrong, and both were wrong in practice before this was settled:
     *
     *  * `REPLACE` cancels whatever is already running. The alarm and the periodic worker can
     *    fire within seconds of each other, and that turned into a cancellation storm where each
     *    new request killed the one that had just started, so no check ever completed.
     *  * `KEEP` never cancels, but a work that is stuck in a queued state — waiting for a network
     *    that is not coming, say — blocks every later request under the same name. The checks
     *    then stop happening, silently, and nothing says why.
     *
     * So the request is simply enqueued. Overlapping runs are harmless: the notification for a
     * game is posted under that game's own tag and id, so a second post *replaces* the first
     * rather than adding to the shade. The user sees one notification either way.
     */
    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<DealAlertWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .addTag(ONE_TIME_WORK)
            .build()

        runCatching { WorkManager.getInstance(context).enqueue(request) }
            .onFailure { AppLog.e("Alerts: could not queue a check", it) }
    }

    private fun createAlarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, DealAlertReceiver::class.java).setAction(ACTION_CHECK)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The alarm this app already has pending, or null when there is none.
     *
     * `FLAG_NO_CREATE` asks for the existing token rather than creating one, which is what makes
     * it possible to tell "armed" from "lost" without keeping a second copy of the truth.
     */
    private fun pendingAlarm(context: Context): PendingIntent? {
        val intent = Intent(context, DealAlertReceiver::class.java).setAction(ACTION_CHECK)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun schedulePrefs(context: Context) =
        context.getSharedPreferences(SCHEDULE_PREFS, Context.MODE_PRIVATE)

    private fun storedNextAlarmAt(context: Context): Long =
        schedulePrefs(context).getLong(KEY_NEXT_ALARM_AT, 0L)

    private fun canScheduleExact(manager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }

    /** The action the alarm carries. */
    const val ACTION_CHECK = "com.discounthunter.android.action.CHECK_DEALS"
    private const val REQUEST_CODE = 7311
    private const val SCHEDULE_PREFS = "deal_alert_schedule"
    private const val KEY_NEXT_ALARM_AT = "next_alarm_at"
}

/**
 * The decision of whether the next alarm needs to be armed.
 *
 * Pure and separate from [DealAlertScheduler] so it can be tested without an `AlarmManager`. The
 * rule is deliberately conservative: an alarm that is armed is left alone unless it is provably
 * gone, or so far overdue that the chain must have broken.
 */
object AlarmSchedule {

    /**
     * How many intervals past its due time an alarm may be before it is treated as lost.
     *
     * A pending alarm can legitimately be late: Doze defers even `setExactAndAllowWhileIdle`, and a
     * device that was asleep for an hour may deliver it well after the time it was set for.
     * Resetting it then would delay it by another full interval, which is the opposite of what is
     * wanted. Two intervals is late enough to be suspicious, early enough to recover.
     */
    const val STALE_AFTER_INTERVALS = 2

    /**
     * True when [scheduleAlarm][DealAlertScheduler.scheduleAlarm] should call the AlarmManager.
     *
     * @param now the current wall-clock time in epoch millis.
     * @param nextAlarmAt when the stored alarm is due, or 0 when that is not known.
     * @param hasPendingAlarm whether the platform still has the `PendingIntent`.
     * @param force true when the caller knows the alarm was cleared (a reboot or an app update).
     * @param intervalMillis the normal gap between checks.
     */
    fun shouldArm(
        now: Long,
        nextAlarmAt: Long,
        hasPendingAlarm: Boolean,
        force: Boolean,
        intervalMillis: Long = DealAlertScheduler.CHECK_INTERVAL_MINUTES * 60_000L,
    ): Boolean {
        if (force) return true
        if (!hasPendingAlarm) return true
        // Armed, but the stored time is missing: leave the platform's copy alone. The receiver
        // re-arms when it fires, and WorkManager is the backstop if it never does.
        if (nextAlarmAt <= 0L) return false
        return now - nextAlarmAt > intervalMillis * STALE_AFTER_INTERVALS
    }
}
