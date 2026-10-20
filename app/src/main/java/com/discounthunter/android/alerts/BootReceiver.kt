package com.discounthunter.android.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.discounthunter.android.diagnostics.AppLog

/**
 * Restores the schedule after a reboot.
 *
 * Alarms do not survive a restart, and WorkManager's own schedule *does* — so this is not about
 * the periodic worker, which would come back on its own. It is about the alarm heartbeat, which
 * is the punctual half of the arrangement and would otherwise be silently gone after every
 * reboot.
 *
 * The worker is re-registered as well, because the user's setting is the source of truth for
 * whether monitoring should be running at all: if they turned it off, a reboot must not turn it
 * back on.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        AppLog.i("Alerts: the device started, restoring the check schedule")
        val pending = goAsync()
        val appContext = context.applicationContext
        // The work is queued rather than done inline: on a boot broadcast the device may still be
        // finishing its own start-up, and a network call then would be competing with everything
        // else the system is doing.
        DealAlertScheduler.enqueueNow(appContext)
        DealAlertScheduler.scheduleAlarm(appContext)
        pending.finish()
    }
}
