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
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Bumped to read the block list again after a change here.
    var blockedVersion by remember { mutableIntStateOf(0) }
    val blocked by produceState<List<String>?>(null, blockedVersion) { value = container.blockedNumbers.all() }
    // A count, not the Unit each change is: equal keys wouldn't read the names again.
    val contactsChanged by remember { container.contacts.changes().runningFold(0) { n, _ -> n + 1 } }.collectAsStateWithLifecycle(0)
    // Names and photos off the main thread: the first lookup reads the whole contact list.
    val people by produceState(emptyMap<String, Sender>(), rules, blocked, contactsChanged) {
        val addresses = rules.orEmpty().map { it.address } + blocked.orEmpty()
        value = withContext(Dispatchers.IO) {
            addresses.associateWith { a ->
                Sender(a, container.contacts.displayName(a) ?: ContactLookup.formatAddress(a), container.contacts.photoUri(a))
            }
        }
    }
    fun sender(address: String) = people[address] ?: Sender(address, ContactLookup.formatAddress(address), null)

    fun remove(rule: SenderRuleEntity) = scope.launch {
        dao.deleteSenderRule(rule.address)
        val result = snackbar.showSnackbar("Rule for ${sender(rule.address).name} removed", actionLabel = "Undo")
        if (result == SnackbarResult.ActionPerformed) dao.upsertSenderRule(rule)
    }

    fun unblock(number: String) = scope.launch {
        container.blockedNumbers.unblock(number)
        blockedVersion++
        val result = snackbar.showSnackbar("${sender(number).name} unblocked", actionLabel = "Undo")
        if (result == SnackbarResult.ActionPerformed) {
            container.blockedNumbers.block(number)
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
