package com.discounthunter.android.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the notification permission prompt is shown.
 *
 * The defect this guards against: deal alerts default to on, but on Android 13+ the notification
 * permission starts denied, and the app only asked when the switch was turned *on*. A fresh
 * install therefore never asked, and its background checks could never show anything.
 */
class NotificationPermissionPolicyTest {

    @Test
    fun `a fresh install with alerts on asks once`() {
        assertTrue(
            NotificationPermissionPolicy.shouldPrompt(
                apiLevel = 33,
                alertsEnabled = true,
                canPost = false,
                alreadyPrompted = false,
            ),
        )
    }

    @Test
    fun `a user who has already been asked is not asked again`() {
        assertFalse(
            NotificationPermissionPolicy.shouldPrompt(
                apiLevel = 33,
                alertsEnabled = true,
                canPost = false,
                alreadyPrompted = true,
            ),
        )
    }

    @Test
    fun `nothing is asked when notifications already work`() {
        assertFalse(
            NotificationPermissionPolicy.shouldPrompt(
                apiLevel = 34,
                alertsEnabled = true,
                canPost = true,
                alreadyPrompted = false,
            ),
        )
    }

    @Test
    fun `nothing is asked when the feature is off`() {
        assertFalse(
            NotificationPermissionPolicy.shouldPrompt(
                apiLevel = 33,
                alertsEnabled = false,
                canPost = false,
                alreadyPrompted = false,
            ),
        )
    }

    @Test
    fun `nothing is asked below Android 13`() {
        // There is no runtime notification permission before API 33; the permission is granted at
        // install time, and the only way to remove it is in system settings.
        assertFalse(
            NotificationPermissionPolicy.shouldPrompt(
                apiLevel = 32,
                alertsEnabled = true,
                canPost = false,
                alreadyPrompted = false,
            ),
        )
    }

    @Test
    fun `the system dialog can be shown on Android 13 and above`() {
        assertTrue(NotificationPermissionPolicy.canRequestFromApp(33, canPost = false))
        assertTrue(NotificationPermissionPolicy.canRequestFromApp(35, canPost = false))
        assertFalse(NotificationPermissionPolicy.canRequestFromApp(33, canPost = true))
        assertFalse(NotificationPermissionPolicy.canRequestFromApp(32, canPost = false))
    }

    @Test
    fun `the settings warning is exactly the unusable state`() {
        assertTrue(NotificationPermissionPolicy.shouldWarn(alertsEnabled = true, canPost = false))
        assertFalse(NotificationPermissionPolicy.shouldWarn(alertsEnabled = true, canPost = true))
        assertFalse(NotificationPermissionPolicy.shouldWarn(alertsEnabled = false, canPost = false))
    }
}
