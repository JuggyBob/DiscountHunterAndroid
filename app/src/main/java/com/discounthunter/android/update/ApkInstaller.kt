package com.discounthunter.android.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.discounthunter.android.diagnostics.AppLog
import java.io.File

/**
 * Installs a downloaded package through the platform's own package installer.
 *
 * ## Why `PackageInstaller` and not a file URI
 *
 * The obvious way to install an APK is to hand `ACTION_VIEW` an intent pointing at the file and let
 * the system installer take it. That needs the file to be reachable by another application, which
 * means either a world-readable path or a `FileProvider` — and **both are flagged by static
 * analysis as an arbitrary APK installation**, because a URI handed to the installer is by
 * definition a URI some other application could have influenced.
 *
 * `PackageInstaller` sidesteps that entirely. The application writes the package into a session it
 * owns, the session is committed, and the system installs *that* — no URI, no shared file, no path
 * another application can reach. It is also the platform's own recommended API for this, it reports
 * a real result instead of a silent hand-off, and it needs no extra provider in the manifest.
 *
 * ## What it does not do
 *
 * It does not install anything silently. The system still shows its installation confirmation, and
 * on Android 8 and above the user still has to grant the "install unknown apps" permission
 * separately, in system settings. [canRequestInstall] reports whether that permission is held, and
 * the caller is expected to take the user there when it is not.
 */
class ApkInstaller(
    private val context: Context,
) {

    /**
     * True when the app is allowed to request package installation.
     *
     * Always true below Android 8, where the permission did not exist.
     */
    fun canRequestInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * Opens the system screen where the user grants the "install unknown apps" permission.
     *
     * The permission cannot be requested from a dialog — it is a developer setting, and the user
     * has to turn it on for this application specifically. Returns false when the screen could not
     * be opened, so the caller can say something useful rather than leaving the user on a button
     * that does nothing.
     */
    fun openInstallPermissionSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            AppLog.e("Updates: the unknown-sources settings screen could not be opened", error)
            false
        }
    }

    /**
     * Creates an install session for [file] and commits it.
     *
     * Returns false when the session could not be created or written, in which case nothing has
     * been installed and the caller can say so.
     */
    fun install(file: File): Boolean {
        val installer = context.packageManager.packageInstaller
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            // The package being installed is this one. Stating it lets the system refuse an
            // attempt to replace the application with something else.
            params.setAppPackageName(context.packageName)

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                val output = session.openWrite("package", 0, file.length())
                try {
                    file.inputStream().use { input -> input.copyTo(output) }
                    // The bytes have to be flushed to the session before it is committed, or the
                    // system installs a truncated package.
                    session.fsync(output)
                } finally {
                    output.close()
                }
                session.commit(statusIntent(sessionId).intentSender)
            }
            true
        } catch (error: Exception) {
            AppLog.e("Updates: the install session could not be created", error)
            false
        }
    }

    /**
     * The `IntentSender` the system reports the installation result to.
     *
     * The pending intent **must be mutable** from Android 12 onwards: the system fills in the
     * result extras, and a mutable intent is what allows it to.
     */
    private fun statusIntent(sessionId: Int): android.app.PendingIntent {
        val intent = Intent(context, InstallStatusReceiver::class.java).apply {
            action = InstallStatusReceiver.ACTION_STATUS
            putExtra(InstallStatusReceiver.EXTRA_SESSION_ID, sessionId)
        }
        return android.app.PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_MUTABLE,
        )
    }
}
