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
     * existing schedule is left alone rather than restarted on every app launch.
     */
    fun start(context: Context) {
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

        scheduleAlarm(context)
        AppLog.i("Alerts: monitoring scheduled (every $CHECK_INTERVAL_MINUTES min)")
    }

    /** Stops monitoring and cancels anything already queued. */
    fun stop(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK) }
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK) }
        cancelAlarm(context)
        AppLog.i("Alerts: monitoring stopped")
    }

    /**
     * Schedules the next alarm.
     *
     * Called from [DealAlertReceiver] after each run, so the alarm keeps itself alive. The
     * receiver re-arms rather than using a repeating alarm, because a repeating alarm is lost the
     * moment the app is updated and has to be re-armed anyway.
     */
    fun scheduleAlarm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + CHECK_INTERVAL_MINUTES * 60_000L

        val pending = alarmIntent(context)
        try {
            if (canScheduleExact(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                // Inexact, but still delivered inside a Doze window, and it needs no permission.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (error: SecurityException) {
            // The exact-alarm permission was revoked between the check and the call.
            AppLog.w("Alerts: exact alarms are not permitted, falling back to an inexact alarm")
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
        }
    }

    /** Cancels the pending alarm. */
    fun cancelAlarm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { manager.cancel(alarmIntent(context)) }
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

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, DealAlertReceiver::class.java).setAction(ACTION_CHECK)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canScheduleExact(manager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }

    /** The action the alarm carries. */
    const val ACTION_CHECK = "com.discounthunter.android.action.CHECK_DEALS"
    private const val REQUEST_CODE = 7311
}
