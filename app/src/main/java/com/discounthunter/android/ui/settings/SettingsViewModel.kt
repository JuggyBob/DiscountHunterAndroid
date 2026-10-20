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
