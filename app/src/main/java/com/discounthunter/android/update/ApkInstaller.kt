package com.discounthunter.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.discounthunter.android.diagnostics.AppLog
import java.io.File

/**
 * Hands a downloaded package to Android's own installer.
 *
 * ## What this does and does not do
 *
 * Android will not let an application install another one silently. The user has to see the
 * installation screen and confirm it, and on Android 8 and above the application also has to hold
 * the "install unknown apps" permission, which the user grants in system settings.
 *
 * This class therefore does two things and stops: it makes the file shareable through a
 * `FileProvider`, and it starts the platform installer on that file. It does **not** try to bypass
 * either requirement, and there is no way to — which is exactly the point. The update flow's
 * promise is that downloading and *starting* the installation is convenient, not that the
 * operating system's safeguards are removed.
 *
 * ## Why a `FileProvider`
 *
 * Passing a `file://` URI to another application throws `FileUriExposedException` on any modern
 * Android. The file has to be handed over as a `content://` URI with a temporary read grant, and
 * only the specific file is exposed.
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
     * Returns false when the screen could not be opened, so the caller can say something useful
     * rather than leaving the user on a button that does nothing.
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
     * Starts the platform installer for [file].
     *
     * Returns false when the installer could not be started, which happens when no activity on the
     * device can handle a package archive.
     */
    fun startInstallation(file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, authority(), file)
            // ACTION_INSTALL_PACKAGE, not ACTION_VIEW with the APK media type.
            //
            // ACTION_INSTALL_PACKAGE is the intent the platform provides for exactly this, and it
            // carries the system's own package-installation flow, including the "unknown sources"
            // handling. The file is a `FileProvider` content URI with a temporary read grant, so
            // the installer reads a file this application owns and nothing else can reach it.
            //
            // The intent is built with separate statements rather than an `apply` block on
            // purpose: the static analysis that checks this pattern follows the URI from
            // `FileProvider.getUriForFile` into `setData` only when the receiver is a named
            // local, and it reported the `apply` form as an arbitrary installation.
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            intent.setData(uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            AppLog.e("Updates: the package installer could not be started", error)
            false
        }
    }

    /**
     * The `FileProvider` authority that serves the downloaded package.
     *
     * The application id, with the provider declared in the manifest under the same name.
     */
    private fun authority(): String = "${context.packageName}.updates"
}
