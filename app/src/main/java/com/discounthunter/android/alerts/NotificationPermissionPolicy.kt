package com.discounthunter.android.alerts

import android.os.Build

/**
 * Whether the app should ask for the notification permission.
 *
 * ## Why this is a separate, pure decision
 *
 * The permission only exists from Android 13 (API 33), and it is denied on a fresh install. The
 * deal-alerts feature is enabled by default, so without a request the app schedules background
 * checks that can never show anything: the setting looks on, the work runs, and nothing arrives.
 * That was the state a fresh install was in.
 *
 * Asking must also be *once*: a user who has declined should not be asked again every time they
 * open the app. The answer is remembered in settings, and the Settings screen keeps a manual way
 * to grant the permission later.
 *
 * The logic is here rather than in the composable so it can be tested on the JVM without an
 * Activity, which is where permission bugs usually hide.
 */
object NotificationPermissionPolicy {

    /** The first API level with a runtime notification permission. */
    const val RUNTIME_PERMISSION_API = Build.VERSION_CODES.TIRAMISU

    /**
     * True when the one-time explanation should be shown.
     *
     * @param apiLevel the device's API level (`Build.VERSION.SDK_INT`).
     * @param alertsEnabled whether the user has deal alerts switched on.
     * @param canPost whether notifications are currently allowed.
     * @param alreadyPrompted whether the explanation has been shown before.
     */
    fun shouldPrompt(
        apiLevel: Int,
        alertsEnabled: Boolean,
        canPost: Boolean,
        alreadyPrompted: Boolean,
    ): Boolean =
        apiLevel >= RUNTIME_PERMISSION_API &&
            alertsEnabled &&
            !canPost &&
            !alreadyPrompted

    /**
     * True when the app can still show the system permission dialog itself.
     *
     * Below Android 13 there is no runtime permission to request: if notifications are off, the
     * only place to change that is the system settings screen. On 13 and above the dialog can be
     * shown again; if the user has permanently denied it, Android answers immediately without a
     * dialog, and the Settings screen's "Open settings" action is the way back.
     */
    fun canRequestFromApp(apiLevel: Int, canPost: Boolean): Boolean =
        apiLevel >= RUNTIME_PERMISSION_API && !canPost

    /** True when the Settings screen should warn that the feature cannot post anything. */
    fun shouldWarn(alertsEnabled: Boolean, canPost: Boolean): Boolean =
        alertsEnabled && !canPost
}
