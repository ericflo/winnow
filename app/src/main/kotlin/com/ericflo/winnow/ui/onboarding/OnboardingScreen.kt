package com.ericflo.winnow.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import com.ericflo.winnow.ui.settings.BackupProgress
import com.ericflo.winnow.backup.BackupStatus
import com.ericflo.winnow.backup.BackupManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.OutlinedButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ericflo.winnow.ui.components.RestrictedSettingHelp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ericflo.winnow.R
import com.ericflo.winnow.data.ProviderKind

/**
 * First run, in three steps: what Winnow does; becoming the SMS app (with the RCS trade-off
 * stated plainly); and how to classify. Everything here can be changed later in Settings.
 */
@Composable
fun OnboardingScreen(
    isDefault: () -> Boolean,
    onMakeDefault: () -> Unit,
    onChooseClassifier: (ProviderKind, apiKey: String) -> Unit,
    /** The classifier settings hold now. */
    initialClassifier: ProviderKind = ProviderKind.ON_DEVICE,
    onFinish: () -> Unit,
    backups: BackupManager,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    // Up here, not in the step: Back and Next again must show what was chosen (and saved). It
    // starts from what settings say, so a backup restored on the step before shows its choice.
    // The key isn't kept in saved state: it's saved to settings as it's typed.
    var classifier by rememberSaveable(initialClassifier) { mutableStateOf(initialClassifier) }
    var apiKey by remember { mutableStateOf("") }
    // Back goes to the step before, as a swipe back through a setup flow does; from the first, it leaves.
    BackHandler(enabled = step > 0) { step-- }
    var defaultNow by rememberSaveable { mutableStateOf(isDefault()) }
    LifecycleResumeEffect(Unit) {
        defaultNow = isDefault()
        onPauseOrDispose { }
    }
    // A Surface, not just a background, so text gets onSurface instead of the default black.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { Column(
        Modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                0 -> Welcome()
                1 -> BeDefault(defaultNow, onMakeDefault, backups)
                else -> ChooseClassifier(classifier, apiKey) { kind, key ->
                    classifier = kind
                    apiKey = key
                    onChooseClassifier(kind, key.trim())
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            StepDots(step)
            Spacer(Modifier.weight(1f))
            if (step < 2) {
                TextButton(onClick = onFinish) { Text("Skip") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { step++ }) { Text("Next") }
            } else {
                Button(onClick = onFinish) { Text("Start") }
            }
        }
    } }
}

@Composable
private fun Welcome() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 56.dp)) {
        Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_notification), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
        }
        Text("Texts worth your attention.", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Winnow is a full messaging app that reads every incoming text before it can buzz your phone.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Point("Scams, phishing, spam and political blasts go to Filtered, without a notification.")
        Point("Marketing arrives quietly. People you know always come through.")
        Point("Nothing is deleted, and one tap fixes a wrong call.")
    }
}

@Composable
private fun BeDefault(isDefault: Boolean, onMakeDefault: () -> Unit, backups: BackupManager) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 56.dp)) {
        Text("Make Winnow your SMS app", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Android only lets the default SMS app see texts before they notify you, so Winnow needs to be it.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About RCS", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Android doesn't let apps other than Google Messages use RCS. In Winnow, chats send as SMS and MMS: " +
                        "group chats and photos still work, but typing indicators, read receipts and end-to-end " +
                        "encryption don't. You can switch back to Google Messages any time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Until RCS is off, other phones keep sending RCS, which only Google Messages receives;
                // turned off first, people's messages arrive as texts, here, right away.
                Text(
                    "Before you switch: in Google Messages, open Settings, then RCS chats, and turn RCS off. " +
                        "Otherwise messages people send you over RCS keep going to Google Messages for a while.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val context = LocalContext.current
                val messages = remember { context.packageManager.getLaunchIntentForPackage(GOOGLE_MESSAGES) }
                if (messages != null && !isDefault) {
                    TextButton(onClick = { runCatching { context.startActivity(messages) } }, contentPadding = PaddingValues(0.dp)) {
                        Text("Open Google Messages")
                    }
                }
            }
        }
        if (isDefault) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Winnow is your SMS app", style = MaterialTheme.typography.titleMedium)
            }
            RestoreCard(backups)
        } else {
            Button(onClick = onMakeDefault) { Text("Set as default SMS app") }
            RestrictedSettingHelp()
        }
    }
}

/** For a new phone: bring messages, photos and Winnow's decisions over from a backup file. */
@Composable
private fun RestoreCard(backups: BackupManager) {
    val status by backups.status.collectAsStateWithLifecycle()
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(backups::open) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Moving from another phone?", style = MaterialTheme.typography.titleSmall)
            Text(
                "Restore a Winnow backup: messages, photos, and what Winnow learned. Anything already here is kept.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status == BackupStatus.Idle) {
                OutlinedButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore from a file") }
            }
            BackupProgress(status, isDefault = true, onRestore = backups::restore, onDismiss = backups::dismiss, onUnlock = backups::unlock)
        }
    }
}

@Composable
private fun ChooseClassifier(kind: ProviderKind, key: String, onChoose: (ProviderKind, String) -> Unit) {
    val options = listOf(ProviderKind.ON_DEVICE, ProviderKind.OPENROUTER_JEV, ProviderKind.TYPESAFE_JEV)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 56.dp)) {
        Text("How should Winnow decide?", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Messages from contacts, people you've texted and verification codes never leave this phone. " +
                "Anything sent to a classifier has numbers and links trimmed first.",
            style = MaterialTheme.typography.bodyLarge,
        )
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = kind == option, role = Role.RadioButton, onClick = { onChoose(option, key) }),
            ) {
                RadioButton(selected = kind == option, onClick = null, modifier = Modifier.padding(12.dp))
                Column(Modifier.padding(top = 10.dp)) {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    Text(option.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (kind.needsApiKey) {
            OutlinedTextField(
                value = key,
                onValueChange = { onChoose(kind, it) },
                label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "More options, including your own server or any OpenAI-compatible model, are in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Point(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(if (i == step) 10.dp else 8.dp)
                    .background(if (i == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        }
    }
}

/** Google Messages, the only app Android lets use RCS. */
private const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
