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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.ericflo.winnow.backup.BackupStatus
import com.ericflo.winnow.backup.BackupSummary
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun BackupSection(status: BackupStatus, isDefault: Boolean, canBackUpMessages: Boolean, viewModel: SettingsViewModel) {
    val busy = status is BackupStatus.Working
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(viewModel::exportBackup)
    }
    // A protected backup isn't a zip anyone could open: named and typed as what it is.
    val createProtected = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(viewModel::exportBackup)
    }
    val protectedBackups by viewModel.backupPasswordSet.collectAsStateWithLifecycle()
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::openBackup)
    }
    val openXml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importSmsBackupRestore)
    }
    val createXml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        uri?.let(viewModel::exportSmsBackupRestore)
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
            modifier = Modifier.clickable(enabled = !busy) {
                if (protectedBackups) createProtected.launch("winnow-backup-${LocalDate.now()}${AutoBackup.PROTECTED_EXTENSION}")
                else create.launch("winnow-backup-${LocalDate.now()}.zip")
            },
        )
        AutoBackupRow(viewModel)
        BackupPasswordRow(viewModel)
        ListItem(
            headlineContent = { Text("Restore from a file") },
            supportingContent = { Text("Adds whatever is missing from a Winnow backup. Nothing on this phone is deleted.") },
            modifier = Modifier.clickable(enabled = !busy) { open.launch(arrayOf("application/zip", "application/octet-stream")) },
        )
        ListItem(
            headlineContent = { Text("Import from SMS Backup & Restore") },
            supportingContent = { Text("Adds the texts and picture messages from its .xml backup that aren't on this phone yet") },
            modifier = Modifier.clickable(enabled = !busy) { openXml.launch(arrayOf("text/xml", "application/xml", "*/*")) },
        )
        if (canBackUpMessages) {
            ListItem(
                headlineContent = { Text("Export for other apps") },
                supportingContent = { Text("Your texts and picture messages as an SMS Backup & Restore .xml file, which most texting apps can import") },
                // SMS Backup & Restore looks for files named like its own.
                modifier = Modifier.clickable(enabled = !busy) {
                    createXml.launch("sms-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}.xml")
                },
            )
        }
        BackupProgress(
            status,
            isDefault,
            onRestore = viewModel::restoreBackup,
            onDismiss = viewModel::dismissBackup,
            onUnlock = viewModel::unlockBackup,
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
fun BackupProgress(
    status: BackupStatus,
    isDefault: Boolean,
    onRestore: (Uri, includeSettings: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onUnlock: (Uri, CharArray) -> Unit = { _, _ -> },
) {
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
            is BackupStatus.NeedsPassword -> UnlockDialog(wrong = status.wrong, onUnlock = { onUnlock(status.uri, it) }, onDismiss = onDismiss)
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

/** A protected backup's password, to restore it. */
@Composable
private fun UnlockDialog(wrong: Boolean, onUnlock: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    // The verdict was on the last try: it goes once typing starts again.
    var showWrong by remember { mutableStateOf(wrong) }
    val open = { if (typed.isNotEmpty()) onUnlock(typed.toCharArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This backup has a password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("It was protected with a backup password. Enter it to restore.")
                PasswordField(
                    typed,
                    { typed = it; showWrong = false },
                    "Backup password",
                    error = if (showWrong) "That isn't its password" else null,
                    onDone = open,
                )
            }
        },
        confirmButton = { TextButton(onClick = open, enabled = typed.isNotEmpty()) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Settings → Backup password: protects backups (made by hand or automatically) from anyone without it. */
@Composable
private fun BackupPasswordRow(viewModel: SettingsViewModel) {
    val isSet by viewModel.backupPasswordSet.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Backup password") },
        supportingContent = {
            Text(
                if (isSet) "On: backup files open only with it, wherever they're kept. Recently deleted and exports for other apps aren't covered."
                else "Off: anyone with a backup file can read your messages in it. Set one if backups go to a shared or cloud folder.",
            )
        },
        modifier = Modifier.clickable { editing = true },
    )
    if (editing) {
        BackupPasswordDialog(
            isSet = isSet,
            onSet = viewModel::setBackupPassword,
            onRemove = viewModel::removeBackupPassword,
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun BackupPasswordDialog(
    isSet: Boolean,
    /** Whether it was kept. */
    onSet: suspend (CharArray) -> Boolean,
    onRemove: suspend () -> Boolean,
    onDismiss: () -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    // Open until the change is made (a second or so) or has failed, so it's never in doubt.
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val tooShort = first.isNotEmpty() && first.length < MIN_PASSWORD
    val mismatch = again.isNotEmpty() && again != first
    val valid = first.length >= MIN_PASSWORD && again == first && !saving
    fun change(failure: String, action: suspend () -> Boolean) {
        saving = true
        failed = null
        scope.launch {
            val ok = action()
            saving = false
            if (ok) onDismiss() else failed = failure
        }
    }
    val set = { if (valid) change("The password couldn't be saved. Try again.") { onSet(first.toCharArray()) } }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (isSet) "Change backup password" else "Set a backup password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "New backups, including automatic ones, will need it to be restored. Winnow can't recover it: " +
                        "without it, a backup made with it can't be opened, on any phone.",
                )
                PasswordField(first, { first = it }, "New password", error = if (tooShort) "At least $MIN_PASSWORD characters" else null)
                PasswordField(again, { again = it }, "Again", error = if (mismatch) "The two don't match" else null, onDone = set)
                if (isSet) {
                    Text(
                        "Backups already made keep the password they were made with.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                failed?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = set, enabled = valid) { Text(if (isSet) "Change" else "Set") }
        },
        dismissButton = {
            Row {
                if (isSet) TextButton(onClick = { change("The password couldn't be turned off. Try again.", onRemove) }, enabled = !saving) { Text("Turn off") }
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String? = null, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onDone = onDone?.let { { it() } }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A backup password's least length: long enough that guessing at a stolen file takes a while. */
private const val MIN_PASSWORD = 8

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

private fun plural(n: Int, noun: String) = "${count(n)} $noun${if (n == 1) "" else "s"}"
