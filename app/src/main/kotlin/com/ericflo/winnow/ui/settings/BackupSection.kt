package com.ericflo.winnow.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.ericflo.winnow.backup.AutoBackup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ericflo.winnow.backup.BackupStatus
import com.ericflo.winnow.backup.BackupSummary
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun BackupSection(status: BackupStatus, isDefault: Boolean, canBackUpMessages: Boolean, viewModel: SettingsViewModel) {
    val busy = status is BackupStatus.Working
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(viewModel::exportBackup)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::openBackup)
    }

    Column {
        ListItem(
            headlineContent = { Text("Back up to a file") },
            supportingContent = {
                Text(
                    if (canBackUpMessages) "Messages, photos, Winnow's decisions and settings, in one file. API keys stay on this phone."
                    else "Settings and sender rules only, until Winnow can read your messages.",
                )
            },
            modifier = Modifier.clickable(enabled = !busy) { create.launch("winnow-backup-${LocalDate.now()}.zip") },
        )
        AutoBackupRow(viewModel)
        ListItem(
            headlineContent = { Text("Restore from a file") },
            supportingContent = { Text("Adds whatever is missing from a Winnow backup. Nothing on this phone is deleted.") },
            modifier = Modifier.clickable(enabled = !busy) { open.launch(arrayOf("application/zip", "application/octet-stream")) },
        )
        BackupProgress(
            status,
            isDefault,
            onRestore = viewModel::restoreBackup,
            onDismiss = viewModel::dismissBackup,
        )
    }
}

/** Weekly backups into a folder, with when the last one ran (or why it didn't) and "Back up now". */
@Composable
private fun AutoBackupRow(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val folderName by viewModel.autoBackupFolderName.collectAsStateWithLifecycle()
    val running by viewModel.autoBackupRunning.collectAsStateWithLifecycle()
    val notice by viewModel.autoBackupNotice.collectAsStateWithLifecycle()
    val s = settings ?: return
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::enableAutoBackup) }
    val on = s.autoBackupFolder != null
    val toggle = { if (on) viewModel.disableAutoBackup() else pickFolder.launch(null) }
    ListItem(
        headlineContent = { Text("Back up automatically") },
        supportingContent = {
            Text(
                when {
                    !on -> "Every week while charging, into a folder you choose. The newest ${AutoBackup.KEEP} are kept."
                    s.autoBackupError != null -> "The last one failed: ${s.autoBackupError}"
                    s.autoBackupLast > 0 -> "Every week while charging, to ${folderName.orEmpty()}. Last: ${dateTime(s.autoBackupLast)}"
                    else -> "Every week while charging, to ${folderName.orEmpty()}. None yet."
                },
                color = if (on && s.autoBackupError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = on, role = Role.Switch) { toggle() },
    )
    if (on) {
        TextButton(onClick = viewModel::backUpNow, enabled = !running, modifier = Modifier.padding(start = 8.dp)) {
            Text(if (running) "Backing up…" else "Back up now")
        }
    }
    notice?.let { Outcome(it, isError = it.startsWith("Couldn't"), onDismiss = viewModel::dismissAutoBackupNotice) }
}

private fun dateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

/** Progress, the outcome, or the confirm dialog for a backup or restore in flight. Shared with onboarding. */
@Composable
fun BackupProgress(status: BackupStatus, isDefault: Boolean, onRestore: (Uri, includeSettings: Boolean) -> Unit, onDismiss: () -> Unit) {
    Column {
        when (status) {
            is BackupStatus.Working -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val progress = if (status.total > 0) "${status.label} · ${count(status.done)} of ${count(status.total)}" else "${status.label}…"
                Text(progress, style = MaterialTheme.typography.bodyMedium)
                if (status.total > 0) {
                    LinearProgressIndicator(progress = { status.done.toFloat() / status.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            is BackupStatus.Done -> Outcome(status.message, isError = false, onDismiss = onDismiss)
            is BackupStatus.Failed -> Outcome(status.message, isError = true, onDismiss = onDismiss)
            is BackupStatus.Ready -> RestoreDialog(
                status.summary,
                isDefault,
                onRestore = { includeSettings -> onRestore(status.uri, includeSettings) },
                onDismiss = onDismiss,
            )
            BackupStatus.Idle -> Unit
        }
    }
}

@Composable
private fun Outcome(message: String, isError: Boolean, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

@Composable
private fun RestoreDialog(summary: BackupSummary, isDefault: Boolean, onRestore: (includeSettings: Boolean) -> Unit, onDismiss: () -> Unit) {
    var includeSettings by rememberSaveable { mutableStateOf(summary.hasSettings) }
    val made = Instant.ofEpochMilli(summary.createdAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore this backup?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Made $made: ${plural(summary.messages, "message")} in ${plural(summary.conversations, "conversation")}, " +
                        "and ${plural(summary.senderRules, "sender rule")}. Messages already on this phone are skipped.",
                )
                if (!isDefault && summary.messages > 0) {
                    Text(
                        "Winnow isn't your SMS app, so only settings and sender rules can be restored. Make it your SMS app first to restore messages.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (summary.hasSettings) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.toggleable(value = includeSettings, role = Role.Checkbox) { includeSettings = it },
                    ) {
                        Checkbox(checked = includeSettings, onCheckedChange = null)
                        Text("Also replace my settings (API keys aren't in backups)")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRestore(includeSettings) }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

private fun plural(n: Int, noun: String) = "${count(n)} $noun${if (n == 1) "" else "s"}"
