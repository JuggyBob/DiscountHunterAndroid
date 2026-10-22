package com.discounthunter.android.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.discounthunter.android.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Receives the outcome of an installation.
 *
 * The result arrives as a broadcast from the system, and the application's own copy of it is
 * published on [InstallResults] so the About dialog can say whether the update actually went
 * through.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.i("Updates: the update was installed (session $sessionId)")
                InstallResults.completed(message)
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system wants the user to confirm. It raises the confirmation itself, from
                // the intent this broadcast carries, so there is nothing for the app to do but
                // report that a confirmation is pending.
                AppLog.i("Updates: the installation is waiting for the user to confirm")
                InstallResults.awaitingConfirmation(message)
            }

            PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppLog.w("Updates: the installation failed: $message")
                InstallResults.failed(message)
            }

            else -> AppLog.i("Updates: the installation reported status $status")
        }
    }

    companion object {
        const val ACTION_STATUS = "com.discounthunter.android.action.INSTALL_STATUS"
        const val EXTRA_SESSION_ID = "session_id"
    }
}

/**
 * The outcome of the last installation attempt.
 *
 * A process-wide flow rather than something persisted: the result is about *this* installation,
 * and it is only interesting while the user is looking at the screen that started it.
 */
object InstallResults {

    /** The states an installation can be in. */
    sealed interface State {
        /** The system is waiting for the user to confirm the installation. */
        data object AwaitingConfirmation : State

        /** The package was installed. */
        data object Completed : State

        /** The installation failed. */
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State?>(null)

    /** The outcome, or null when no installation has been attempted in this session. */
    val state: StateFlow<State?> = _state.asStateFlow()

    /** Forgets the previous outcome, so a new attempt does not show the old one. */
    fun reset() {
        _state.value = null
    }

    internal fun awaitingConfirmation(reason: String) {
        _state.value = State.AwaitingConfirmation
    }

    internal fun completed(reason: String) {
        _state.value = State.Completed
    }

    internal fun failed(reason: String) {
        _state.value = State.Failed(reason)
    }
}
