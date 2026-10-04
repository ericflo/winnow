package com.ericflo.winnow.ui.details

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.R
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.SenderRule
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.displayNameFor
import com.ericflo.winnow.data.normalizeAddress
import com.ericflo.winnow.ui.components.Avatar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailsUiState(
    val title: String,
    val people: List<Person> = emptyList(),
    val muted: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** For 1:1 conversations: the user's standing decision about this sender, if any. */
    val senderRule: SenderRule? = null,
    val blocked: Boolean = false,
    val canBlock: Boolean = false,
) {
    data class Person(val address: String, val name: String, val number: String, val photoUri: String?, val isContact: Boolean)

    val isGroup: Boolean get() = people.size > 1
}

class ConversationDetailsViewModel(
    private val container: AppContainer,
    private val threadId: Long,
    private val recipients: List<String>,
) : ViewModel() {
    private val repo = container.messages
    private val rule = MutableStateFlow<SenderRule?>(null)
    private val blocked = MutableStateFlow(false)
    private val single = recipients.singleOrNull()

    init {
        reload()
    }

    val state: StateFlow<DetailsUiState> = combine(
        container.conversationStates.observe().map { it[threadId] },
        rule,
        blocked,
    ) { s, rule, blocked ->
        DetailsUiState(
            title = displayNameFor(recipients, repo::displayName),
            people = recipients.map { address ->
                val name = repo.displayName(address)
                val number = ContactLookup.formatAddress(address)
                // A known name means a contact, for real contacts and sample conversations alike.
                DetailsUiState.Person(address, name, number, repo.photoUri(address), isContact = name != number)
            },
            muted = s?.muted == true,
            pinned = s?.pinned == true,
            archived = s?.archived == true,
            senderRule = rule,
            blocked = blocked,
            canBlock = single != null && container.blockedNumbers.available(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsUiState(displayNameFor(recipients, repo::displayName)))

    fun setMuted(value: Boolean) = launch { container.conversationStates.setMuted(threadId, value) }

    fun setPinned(value: Boolean) = launch { container.conversationStates.setPinned(setOf(threadId), value) }

    fun setArchived(value: Boolean) = launch { container.conversationStates.setArchived(setOf(threadId), value) }

    /** Null clears the rule, so the sender is classified normally again. */
    fun setRule(value: SenderRule?) = launch {
        val address = single ?: return@launch
        when (value) {
            null -> container.verdictDao.deleteSenderRule(normalizeAddress(address))
            SenderRule.ALWAYS_ALLOW -> repo.overrideVerdict(threadId, address, Action.ALLOW)
            SenderRule.ALWAYS_FILTER -> repo.overrideVerdict(threadId, address, Action.FILTER)
        }
        reload()
    }

    fun setBlocked(value: Boolean) = launch {
        val address = single ?: return@launch
        if (value) container.blockedNumbers.block(address) else container.blockedNumbers.unblock(address)
        reload()
    }

    fun delete(onDone: () -> Unit) = launch {
        repo.deleteThreads(setOf(threadId))
        container.conversationStates.forget(setOf(threadId))
        container.notifier.forget(setOf(threadId))
        onDone()
    }

    private fun reload() = launch {
        val address = single ?: return@launch
        rule.value = container.verdictDao.senderRule(normalizeAddress(address))?.let { runCatching { SenderRule.valueOf(it) }.getOrNull() }
        blocked.value = container.blockedNumbers.isBlocked(address)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailsScreen(viewModel: ConversationDetailsViewModel, onBack: () -> Unit, onDeleted: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val showContact = { number: String ->
        context.startActivity(Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT, Uri.fromParts("tel", number, null)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Details") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item("header") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    val person = state.people.singleOrNull()
                    if (person != null) {
                        Avatar(person.name, seed = person.address, size = 88.dp, photoUri = person.photoUri)
                    } else {
                        Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_group), contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(44.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(state.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    if (person != null && person.name != person.number) {
                        Text(person.number, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            section(if (state.isGroup) "${state.people.size} people" else "Contact")
            items(state.people, key = { it.address }) { person ->
                ListItem(
                    leadingContent = { Avatar(person.name, seed = person.address, size = 40.dp, photoUri = person.photoUri) },
                    headlineContent = { Text(person.name) },
                    supportingContent = { Text(if (person.isContact) person.number else "Not in your contacts · tap to add") },
                    trailingContent = {
                        IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", person.address, null))) }) {
                            Icon(Icons.Filled.Call, contentDescription = "Call ${person.name}")
                        }
                    },
                    modifier = Modifier.clickable { showContact(person.address) },
                )
            }

            section("Conversation")
            item("muted") { Toggle("Notifications", if (state.muted) "Muted" else "On", !state.muted) { viewModel.setMuted(!it) } }
            item("pinned") { Toggle("Pin to top", null, state.pinned, viewModel::setPinned) }
            item("archived") { Toggle("Archived", null, state.archived, viewModel::setArchived) }

            if (!state.isGroup) {
                section("Winnow")
                item("rule-none") { RuleRow("Classify normally", "Winnow decides each message", state.senderRule == null) { viewModel.setRule(null) } }
                item("rule-allow") { RuleRow("Always allow", "Every message reaches your inbox", state.senderRule == SenderRule.ALWAYS_ALLOW) { viewModel.setRule(SenderRule.ALWAYS_ALLOW) } }
                item("rule-filter") { RuleRow("Always filter", "Every message goes to Filtered, silently", state.senderRule == SenderRule.ALWAYS_FILTER) { viewModel.setRule(SenderRule.ALWAYS_FILTER) } }
                if (state.canBlock) {
                    item("blocked") { Toggle("Block number", "Android drops their texts and calls", state.blocked, viewModel::setBlocked) }
                }
            }

            item("delete") {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(16.dp)) {
                    Text("Delete conversation", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            text = { Text("Messages are removed from this phone. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onDeleted) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun LazyListScope.section(title: String) {
    item("section-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun RuleRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.clickable(onClick = onSelect),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
