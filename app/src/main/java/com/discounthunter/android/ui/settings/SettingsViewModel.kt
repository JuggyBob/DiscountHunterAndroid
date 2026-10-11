package com.discounthunter.android.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        cacheSize,
        clearing,
        detectedApps,
        deleting,
    ) { settings, size, isClearing, apps, isDeleting ->
        SettingsUiState(
            settings = settings,
            cacheSizeBytes = size,
            isClearingCache = isClearing,
            isDeletingData = isDeleting,
            storeApps = apps,
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
