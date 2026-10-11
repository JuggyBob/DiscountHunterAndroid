package com.discounthunter.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discounthunter.android.BuildConfig
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import com.discounthunter.android.R
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.data.settings.AppSettings
import com.discounthunter.android.data.settings.CurrencyCode
import com.discounthunter.android.data.settings.ThemeMode
import kotlin.math.roundToInt

/**
 * Settings.
 *
 * Grouped the way people look for things — appearance, then what the feed shows, then storage — and
 * every control writes straight through to persisted settings, so a change is visible immediately
 * when the user navigates back.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onStoreAppStateChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var accountFor by remember { mutableStateOf<StoreId?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    // Re-probe installed store apps whenever the screen becomes visible.
    LaunchedEffect(Unit) {
        viewModel.refreshStoreApps()
        onStoreAppStateChanged()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- appearance ----
            SettingsCard(title = stringResource(R.string.settings_appearance)) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = {
                                Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                        ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                    },
                                )
                            },
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text("Currency", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_currency_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CurrencyCode.entries.forEach { currency ->
                        FilterChip(
                            selected = state.settings.currency == currency,
                            onClick = { viewModel.setCurrency(currency) },
                            label = { Text("${currency.code} ${currency.symbol}") },
                        )
                    }
                }
            }

            // ---- stores ----
            SettingsCard(title = stringResource(R.string.settings_stores)) {
                StoreId.MAJOR.forEach { store ->
                    SwitchRow(
                        title = store.displayName,
                        // The switch controls whether this store is queried, so the subtitle
                        // reports *why* a link might behave differently rather than sounding like
                        // the label of a second, hidden setting.
                        subtitle = when (state.storeApps[store]) {
                            true -> "In the feed · links open in the store app"
                            false -> "In the feed · links open in a browser"
                            null -> "In the feed"
                        },
                        checked = store in state.settings.enabledStores,
                        onCheckedChange = { viewModel.setStoreEnabled(store, it) },
                    )
                }
            }

            // ---- feed ----
            SettingsCard(title = stringResource(R.string.settings_deals)) {
                Text(
                    text = stringResource(R.string.settings_page_size),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${state.settings.dealLimit} per store",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.settings.dealLimit.toFloat(),
                    onValueChange = { viewModel.setDealLimit(it.roundToInt()) },
                    valueRange = AppSettings.MIN_DEAL_LIMIT.toFloat()..AppSettings.MAX_DEAL_LIMIT.toFloat(),
                    steps = (AppSettings.MAX_DEAL_LIMIT - AppSettings.MIN_DEAL_LIMIT - 1),
                )

                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = "Autoplay trailers",
                    subtitle = "Start playing when a trailer opens",
                    checked = state.settings.autoplayTrailers,
                    onCheckedChange = viewModel::setAutoplayTrailers,
                )
                SwitchRow(
                    title = "Open store apps first",
                    subtitle = "Use the Steam or Epic app when it is installed",
                    checked = state.settings.preferStoreApps,
                    onCheckedChange = viewModel::setPreferStoreApps,
                )
            }

            // ---- accounts ----
            SettingsCard(title = "Store accounts") {
                Text(
                    text = "No store offers an API for claiming a game, so the app cannot do it for " +
                        "you. Linking your username only makes the confirmation messages name your " +
                        "account. It is stored on this device and never sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                StoreId.MAJOR.forEach { store ->
                    val linked = state.settings.accountFor(store)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(store.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = linked?.let { "Linked as $it" } ?: "Not linked",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { accountFor = store }) {
                            Text(if (linked == null) "Link" else "Change")
                        }
                    }
                }
            }

            // ---- storage ----
            SettingsCard(title = stringResource(R.string.settings_cache)) {
                Text(
                    text = stringResource(R.string.settings_cache_size),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (state.isClearingCache) {
                        stringResource(R.string.cache_calculating)
                    } else {
                        formatBytes(state.cacheSizeBytes)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = !state.isClearingCache,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_clear_cache))
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.settings_delete_data_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.settings_delete_data_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { confirmDeleteAll = true },
                    enabled = !state.isDeletingData,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_delete_data))
                }
            }

            // ---- diagnostics ----
            //
            // Its own card rather than another button in Storage. It sits next to "Delete all app
            // data" otherwise, and those two are opposites: one is a way to *get your data out*
            // before something goes wrong, the other is a way to destroy it. Grouping them put the
            // export one tap from the button that wipes the wishlist.
            SettingsCard(title = stringResource(R.string.settings_diagnostics)) {
                Text(
                    text = stringResource(R.string.settings_export_logs_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_export_logs_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { viewModel.exportLogs(context) }) {
                    Icon(
                        imageVector = Icons.Outlined.BugReport,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_export_logs))
                }
            }

            // ---- about ----
            SettingsCard(title = stringResource(R.string.settings_about)) {
                Text("Discount Hunter ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Live data from the Steam Store API, the GOG catalogue and Epic's free-games " +
                        "feed. No account, no tracking, no keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    accountFor?.let { store ->
        LinkAccountDialog(
            store = store,
            current = state.settings.accountFor(store),
            onDismiss = { accountFor = null },
            onSave = { name ->
                viewModel.linkAccount(store, name)
                accountFor = null
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_delete_data_confirm_title)) },
            text = { Text(stringResource(R.string.settings_delete_data_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllData()
                        confirmDeleteAll = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.settings_delete_data_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(R.string.settings_delete_data_confirm_no))
                }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear cached data?") },
            text = {
                Text("Downloaded store pages and artwork will be removed and re-fetched when needed. " +
                    "Your settings and wishlist are kept.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearCache()
                        confirmClear = false
                    },
                ) { Text("Clear cache") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LinkAccountDialog(
    store: StoreId,
    current: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var name by remember { mutableStateOf(current.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link your ${store.shortName} account") },
        text = {
            Column {
                Text(
                    text = "This name is only used to make the confirmation messages clearer. " +
                        "It stays on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim().ifBlank { null }) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = { onSave(null) }) { Text("Unlink") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
