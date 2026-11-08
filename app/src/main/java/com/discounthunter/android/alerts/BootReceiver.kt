package com.discounthunter.android.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.discounthunter.android.DiscountHunterApplication
import com.discounthunter.android.diagnostics.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Restores the schedule after a reboot or an app update.
 *
 * Alarms do not survive either event, and WorkManager's own schedule *does* survive both — so this
 * is not about the periodic worker, which would come back on its own. It is about the alarm
 * heartbeat, which is the punctual half of the arrangement and would otherwise be silently gone
 * after every reboot.
 *
 * The user's setting decides whether anything is restored. A reboot must never switch monitoring
 * back on for someone who deliberately turned it off, and it must not keep waking the device every
 * hour for a feature that is not running.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        AppLog.i("Alerts: the device started, restoring the check schedule")
        val app = context.applicationContext as? DiscountHunterApplication
        val container = app?.container
        if (app == null || container == null) {
            // The container is created in Application.onCreate, which runs before a manifest
            // receiver. This is therefore an unexpected state, not a normal one; the periodic
            // worker is persisted and will still run.
            AppLog.w("Alerts: the container is not ready, so the schedule was not restored")
            return
        }

        // The work is queued rather than done inline: on a boot broadcast the device may still be
        // finishing its own start-up, and a network call then would be competing with everything
        // else the system is doing. `goAsync` gives the DataStore read the same window the
        // broadcast itself has.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val enabled = runCatching {
                    // The broadcast has about ten seconds. A DataStore read is normally
                    // instantaneous, but this is a boot-time broadcast competing with the rest of
                    // the system's start-up, so it gets a bound rather than the whole window.
                    withTimeoutOrNull(8_000) { container.settings.settings.first() }
                }.getOrNull()?.saleAlertsEnabled
                when (enabled) {
                    true -> {
                        AlertNotifier(app, container.fxRates).ensureChannels()
                        // Forced: the system cleared every alarm when it rebooted or replaced the
                        // package, so there is nothing here to push back.
                        DealAlertScheduler.start(app, forceAlarm = true)
                        // Check soon rather than waiting a full interval: the device may have been
                        // off for days, and a sale could have started and be about to end.
                        DealAlertScheduler.enqueueNow(app)
                        AppLog.i("Alerts: schedule restored after a restart")
                    }

                    false -> {
                        DealAlertScheduler.stop(app)
                        AppLog.i("Alerts: monitoring is off, so nothing was restored")
                    }

                    null -> AppLog.w("Alerts: the setting could not be read, leaving the schedule alone")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
