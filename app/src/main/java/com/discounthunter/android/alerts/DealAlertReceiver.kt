package com.discounthunter.android.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.discounthunter.android.diagnostics.AppLog

/**
 * Receives the periodic alarm and turns it into a queued check.
 *
 * The receiver does no work of its own beyond handing the job to WorkManager. That is deliberate:
 * a broadcast receiver has about ten seconds before the system treats it as hung, and fetching a
 * live feed from three stores takes longer than that. Handing over immediately means the check
 * runs under WorkManager's background execution guarantees instead.
 *
 * The next alarm is armed as the receiver runs, so the chain continues without the app ever being
 * opened.
 */
class DealAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DealAlertScheduler.ACTION_CHECK) return
        AppLog.i("Alerts: the alarm fired")
        // Re-arm first. If the process is killed between here and the work being queued, the next
        // alarm is already in place.
        DealAlertScheduler.scheduleAlarm(context)
        DealAlertScheduler.enqueueNow(context)
    }
}
