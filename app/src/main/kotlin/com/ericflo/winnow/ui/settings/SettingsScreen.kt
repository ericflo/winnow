package com.ericflo.winnow.ui.settings

import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.FilteredPhrases
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.VerdictSource
import com.ericflo.winnow.data.ProviderKind
import com.ericflo.winnow.data.ProviderSettings
import com.ericflo.winnow.data.WinnowSettings
import com.ericflo.winnow.ui.review.ReviewSettingsRow
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import com.ericflo.winnow.data.TextScale
import com.ericflo.winnow.data.ThemeMode
import com.ericflo.winnow.ui.components.scaled
import kotlin.math.roundToInt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.DpOffset
import androidx.compose.material.icons.filled.Check
import com.ericflo.winnow.data.SwipeChoice
import androidx.compose.foundation.selection.toggleable

private val Action.label: String
    get() = when (this) {
        Action.ALLOW -> "Notify"
        Action.SILENCE -> "Silence"
        Action.FILTER -> "Filter"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onMakeDefault: () -> Unit, onOpenSenderRules: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isDefault by viewModel.isDefault.collectAsStateWithLifecycle()
    val trial by viewModel.trial.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val canBackUpMessages by viewModel.canBackUpMessages.collectAsStateWithLifecycle()
    val learned by viewModel.learned.collectAsStateWithLifecycle()
    val providerLearned by viewModel.providerLearned.collectAsStateWithLifecycle()
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget what Winnow learned?") },
            text = {
                Text(
                    "The on-phone model goes back to how it shipped, without your $learned labels and corrections (or any a classifier service gave). " +
                        "Conversations stay where you put them. This can't be undone.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmForget = false; viewModel.forgetLearning() }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep") } },
        )
    }
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text(if (isDefault) "Winnow is your SMS app" else "Winnow isn't your SMS app yet") },
                    supportingContent = {
                        Text(
                            if (isDefault) "Incoming texts are classified before you're notified."
                            else "Android only lets the default SMS app filter incoming texts.",
                        )
                    },
                    trailingContent = if (isDefault) null else {
                        { FilledTonalButton(onClick = onMakeDefault) { Text("Set") } }
                    },
                )
            }

            section("Classifier")
            item {
                Text(
                    "Winnow asks one question per message: what kind of message is this? Any provider below can answer it, and you can switch at any time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ProviderKind.entries.forEach { kind ->
                item(kind.name) {
                    ProviderOption(kind, selected = s.provider == kind, onSelect = { viewModel.selectProvider(kind) })
                    if (s.provider == kind && kind != ProviderKind.ON_DEVICE) {
                        ProviderFields(kind, s.settingsFor(kind), onSave = { viewModel.saveProvider(kind, it) })
                    }
                }
            }

            if (learned > 0 || providerLearned > 0) {
                item("learned") {
                    ListItem(
                        headlineContent = { Text("Learned from your labels and corrections") },
                        supportingContent = {
                            Column {
                                Text(
                                    "${if (learned == 1) "1 text you labeled or corrected teaches" else "$learned texts you labeled or corrected teach"} the on-phone model " +
                                        "about texts like them. It keeps word fingerprints, never the messages. Forget clears what it learned; " +
                                        "conversations stay where you put them.",
                                )
                                if (providerLearned > 0) {
                                    Text(
                                        "Also $providerLearned texts from your backlog labeled by a classifier service (Train Winnow). They count for less than yours.",
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                    TextButton(onClick = viewModel::forgetProviderLabels, contentPadding = PaddingValues(0.dp)) { Text("Forget just those") }
                                }
                            }
                        },
                        trailingContent = { TextButton(onClick = { confirmForget = true }) { Text("Forget") } },
                    )
                }
            }

            section("Try it")
            item { TrialCard(trial, onClassify = viewModel::tryClassify) }

            // What the classifier's answers lead to, and the user's own rules on top of them.
            section("Filtering")
            item("categories-explainer") {
                Text(
                    "What happens to each kind of message",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Category.entries.forEach { category ->
                item(category.key) {
                    CategoryActionRow(category, s.categoryActions[category] ?: category.defaultAction) {
                        viewModel.setAction(category, it)
                    }
                }
            }
            item("sender-rules") {
                ListItem(
                    headlineContent = { Text("Sender rules") },
                    supportingContent = { Text("Senders you've always allowed or always filtered") },
                    modifier = Modifier.clickable(onClick = onOpenSenderRules),
                )
            }
            item("filtered-phrases") { FilteredPhrasesRow(s.filteredPhrases, viewModel::setFilteredPhrases) }
            item("clear-old-filtered") {
                SwitchRow(
                    "Clear out old filtered texts",
                    "Filtered conversations untouched for a month go to Recently deleted, where they can still be restored for 30 days. Never one you've written in, pinned or starred.",
                    s.clearOldFiltered,
                    onChange = viewModel::setClearOldFiltered,
                )
            }
            item("daily-summary") {
                SwitchRow(
                    "Daily summary",
                    "Each evening, how many texts were kept out of your inbox or arrived quietly. Nothing on a quiet day.",
                    s.dailySummary,
                    onChange = viewModel::setDailySummary,
                )
            }

            if (isDefault) {
                section("Older conversations")
                item("review") { ReviewSettingsRow(review, s.provider.label, onStart = viewModel::startReview) }
            }

            // What a classifier service is sent, and which messages never leave the phone.
            section("Privacy")
            privacyItems(s, viewModel)

            section("Display")
            item("theme") {
                ListItem(
                    headlineContent = { Text("Theme") },
                    supportingContent = {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEachIndexed { i, (mode, label) ->
                                SegmentedButton(
                                    selected = s.theme == mode,
                                    onClick = { viewModel.setTheme(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                                ) { Text(label) }
                            }
                        }
                    },
                )
            }
            item("text-size") { TextSizeRow(s.textScale, viewModel::setTextScale) }
            item("swipe-right") { SwipeChoiceRow("Swipe right", s.swipeRight) { viewModel.setSwipe(right = true, value = it) } }
            item("swipe-left") { SwipeChoiceRow("Swipe left", s.swipeLeft) { viewModel.setSwipe(right = false, value = it) } }

            section("Messages")
            item("notifications") {
                ListItem(
                    headlineContent = { Text("Notifications") },
                    supportingContent = { Text("Sound, vibration and how messages appear") },
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                )
            }
            item("undo-send") {
                ListItem(
                    headlineContent = { Text("Undo send") },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Wait a few seconds before sending, so a message can be taken back")
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf(0 to "Off", 5 to "5 seconds", 10 to "10 seconds").forEachIndexed { i, (seconds, label) ->
                                    SegmentedButton(
                                        selected = s.undoSendSeconds == seconds,
                                        onClick = { viewModel.setUndoSend(seconds) },
                                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                                    ) { Text(label) }
                                }
                            }
                        }
                    },
                )
            }
            item("delete-codes") {
                SwitchRow(
                    "Delete verification codes after a day",
                    "One-time codes from services, not people, are deleted 24 hours after they arrive. Starred codes are kept.",
                    s.deleteOldCodes,
                    onChange = viewModel::setDeleteOldCodes,
                )
            }
            item("blocked") {
                ListItem(
                    headlineContent = { Text("Blocked numbers") },
                    supportingContent = { Text("Android's own list: blocked numbers can't text or call you") },
                    modifier = Modifier.clickable {
                        runCatching { context.startActivity(context.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()) }
                    },
                )
            }
            item("delivery-reports") {
                SwitchRow(
                    "Delivery reports",
                    "Ask your carrier to confirm each text and MMS arrived, and show \"Delivered\"",
                    s.deliveryReports,
                    onChange = viewModel::setDeliveryReports,
                )
            }
            item("nudges") {
                SwitchRow(
                    "Reply and birthday reminders",
                    "A contact's question you haven't answered in two days, or yours they haven't, comes back to the top of your inbox. So does a contact on their birthday.",
                    s.nudges,
                    onChange = viewModel::setNudges,
                )
            }
            item("simple-characters") {
                SwitchRow(
                    "Simple characters",
                    "A curly quote, long dash or some accents cut a text from 160 characters to 70. " +
                        "When that would take more texts, plain ones are sent instead.",
                    s.simpleCharacters,
                    onChange = viewModel::setSimpleCharacters,
                )
            }
            item("enter-to-send") {
                SwitchRow(
                    "Enter sends",
                    "Enter sends the message instead of starting a new line; Shift+Enter still does. Handy with a keyboard.",
                    s.enterToSend,
                    onChange = viewModel::setEnterToSend,
                )
            }
            item("quick-replies") { QuickRepliesRow(s.quickReplies, viewModel::setQuickReplies) }
            item("suggested-replies") {
                SwitchRow(
                    "Suggested replies",
                    "Reply ideas above the keyboard, from Android's on-device text classifier, and Android's own on notifications where it offers them. Nothing leaves your phone, and never for filtered texts.",
                    s.suggestedReplies,
                    onChange = viewModel::setSuggestedReplies,
                )
            }
            item("auto-save-media") {
                SwitchRow(
                    "Save received photos and videos",
                    "To the phone's Pictures and Movies, from your contacts and people you've texted. Never from filtered or silenced texts.",
                    s.autoSaveMedia,
                    onChange = viewModel::setAutoSaveMedia,
                )
            }
            item("link-previews") {
                SwitchRow(
                    "Link previews",
                    "A title and picture for links from people in your contacts or that you've texted. Never for strangers or filtered texts, since fetching a link tells the site your IP address.",
                    s.linkPreviews,
                    onChange = viewModel::setLinkPreviews,
                )
            }
            item("auto-download") {
                SwitchRow(
                    "Auto-download MMS",
                    "Fetch photos and videos as they arrive. Off, they wait for a tap.",
                    s.autoDownloadMms,
                    onChange = viewModel::setAutoDownloadMms,
                )
            }
            item("auto-download-roaming") {
                SwitchRow(
                    "Auto-download MMS when roaming",
                    "Roaming data can cost extra",
                    s.autoDownloadMms && s.autoDownloadMmsRoaming,
                    enabled = s.autoDownloadMms,
                    onChange = viewModel::setAutoDownloadMmsRoaming,
                )
            }

            section("Security")
            item("app-lock") {
                val secure = viewModel.deviceIsSecure()
                // Stays switchable while on, so it can be turned off even after the screen lock is
                // removed, rather than quietly coming back when one is set again.
                SwitchRow(
                    "Lock Winnow",
                    when {
                        secure -> "Ask for your fingerprint, face or screen lock to open Winnow after a minute away"
                        s.appLock -> "Paused: set a screen lock in Android's settings to use it"
                        else -> "Set a screen lock in Android's settings first"
                    },
                    s.appLock,
                    enabled = secure || s.appLock,
                    onChange = viewModel::setAppLock,
                )
            }
            item("hide-lock-screen") {
                SwitchRow(
                    "Hide texts on the lock screen",
                    "New-message notifications only appear once the phone is unlocked",
                    s.hideOnLockScreen,
                    onChange = viewModel::setHideOnLockScreen,
                )
            }

            section("Backup")
            item("backup") { BackupSection(backup, isDefault, canBackUpMessages, viewModel) }

            section("About")
            item("problems") {
                val problems by viewModel.problems.collectAsStateWithLifecycle()
                ProblemsRow(
                    problems,
                    onShare = { runCatching { context.startActivity(viewModel.shareProblems()) } },
                    onClear = viewModel::clearProblems,
                )
            }
            item("version") {
                // What a bug report needs first.
                val info = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() }
                ListItem(
                    headlineContent = { Text("Winnow ${info?.versionName.orEmpty()}") },
                    supportingContent = { Text("Build ${info?.longVersionCode ?: "?"} · ${context.packageName}") },
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private fun LazyListScope.section(title: String) {
    item("section-$title") {
        Column {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun ProviderOption(kind: ProviderKind, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.padding(top = 10.dp, end = 16.dp)) {
            Text(kind.label, style = MaterialTheme.typography.bodyLarge)
            Text(kind.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProviderFields(kind: ProviderKind, saved: ProviderSettings, onSave: (ProviderSettings) -> Unit) {
    var apiKey by rememberSaveable(kind, saved) { mutableStateOf(saved.apiKey) }
    var model by rememberSaveable(kind, saved) { mutableStateOf(saved.model) }
    var baseUrl by rememberSaveable(kind, saved) { mutableStateOf(saved.baseUrl) }
    var zeroRetention by rememberSaveable(kind, saved) { mutableStateOf(saved.zeroRetention) }
    val edited = ProviderSettings(apiKey.trim(), model.trim(), baseUrl.trim(), zeroRetention)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 64.dp, end = 16.dp, bottom = 12.dp),
    ) {
        if (kind.needsBaseUrl) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                placeholder = { Text(if (kind == ProviderKind.CHAT_COMPLETIONS) "https://host/v1" else "https://host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(if (kind.needsApiKey) "API key" else "API key (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            placeholder = { Text(kind.defaultModel.ifBlank { "model id" }) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.toggleable(value = zeroRetention, role = Role.Switch) { zeroRetention = it },
        ) {
            Text(
                "This provider keeps no message data (zero retention)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = zeroRetention, onCheckedChange = null)
        }
        Button(onClick = { onSave(edited) }, enabled = edited != saved) { Text("Save") }
    }
}

@Composable
private fun TrialCard(trial: TrialState, onClassify: (sender: String, body: String) -> Unit) {
    var sender by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Message") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sender,
            onValueChange = { sender = it },
            label = { Text("From (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onClassify(sender, body) }, enabled = body.isNotBlank() && trial != TrialState.Running) { Text("Classify") }
            if (trial == TrialState.Running) {
                CircularProgressIndicator(Modifier.padding(start = 16.dp).size(24.dp), strokeWidth = 3.dp)
            }
        }
        if (trial is TrialState.Done) TrialResult(trial)
    }
}

@Composable
private fun TrialResult(result: TrialState.Done) {
    val v = result.verdict
    val origin = when (val source = v.source) {
        is VerdictSource.Rule -> source.reason
        is VerdictSource.Provider -> buildString {
            append("Answered by ${ProviderKind.labelFor(source.providerId)}")
            source.model?.let { append(" ($it)") }
            if (v.costUsd > 0) append(" for $${"%.5f".format(v.costUsd)}")
        }
        is VerdictSource.OnDevice -> buildString {
            append("Decided on this phone")
            if (source.reasons.isNotEmpty()) append(": ${source.reasons.joinToString(", ")}")
            source.fallbackReason?.let { append(". $it") }
        }
        is VerdictSource.Heuristic -> "On-device keywords: ${source.reason}"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val percent = if (v.confidence < 1.0) " · ${(v.confidence * 100).toInt()}%" else ""
            Text("${v.category?.label ?: "Sender rule"}$percent → ${v.action.label}", style = MaterialTheme.typography.titleMedium)
            Text(origin, style = MaterialTheme.typography.bodySmall)
            if (v.distribution.isNotEmpty()) {
                Text(
                    v.distribution.entries.sortedByDescending { it.value }.take(3)
                        .joinToString("   ") { "${it.key.label} ${(it.value * 100).toInt()}%" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.payload != null) {
                Text(
                    if (v.source is VerdictSource.Provider) "What the provider saw" else "What Winnow tried to send",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(result.payload, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            } else {
                Text("Nothing left this phone.", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun LazyListScope.privacyItems(s: WinnowSettings, vm: SettingsViewModel) {
    val p = s.privacy
    if (s.provider != ProviderKind.ON_DEVICE) {
        item("p-local-first") {
            SwitchRow(
                "Decide on this phone when it's sure",
                "Texts Winnow's built-in model is very sure about are never sent to ${s.provider.label}. Fewer texts leave your phone.",
                s.decideOnPhoneWhenSure,
                onChange = vm::setDecideOnPhoneWhenSure,
            )
        }
    }
    item("p-contacts") {
        SwitchRow("Keep contacts' messages on this phone", "Texts from saved contacts are always delivered and never sent to a provider.", !p.classifyContacts) { on ->
            vm.setPrivacy { it.copy(classifyContacts = !on) }
        }
    }
    item("p-known") {
        SwitchRow("Keep known conversations on this phone", "Same for anyone you've texted before.", !p.classifyKnownConversations) { on ->
            vm.setPrivacy { it.copy(classifyKnownConversations = !on) }
        }
    }
    item("p-codes") {
        SwitchRow("Keep verification codes on this phone", "One-time codes are recognized locally and delivered.", !p.classifyVerificationCodes) { on ->
            vm.setPrivacy { it.copy(classifyVerificationCodes = !on) }
        }
    }
    item("p-sender") {
        SwitchRow("Share the sender's number", "Off: the provider only learns whether it's a phone number, short code or name.", p.shareSenderAddress) { on ->
            vm.setPrivacy { it.copy(shareSenderAddress = on) }
        }
    }
    item("p-digits") {
        SwitchRow("Mask numbers and codes", "Runs of 4 or more digits are sent as ####.", p.redaction.maskDigitRuns) { on ->
            vm.setPrivacy { it.copy(redaction = it.redaction.copy(maskDigitRuns = on)) }
        }
    }
    item("p-emails") {
        SwitchRow("Mask email addresses", "Sent as [email].", p.redaction.maskEmails) { on ->
            vm.setPrivacy { it.copy(redaction = it.redaction.copy(maskEmails = on)) }
        }
    }
    item("p-links") {
        SwitchRow("Send links as their domain only", "The domain is what gives phishing away; the path often identifies you.", p.redaction.stripUrlPaths) { on ->
            vm.setPrivacy { it.copy(redaction = it.redaction.copy(stripUrlPaths = on)) }
        }
    }
    item("p-zdr") {
        SwitchRow("Zero-retention providers only", "Skip any provider you haven't marked as keeping no data.", s.zdrOnly) { on ->
            vm.setZdrOnly(on)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // One control: TalkBack reads "title, subtitle, switch, on" instead of stopping twice.
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun CategoryActionRow(category: Category, action: Action, onSelect: (Action) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(category.label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Action.entries.forEachIndexed { i, a ->
                SegmentedButton(
                    selected = a == action,
                    onClick = { onSelect(a) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = Action.entries.size),
                ) { Text(a.label) }
            }
        }
    }
}

/** A slider for message text size, with a sample bubble drawn at the chosen size. */
@Composable
private fun TextSizeRow(scale: Float, onChange: (Float) -> Unit) {
    var value by remember(scale) { mutableFloatStateOf(scale) }
    val colors = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text("Message text size") },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You can also pinch a conversation to zoom")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("A", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = value,
                        // Snaps back to the default near the middle, so it's easy to return to.
                        onValueChange = { value = TextScale.settle(it) },
                        onValueChangeFinished = { onChange(value) },
                        valueRange = TextScale.MIN..TextScale.MAX,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                            .semantics {
                                contentDescription = "Message text size"
                                stateDescription = "${(value * 100).roundToInt()}%"
                            },
                    )
                    Text("A", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    "See you at 7! I'll bring snacks.",
                    style = MaterialTheme.typography.bodyLarge.scaled(value),
                    color = colors.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.surfaceContainerHigh)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        },
    )
}

/** What swiping an inbox conversation one way does, picked from a menu. */
@Composable
private fun SwipeChoiceRow(title: String, choice: SwipeChoice, onChange: (SwipeChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(choice.label) },
            modifier = Modifier.clickable(onClickLabel = "Change") { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(16.dp, 0.dp)) {
            SwipeChoice.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    trailingIcon = { if (option == choice) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                    onClick = {
                        open = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

/** Words that send a stranger's text straight to Filtered, decided on the phone. */
@Composable
private fun FilteredPhrasesRow(phrases: List<String>, onSave: (List<String>) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Filtered words") },
        supportingContent = {
            Text(
                if (phrases.isEmpty()) "Texts from people you don't know that use a word or phrase you add here go to Filtered, without being sent anywhere."
                else "${phrases.size} filtered: ${phrases.joinToString(" · ")}",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier.clickable { editing = true },
    )
    if (!editing) return
    // Survives a rotation with the dialog open, edits and all.
    var list by rememberSaveable(phrases, stateSaver = listSaver<List<String>, String>({ it }, { it })) { mutableStateOf(phrases) }
    var adding by rememberSaveable { mutableStateOf("") }
    fun add() {
        val phrase = FilteredPhrases.normalize(adding)
        if (phrase.isNotEmpty() && list.none { it.equals(phrase, ignoreCase = true) }) list = list + phrase
        adding = ""
    }
    AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("Filtered words") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Whole words, any capitalization: \"vote\" filters \"VOTE today\" but not \"devoted\". Contacts and people you've texted aren't affected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                list.forEach { phrase ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(phrase, modifier = Modifier.weight(1f))
                        IconButton(onClick = { list = list - phrase }) { Icon(Icons.Filled.Close, contentDescription = "Remove \"$phrase\"") }
                    }
                }
                OutlinedTextField(
                    value = adding,
                    onValueChange = { adding = it.take(FilteredPhrases.MAX_LENGTH) },
                    label = { Text("Word or phrase") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    trailingIcon = {
                        IconButton(onClick = ::add, enabled = adding.isNotBlank()) { Icon(Icons.Filled.Add, contentDescription = "Add") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                add()
                onSave(list)
                editing = false
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
    )
}

/** The quick replies, edited in a dialog: take some out, add your own. */
@Composable
private fun QuickRepliesRow(replies: List<String>, onSave: (List<String>) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Quick replies") },
        supportingContent = {
            Text(
                if (replies.isEmpty()) "None. Add some to answer from a notification in one tap."
                else "${replies.size} ready: in the composer's + menu and on notifications. ${replies.joinToString(" · ")}",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier.clickable { editing = true },
    )
    if (!editing) return
    var list by remember(replies) { mutableStateOf(replies) }
    var adding by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("Quick replies") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEach { reply ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(reply, modifier = Modifier.weight(1f))
                        IconButton(onClick = { list = list - reply }) { Icon(Icons.Filled.Close, contentDescription = "Remove \"$reply\"") }
                    }
                }
                OutlinedTextField(
                    value = adding,
                    onValueChange = { adding = it.take(160) },
                    label = { Text("New reply") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { list = list + adding.trim(); adding = "" }, enabled = adding.isNotBlank()) {
                            Icon(Icons.Filled.Add, contentDescription = "Add")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(if (adding.isNotBlank()) list + adding.trim() else list)
                editing = false
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
    )
}

/**
 * Crashes and freezes recorded on this phone, and the report to share. A report can carry bits
 * of what was being handled when it went wrong, so it only leaves the phone when shared.
 */
@Composable
private fun ProblemsRow(problems: List<com.ericflo.winnow.diagnostics.ProblemLog.Problem>, onShare: () -> Unit, onClear: () -> Unit) {
    ListItem(
        headlineContent = { Text("Problems on this phone") },
        supportingContent = {
            Column {
                if (problems.isEmpty()) {
                    Text("None recorded. If Winnow crashes or stops responding, the details are kept here, on this phone, for you to share.")
                } else {
                    val latest = problems.first()
                    val at = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                        .format(java.time.Instant.ofEpochMilli(latest.at).atZone(java.time.ZoneId.systemDefault()))
                    Text(
                        "${if (problems.size == 1) "1 recorded" else "${problems.size} recorded"}, the latest a ${latest.kind.label.lowercase()} on $at. " +
                            "The report stays on this phone unless you share it, and can include bits of what Winnow was handling.",
                    )
                    Row {
                        TextButton(onClick = onShare) { Text("Share report") }
                        TextButton(onClick = onClear) { Text("Clear") }
                    }
                }
            }
        },
    )
}
