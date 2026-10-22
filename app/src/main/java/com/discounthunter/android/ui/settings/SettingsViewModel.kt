package com.discounthunter.android.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.discounthunter.android.alerts.AlertNotifier
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.data.settings.AppSettings
import com.discounthunter.android.data.settings.CurrencyCode
import com.discounthunter.android.data.settings.SettingsRepository
import com.discounthunter.android.data.settings.ThemeMode
import com.discounthunter.android.di.AppContainer
import com.discounthunter.android.diagnostics.AppLog
import com.discounthunter.android.update.ApkDownloader
import com.discounthunter.android.update.ApkInstaller
import com.discounthunter.android.update.GitHubReleaseService
import com.discounthunter.android.update.ReleaseInfo
import com.discounthunter.android.update.UpdateController
import com.discounthunter.android.update.InstallResults
import com.discounthunter.android.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What happened when the user asked to install a downloaded update. */
sealed interface InstallOutcome {
    /** The platform installer was started with the downloaded package. */
    data object Started : InstallOutcome

    /**
     * The app is not allowed to request package installation yet.
     *
     * The user has been sent to the system screen where the permission is granted. The
     * installation is not started, because Android will not allow it until they do.
     */
    data object PermissionNeeded : InstallOutcome

    /** There was nothing downloaded to install. */
    data object NothingToInstall : InstallOutcome

    /** The installer could not be started, and the screen says so. */
    data class Failed(val message: String) : InstallOutcome
}
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    /** `-1` until the first measurement completes. */
    val cacheSizeBytes: Long = -1L,
    val isClearingCache: Boolean = false,
    /** True while the delete-everything pass is running, so the button can disable itself. */
    val isDeletingData: Boolean = false,
    /** Whether a store app that handles its deep links is installed. */
    val storeApps: Map<StoreId, Boolean> = emptyMap(),
    /**
     * Whether the app is allowed to post notifications.
     *
     * False on Android 12 and below only if the user blocked them in system settings; on Android 13
     * and above it is false until the permission is granted. The deal-alerts switch can be on
     * while this is false, which is exactly the state that makes the feature look broken.
     */
    val notificationsAllowed: Boolean = true,
)

/**
 * Settings, plus the two things that need a little extra machinery: the cache size (measured off
 * the main thread) and which store apps are installed.
 *
 * Writes go straight to [SettingsRepository], whose flow feeds every screen — so enabling a store
 * here immediately re-queries it on the front page, with no manual refresh needed.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val container: AppContainer,
    private val detectStoreApp: (StoreId) -> Boolean,
) : ViewModel() {

    private val cacheSize = MutableStateFlow(-1L)
    private val clearing = MutableStateFlow(false)
    private val deleting = MutableStateFlow(false)
    private val detectedApps = MutableStateFlow(StoreId.MAJOR.associateWith { false })
    private val notificationsAllowed = MutableStateFlow(true)

    /**
     * The updater.
     *
     * It lives here rather than in its own view model because the About dialog and the update
     * entry are the same feature, and two view models would mean two release-metadata fetches and
     * two answers to "is there an update".
     */
    private val updateController = UpdateController(
        context = container.appContext,
        service = GitHubReleaseService(container.network, container.payloadCacheForUpdates),
        downloader = ApkDownloader(container.httpClient),
        scope = viewModelScope,
    )

    private val installer = ApkInstaller(container.appContext)

    /** The update state and the latest release, for the about dialog. */
    val updateState: StateFlow<UpdateState> = updateController.state
    val latestRelease: StateFlow<ReleaseInfo?> = updateController.release
    val installResult: StateFlow<InstallResults.State?> = InstallResults.state

    /**
     * The state this screen owns itself, as opposed to state that comes from the repository.
     *
     * Grouped into one flow because `combine` has typed overloads for up to five flows, and this
     * screen has more state than that once the notification permission is added.
     */
    private data class LocalFlags(
        val cacheSize: Long,
        val clearing: Boolean,
        val deleting: Boolean,
        val notificationsAllowed: Boolean,
    )

    private val localFlags = combine(
        cacheSize,
        clearing,
        deleting,
        notificationsAllowed,
    ) { size, isClearing, isDeleting, allowed ->
        LocalFlags(size, isClearing, isDeleting, allowed)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        detectedApps,
        localFlags,
    ) { settings, apps, flags ->
        SettingsUiState(
            settings = settings,
            cacheSizeBytes = flags.cacheSize,
            isClearingCache = flags.clearing,
            isDeletingData = flags.deleting,
            storeApps = apps,
            notificationsAllowed = flags.notificationsAllowed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(storeApps = StoreId.MAJOR.associateWith { false }),
    )

    init {
        settingsRepository.settings
            .onEach { measureCache() }
            .launchIn(viewModelScope)
        refreshStoreApps()
        refreshNotificationPermission()
    }

    // ------------------------------------------------------------------ actions

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }

    fun setCurrency(currency: CurrencyCode) =
        viewModelScope.launch { settingsRepository.setCurrency(currency) }

    fun setStoreEnabled(store: StoreId, enabled: Boolean) = viewModelScope.launch {
        val existing = uiState.value.settings.enabledStores
        settingsRepository.setEnabledStores(
            if (enabled) existing + store else existing - store,
        )
    }

    fun setDealLimit(limit: Int) = viewModelScope.launch { settingsRepository.setDealLimit(limit) }

    fun setAutoplayTrailers(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setAutoplayTrailers(enabled) }

    fun setPreferStoreApps(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setPreferStoreApps(enabled) }

    /**
     * Turns the background announcements on or off.
     *
     * The change is applied to the schedule as well as to the setting. The schedule lives outside
     * the app's process, so writing the setting alone would leave the checks running and the
     * notifications arriving after the user had switched the feature off.
     *
     * The notification permission is requested by the screen, not here: a ViewModel has no
     * activity to ask, and reaching for one is how a permission dialog ends up attached to
     * nothing.
     */
    fun setSaleAlertsEnabled(enabled: Boolean) = viewModelScope.launch {
        container.setSaleAlertsEnabled(enabled)
        refreshNotificationPermission()
    }

    /**
     * Opens the system notification settings for this app.
     *
     * Once the permission has been refused, Android does not show the dialog again — the only way
     * back is the settings screen, so that is where the button leads.
     */
    fun openNotificationSettings() {
        val context = container.appContext
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Re-reads whether notifications are allowed, so the screen stops warning once they are. */
    fun refreshNotificationPermission() {
        notificationsAllowed.value = AlertNotifier(container.appContext).canPost()
    }

    // ------------------------------------------------------------------ updates

    /** Checks the official release feed for a newer version. */
    fun checkForUpdates() = updateController.check()

    /** Downloads the update the user has asked for. */
    fun downloadUpdate() = updateController.download()

    /** Cancels a check or a download in flight. */
    fun cancelUpdate() = updateController.cancel()

    /**
     * Starts the platform installer for the downloaded package.
     *
     * Returns the outcome so the screen can say what happened: the installer was started, the
     * permission is missing and the user was sent to grant it, or the installer could not be
     * started at all.
     */
    fun installUpdate(): InstallOutcome {
        val state = updateController.state.value
        val file = (state as? UpdateState.ReadyToInstall)?.file
            ?: return InstallOutcome.NothingToInstall
        if (!installer.canRequestInstall()) {
            // The user has to grant this in system settings; the application cannot ask for it in
            // a dialog. Send them there and say what to do.
            return if (installer.openInstallPermissionSettings()) {
                InstallOutcome.PermissionNeeded
            } else {
                InstallOutcome.Failed("The system settings screen could not be opened.")
            }
        }
        InstallResults.reset()
        return if (installer.install(file)) {
            InstallOutcome.Started
        } else {
            InstallOutcome.Failed("The installation could not be started.")
        }
    }

    /** Removes a downloaded installer that is no longer needed. */
    fun discardUpdate() = updateController.discardDownload()

    fun linkAccount(store: StoreId, name: String?) =
        viewModelScope.launch { settingsRepository.setLinkedAccount(store, name) }

    fun clearCache() = viewModelScope.launch {
        clearing.value = true
        container.clearCaches()
        clearing.value = false
        measureCache()
    }

    /**
     * Deletes every trace of the app's use: caches, day snapshots, settings, recent searches and
     * the whole wishlist.
     *
     * The intro pages come back too, because "seen the intro" is a stored preference like any
     * other — which is the behaviour the button's confirmation promises, and it means the next
     * launch starts from a genuinely clean slate.
     */
    fun deleteAllData() = viewModelScope.launch {
        deleting.value = true
        container.deleteAllData()
        measureCache()
        deleting.value = false
    }

    /** Re-probes which store apps are installed; called whenever the screen is shown. */
    fun refreshStoreApps() = viewModelScope.launch {
        detectedApps.value = StoreId.MAJOR.associateWith { detectStoreApp(it) }
    }

    /**
     * Writes a diagnostics report to the cache and hands it to the system share sheet.
     *
     * The report carries the last few hundred log lines alongside the OS version, screen size and
     * free memory, because a crash reported from memory has none of that and is close to unusable.
     */
    fun exportLogs(context: Context) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { AppLog.export(context) }
            AppLog.i("Exported diagnostics: ${file.name}, ${file.length()} bytes")
            shareReport(context, file)
        }
    }

    /**
     * Shares the report through a `FileProvider` URI.
     *
     * A plain `file://` URI throws `FileUriExposedException` on any modern Android, which is exactly
     * the kind of "it works on my device" bug this class exists to catch.
     */
    private fun shareReport(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Discount Hunter diagnostics")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export logs"))
        } catch (error: Exception) {
            AppLog.e("Could not open the share sheet for the diagnostics report", error)
        }
    }

    private fun measureCache() {
        viewModelScope.launch { cacheSize.value = container.cacheSizeBytes() }
    }

    private companion object {
        const val STATE_TIMEOUT_MILLIS = 5_000L
    }
}

/** Renders a byte count the way a person would read it. */
fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "…"
    bytes == 0L -> "Empty"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 1024.0).let { String.format("%.1f", it) }} KB"
    bytes < 1024L * 1024 * 1024 -> "${(bytes / (1024.0 * 1024.0)).let { String.format("%.1f", it) }} MB"
    else -> "${(bytes / (1024.0 * 1024.0 * 1024.0)).let { String.format("%.2f", it) }} GB"
}
