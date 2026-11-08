package com.discounthunter.android.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discounthunter.android.R
import com.discounthunter.android.alerts.AlertNotifier
import com.discounthunter.android.alerts.DealAlertScheduler
import com.discounthunter.android.alerts.NotificationPermissionPolicy
import com.discounthunter.android.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Explains why the app needs to notify, then asks for the permission once.
 *
 * ## Why this exists
 *
 * Deal alerts are on by default, and on Android 13 and above the notification permission is denied
 * until it is asked for. The app used to ask only when the user turned the switch **on** — but it
 * already was on, so a fresh install never asked, and its background checks could never show
 * anything. The feature looked enabled and did nothing.
 *
 * The prompt is deliberately shown after the intro, in the app itself, rather than as a raw system
 * dialog on first launch: the system dialog has no room to say *what* the permission is for. It is
 * shown once, and "Not now" is remembered. The Settings screen keeps a manual "Allow notifications"
 * action, so declining here is not permanent.
 */
@Composable
fun NotificationPermissionPrompt(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)

    var canPost by remember { mutableStateOf(AlertNotifier(container.appContext).canPost()) }

    // The permission can be changed in system settings while the app is in the background. Re-read
    // it on every resume so the dialog and the Settings warning reflect reality rather than the
    // state they happened to see when they were composed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canPost = AlertNotifier(container.appContext).canPost()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        canPost = AlertNotifier(container.appContext).canPost()
        if (granted) {
            // The channels may not exist yet if the first check has never run.
            AlertNotifier(container.appContext).ensureChannels()
            // Check soon rather than waiting for the next alarm: if a sale was detected while the
            // permission was missing, the record deliberately still holds it, and this is the
            // moment it can finally be announced.
            DealAlertScheduler.enqueueNow(container.appContext)
        }
    }

    val markPrompted: () -> Unit = {
        scope.launch { container.settings.setNotificationPermissionPromptShown() }
        Unit
    }

    val current = settings ?: return
    val shouldShow = NotificationPermissionPolicy.shouldPrompt(
        apiLevel = Build.VERSION.SDK_INT,
        alertsEnabled = current.saleAlertsEnabled,
        canPost = canPost,
        alreadyPrompted = current.hasPromptedNotificationPermission,
    )
    if (!shouldShow) return

    AlertDialog(
        onDismissRequest = { markPrompted() },
        title = { Text(stringResource(R.string.alert_permission_title)) },
        text = {
            Text(
                text = stringResource(R.string.alert_permission_rationale),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Recorded before the system dialog opens, so a configuration change cannot
                    // show this explanation a second time.
                    markPrompted()
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            ) {
                Text(stringResource(R.string.alert_permission_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = { markPrompted() }) {
                Text(stringResource(R.string.alert_permission_not_now))
            }
        },
    )
}
