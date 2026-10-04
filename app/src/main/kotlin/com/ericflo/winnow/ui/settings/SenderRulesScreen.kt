package com.ericflo.winnow.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.SenderRule
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.db.SenderRuleEntity
import com.ericflo.winnow.ui.components.Avatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect

/** One row: who, as the rest of the app shows them. */
private data class Sender(val address: String, val name: String, val photoUri: String?)

/** Every "always allow" and "always filter" decision, and Android's own block list, each removable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderRulesScreen(container: AppContainer, onBack: () -> Unit) {
    val dao = container.verdictDao
    val rules by remember { dao.observeSenderRules() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // Bumped to read the block list again: after a change here, and on coming back (the phone's
    // own settings can change it too).
    var blockedVersion by remember { mutableIntStateOf(0) }
    val blocked by produceState<List<String>?>(null, blockedVersion) { value = container.blockedNumbers.all() }
    // Bumped when contacts change, while here or while away (a name added in Contacts, say): a
    // count, since equal keys wouldn't read the names again.
    var contactsVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { container.contacts.changes().collect { contactsVersion++ } }
    LifecycleResumeEffect(Unit) {
        contactsVersion++
        blockedVersion++
        onPauseOrDispose { }
    }
    // Names and photos off the main thread (the first lookup reads the whole contact list),
    // added to what's known so a row that comes back (Undo) keeps its name meanwhile.
    val people by produceState<Map<String, Sender>?>(null, rules, blocked, contactsVersion) {
        val addresses = rules.orEmpty().map { it.address } + blocked.orEmpty()
        val found = withContext(Dispatchers.IO) {
            addresses.associateWith { a ->
                Sender(a, container.contacts.displayName(a) ?: ContactLookup.formatAddress(a), container.contacts.photoUri(a))
            }
        }
        value = value.orEmpty() + found
    }
    fun sender(address: String) = people?.get(address) ?: Sender(address, ContactLookup.formatAddress(address), null)

    // One Undo at a time, the latest: a waiting one would sit behind it, and its Undo look like this one's.
    suspend fun offerUndo(message: String): Boolean {
        snackbar.currentSnackbarData?.dismiss()
        return snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed
    }

    fun remove(rule: SenderRuleEntity) = scope.launch {
        dao.deleteSenderRule(rule.address)
        if (offerUndo("Rule for ${sender(rule.address).name} removed")) dao.upsertSenderRule(rule)
    }

    fun unblock(number: String) = scope.launch {
        val before = container.blockedNumbers.all()
        if (!container.blockedNumbers.unblock(number)) {
            snackbar.showSnackbar("Couldn't unblock it: only your SMS app can change the block list")
            return@launch
        }
        // Every way the number was written goes; Undo puts back each one.
        val removed = before - container.blockedNumbers.all().toSet()
        blockedVersion++
        if (offerUndo("${sender(number).name} unblocked")) {
            removed.ifEmpty { listOf(number) }.forEach { container.blockedNumbers.block(it) }
            blockedVersion++
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Sender rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        // Nothing until both lists are read, so "No sender rules yet" never flashes by.
        val loadedRules = rules ?: return@Scaffold
        val loadedBlocked = blocked ?: return@Scaffold
        // And the names, so rows don't show numbers first and then change.
        if (people == null) return@Scaffold
        val (allowed, filtered) = loadedRules.partition { it.rule == SenderRule.ALWAYS_ALLOW.name }
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            ruleSection("Always allowed", "Every text reaches your inbox", allowed, ::sender, ::remove)
            ruleSection("Always filtered", "Every text goes to Filtered, with no notification", filtered, ::sender, ::remove)
            if (loadedBlocked.isNotEmpty()) {
                item("blocked-header") { Heading("Blocked by Android") }
                items(loadedBlocked, key = { "blocked-$it" }) { number ->
                    SenderRow(sender(number), "Texts and calls are dropped before any app sees them", "Unblock") { unblock(number) }
                }
            }
            if (loadedRules.isEmpty() && loadedBlocked.isEmpty()) {
                item {
                    Text(
                        "No sender rules yet. Tap \"Not spam\" or \"Filter sender\" in a conversation to add one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.ruleSection(
    title: String,
    subtitle: String,
    rules: List<SenderRuleEntity>,
    sender: (String) -> Sender,
    onRemove: (SenderRuleEntity) -> Unit,
) {
    if (rules.isEmpty()) return
    item("header-$title") { Heading(title) }
    items(rules, key = { it.address }) { rule -> SenderRow(sender(rule.address), subtitle, "Remove rule") { onRemove(rule) } }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SenderRow(sender: Sender, subtitle: String, removeLabel: String, onRemove: () -> Unit) {
    ListItem(
        leadingContent = { Avatar(sender.name, seed = sender.address, size = 40.dp, photoUri = sender.photoUri) },
        headlineContent = { Text(sender.name) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = removeLabel) }
        },
    )
}
