package com.discounthunter.android.update

import android.content.Context
import android.content.pm.PackageManager
import com.discounthunter.android.data.net.fold
import com.discounthunter.android.diagnostics.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * What the update feature is currently doing.
 *
 * The states are explicit because the alternative is a set of independent booleans that can
 * disagree with each other — a screen that shows a spinner *and* an error, or a download that
 * finishes while the button still says "Download".
 */
sealed interface UpdateState {

    /** Nothing has been requested yet. */
    data object Idle : UpdateState

    /** A check is in flight. */
    data object Checking : UpdateState

    /** The installed build is the current published release. */
    data class UpToDate(
        val installed: AppVersion,
        val release: ReleaseInfo?,
    ) : UpdateState

    /** A newer release exists and can be installed. */
    data class Available(
        val installed: AppVersion,
        val release: ReleaseInfo,
        val asset: ReleaseAsset,
    ) : UpdateState

    /** The artifact is being fetched. */
    data class Downloading(
        val release: ReleaseInfo,
        val bytesRead: Long,
        val totalBytes: Long,
    ) : UpdateState {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /** The artifact is downloaded and verified, and is ready to install. */
    data class ReadyToInstall(
        val release: ReleaseInfo,
        val file: File,
    ) : UpdateState

    /** Something went wrong. [retryable] says whether trying again could work. */
    data class Failed(
        val message: String,
        val retryable: Boolean,
    ) : UpdateState

    /**
     * A release exists but carries no installable package.
     *
     * A distinct state from [Failed] because the remedy is different: there is nothing to retry,
     * and the user needs the release page rather than an error.
     */
    data class NoInstallableAsset(
        val release: ReleaseInfo,
        val reason: String,
    ) : UpdateState
}

/**
 * Drives the in-app updater.
 *
 * ## One source of truth
 *
 * The updater and the about screen read the *same* release metadata, through this one service. A
 * second, parallel GitHub integration would eventually show two different versions of the same
 * release, and there would be no way to tell which was right.
 *
 * ## The check is on demand
 *
 * Nothing is fetched until the user asks. An application that silently phones home to a release
 * server on every launch is doing something the user did not agree to, and it burns a rate limited
 * public endpoint for no benefit.
 */
class UpdateController(
    private val context: Context,
    private val service: GitHubReleaseService,
    private val downloader: ApkDownloader,
    private val scope: CoroutineScope,
    private val installedVersion: () -> AppVersion? = { AppVersion.installed() },
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The most recent release metadata, kept so the about screen can show it without a fetch. */
    private val _release = MutableStateFlow<ReleaseInfo?>(null)
    val release: StateFlow<ReleaseInfo?> = _release.asStateFlow()

    private val mutex = Mutex()
    private var job: Job? = null

    /** The directory a downloaded installer is written to. */
    private val downloadDirectory: File
        get() = File(context.cacheDir, "updates").apply { if (!isDirectory) mkdirs() }

    /**
     * Checks the official release feed.
     *
     * A check already in flight is not started again, so a user who taps the button twice does not
     * get two requests and two possible updates.
     */
    fun check() {
        job?.let { if (it.isActive) return }
        job = scope.launch {
            mutex.withLock {
                _state.value = UpdateState.Checking
                val outcome = service.latest()
                outcome.fold(
                    onSuccess = { info -> applyRelease(info) },
                    onFailure = { failure ->
                        AppLog.w("Updates: the release check failed (${failure.reason})")
                        _state.value = UpdateState.Failed(
                            message = failure.message,
                            retryable = failure.isTransient,
                        )
                    },
                )
            }
        }
    }

    /** Decides what the fetched release means for this install. */
    private fun applyRelease(info: ReleaseInfo) {
        _release.value = info
        val installed = installedVersion()

        if (info.prerelease || info.draft) {
            _state.value = UpdateState.NoInstallableAsset(
                release = info,
                reason = "That release is a preview, not a stable version.",
            )
            return
        }

        val published = info.version
        if (published == null) {
            // The version could not be read, so it is not known whether this is newer. Failing
            // safe means offering the release page rather than guessing.
            _state.value = UpdateState.NoInstallableAsset(
                release = info,
                reason = "The published version could not be read.",
            )
            return
        }

        if (installed == null) {
            _state.value = UpdateState.NoInstallableAsset(
                release = info,
                reason = "The installed version could not be read.",
            )
            return
        }

        if (published <= installed) {
            _state.value = UpdateState.UpToDate(installed, info)
            return
        }

        val asset = info.installableAsset()
        if (asset == null) {
            _state.value = UpdateState.NoInstallableAsset(
                release = info,
                reason = "That release does not publish an installable package.",
            )
            return
        }

        _state.value = UpdateState.Available(installed, info, asset)
    }

    /**
     * Downloads the update, having first checked that the user asked for it.
     *
     * Nothing is downloaded automatically: the user taps Download, and only then does a
     * multi-megabyte file start moving.
     */
    fun download() {
        val current = _state.value
        val available = current as? UpdateState.Available ?: return
        job?.let { if (it.isActive) return }
        job = scope.launch {
            mutex.withLock {
                val target = File(downloadDirectory, available.asset.name)
                _state.value = UpdateState.Downloading(available.release, 0L, available.asset.sizeBytes)

                val result = downloader.download(available.asset, target) { read, total ->
                    // Progress is reported from the download callback, which is already off the
                    // main thread; the state is a flow, so Compose recomposes on its own.
                    _state.value = UpdateState.Downloading(available.release, read, total)
                }

                result.fold(
                    onSuccess = { file ->
                        // A statement, not an expression: the `when` assigns to the state flow
                        // rather than producing a value.
                        when (val verdict = verifyPackage(file)) {
                            is PackageVerdict.Ok ->
                                _state.value = UpdateState.ReadyToInstall(available.release, file)

                            is PackageVerdict.WrongPackage -> {
                                file.delete()
                                _state.value = UpdateState.Failed(
                                    message = "That file is not a Discount Hunter update, so it was " +
                                        "not installed.",
                                    retryable = false,
                                )
                            }

                            is PackageVerdict.NotNewer -> {
                                file.delete()
                                _state.value = UpdateState.Failed(
                                    message = "That file is not newer than the version already " +
                                        "installed, so it was not installed.",
                                    retryable = false,
                                )
                            }
                        }
                        Unit
                    },
                    onFailure = { error ->
                        AppLog.e("Updates: the download could not be completed", error)
                        _state.value = UpdateState.Failed(
                            message = error.message ?: "The download could not be completed.",
                            retryable = true,
                        )
                    },
                )
            }
        }
    }

    /** Cancels a check or a download in flight, and returns to the state it started from. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = when (val current = _state.value) {
            is UpdateState.Downloading -> UpdateState.Failed(
                message = "The download was cancelled.",
                retryable = true,
            )
            is UpdateState.Checking -> UpdateState.Idle
            else -> current
        }
    }

    /** Removes a downloaded installer that is no longer needed. */
    fun discardDownload() {
        downloadDirectory.listFiles()?.forEach { it.delete() }
    }

    /**
     * Checks that a downloaded file really is this application, and really is newer.
     *
     * The operating system will refuse an update signed with a different key, and it will refuse a
     * downgrade, but finding that out by trying is a poor experience. Checking first lets the app
     * say what is wrong.
     */
    private fun verifyPackage(file: File): PackageVerdict {
        val manager = context.packageManager
        val archive = try {
            manager.getPackageArchiveInfo(file.absolutePath, 0)
        } catch (error: Exception) {
            AppLog.e("Updates: the downloaded package could not be read", error)
            null
        } ?: return PackageVerdict.WrongPackage

        if (archive.packageName != context.packageName) {
            AppLog.w("Updates: the downloaded package is '${archive.packageName}', not this app")
            return PackageVerdict.WrongPackage
        }

        val downloadedCode = archive.versionCodeCompat()
        val installedCode = try {
            manager.getPackageInfo(context.packageName, 0).versionCodeCompat()
        } catch (error: Exception) {
            AppLog.e("Updates: the installed package could not be read", error)
            return PackageVerdict.WrongPackage
        }
        if (downloadedCode <= installedCode) {
            return PackageVerdict.NotNewer
        }

        return PackageVerdict.Ok
    }

    /** The result of inspecting a downloaded package. */
    private sealed interface PackageVerdict {
        data object Ok : PackageVerdict
        data object WrongPackage : PackageVerdict
        data object NotNewer : PackageVerdict
    }
}

/**
 * The package's version code, on every API level the app supports.
 *
 * `longVersionCode` arrived in API 28 and `versionCode` is deprecated from then on, but the
 * application still supports API 24, so the older field has to be read on those devices.
 */
internal fun android.content.pm.PackageInfo.versionCodeCompat(): Long =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }
