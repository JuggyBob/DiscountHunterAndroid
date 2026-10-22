package com.discounthunter.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.discounthunter.android.R
import com.discounthunter.android.update.InstallResults
import com.discounthunter.android.update.ReleaseInfo
import com.discounthunter.android.update.ReleaseNotesFormatter
import com.discounthunter.android.update.UpdateState

/**
 * The About / What's New dialog.
 *
 * ## The one thing this must not get wrong
 *
 * There are two versions on screen and they are not the same thing: the version **installed** on
 * this device, and the version **published** most recently. They are labelled separately, always,
 * and the release notes are headed with the published version. A user who reads "1.1.1" under a
 * heading that implies they have it, when they have 1.1.0, will be told to look for a feature
 * that is not in their build.
 *
 * So: the installed version is shown under "Installed", the published one under "Latest release",
 * and the notes are under "What's new in <published version>".
 */
@Composable
fun AboutDialog(
    installedVersionName: String,
    updateState: UpdateState,
    release: ReleaseInfo?,
    installResult: InstallResults.State?,
    onCheckForUpdates: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.about_name),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))

                LabelValue(
                    label = stringResource(R.string.about_installed),
                    value = installedVersionName,
                )
                release?.let { info ->
                    LabelValue(
                        label = stringResource(R.string.about_latest),
                        value = info.versionLabel,
                    )
                    if (info.publishedAt.isNotBlank()) {
                        LabelValue(
                            label = stringResource(R.string.about_published),
                            value = formatPublished(info.publishedAt),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                InstallOutcomeText(installResult)
                Spacer(Modifier.height(14.dp))
                UpdateStatus(
                    state = updateState,
                    onCheckForUpdates = onCheckForUpdates,
                    onDownload = onDownload,
                    onInstall = onInstall,
                    onCancel = onCancel,
                )

                release?.let { info ->
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(
                            R.string.about_whats_new,
                            info.versionLabel,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    ReleaseNotes(info.body)
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        onOpenReleasePage(release?.htmlUrl ?: DEFAULT_RELEASE_PAGE)
                    },
                ) {
                    Text(stringResource(R.string.about_view_on_github))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_close)) }
        },
    )
}

/**
 * The outcome of the last installation attempt.
 *
 * Shown so the user is not left guessing whether the update went through: the system
 * installer takes over the screen, and when it finishes the application has to say what
 * happened.
 */
@Composable
private fun InstallOutcomeText(state: InstallResults.State?) {
    when (state) {
        is InstallResults.State.AwaitingConfirmation -> Text(
            text = stringResource(R.string.update_install_awaiting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        is InstallResults.State.Completed -> Text(
            text = stringResource(R.string.update_install_done),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        is InstallResults.State.Failed -> Text(
            text = stringResource(R.string.update_install_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        null -> Unit
    }
}

/** A label and its value, stacked. */
@Composable
private fun LabelValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The update status, and the action that matches it.
 *
 * Exactly one action is offered at a time, and the state names decide which: a check while idle,
 * a download when an update exists, and a retry when something failed. The alternative — a button
 * that changes meaning under the user — is how a check ends up firing twice.
 */
@Composable
private fun UpdateStatus(
    state: UpdateState,
    onCheckForUpdates: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        is UpdateState.Idle -> {
            Text(
                text = stringResource(R.string.update_idle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCheckForUpdates) {
                Text(stringResource(R.string.update_check))
            }
        }

        is UpdateState.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.height(16.dp).width(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.update_checking),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is UpdateState.UpToDate -> {
            Text(
                text = stringResource(R.string.update_up_to_date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        is UpdateState.Available -> {
            Text(
                text = stringResource(
                    R.string.update_available,
                    state.installed.toString(),
                    state.release.versionLabel,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDownload) {
                    Text(stringResource(R.string.update_download))
                }
                TextButton(onClick = onCheckForUpdates) {
                    Text(stringResource(R.string.update_recheck))
                }
            }
        }

        is UpdateState.Downloading -> {
            Text(
                text = stringResource(R.string.update_downloading),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.update_cancel))
            }
        }

        is UpdateState.ReadyToInstall -> {
            Text(
                text = stringResource(R.string.update_ready),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onInstall) {
                Text(stringResource(R.string.update_install))
            }
        }

        is UpdateState.Failed -> {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (state.retryable) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCheckForUpdates) {
                    Text(stringResource(R.string.update_retry))
                }
            }
        }

        is UpdateState.NoInstallableAsset -> {
            Text(
                text = state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.update_no_package),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The release notes, rendered from the limited Markdown subset.
 *
 * Scrolls with the rest of the dialog, so a long changelog does not push the close button off the
 * screen.
 */
@Composable
private fun ReleaseNotes(body: String) {
    val lines = remember(body) { ReleaseNotesFormatter.parse(body) }
    if (lines.isEmpty()) {
        Text(
            text = stringResource(R.string.about_no_notes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column {
        for (line in lines) {
            when (line.style) {
                ReleaseNotesFormatter.Style.RULE -> {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                }

                ReleaseNotesFormatter.Style.HEADING -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = inline(line.text),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(2.dp))
                }

                ReleaseNotesFormatter.Style.SUBHEADING -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = inline(line.text),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                ReleaseNotesFormatter.Style.BULLET -> {
                    Row(modifier = Modifier.padding(bottom = 4.dp)) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = inline(line.text),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                ReleaseNotesFormatter.Style.TEXT -> {
                    Text(
                        text = inline(line.text),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * Applies the inline styles to one line of text.
 *
 * The parsing lives in [ReleaseNotesFormatter], so it can be tested without a Compose runtime; this
 * only turns the segments into a styled string.
 */
@Composable
private fun inline(text: String): AnnotatedString {
    val segments = remember(text) { ReleaseNotesFormatter.inline(text) }
    return buildAnnotatedString {
        for (segment in segments) {
            withStyle(
                SpanStyle(
                    fontWeight = if (segment.bold) FontWeight.Bold else null,
                    fontStyle = if (segment.italic) FontStyle.Italic else null,
                    fontFamily = if (segment.code) FontFamily.Monospace else null,
                ),
            ) {
                append(segment.text)
            }
        }
    }
}
/** Formats a GitHub timestamp as a plain date. */
private fun formatPublished(publishedAt: String): String = runCatching {
    val instant = java.time.Instant.parse(publishedAt)
    val formatter = java.time.format.DateTimeFormatter
        .ofLocalizedDate(java.time.format.FormatStyle.LONG)
        .withZone(java.time.ZoneId.systemDefault())
    formatter.format(instant)
}.getOrDefault(publishedAt)

/** The official release page, used when no release metadata has been fetched yet. */
private const val DEFAULT_RELEASE_PAGE =
    "https://github.com/JuggyBob/DiscountHunterAndroid/releases/latest"
