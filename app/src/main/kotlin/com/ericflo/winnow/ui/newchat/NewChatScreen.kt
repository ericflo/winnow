package com.ericflo.winnow.ui.newchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.R
import com.ericflo.winnow.data.ContactEntry
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.isEmailAddress
import com.ericflo.winnow.data.normalizeAddress
import com.ericflo.winnow.ui.components.Avatar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

data class NewChatUiState(
    val query: String = "",
    /** Contacts matching [query], grouped by initial. */
    val groups: List<Pair<String, List<ContactEntry>>> = emptyList(),
    /** [query] when it can be texted directly. */
    val dialable: String? = null,
    /** Picking several people for a group conversation. */
    val groupMode: Boolean = false,
    val picked: List<ContactEntry> = emptyList(),
    /** The people texted most recently, one to one: first, while nothing's typed. */
    val recent: List<ContactEntry> = emptyList(),
)

class NewChatViewModel(
    container: AppContainer,
    /** People already in it, from "Add people": a new group starts with them picked. */
    startWith: List<String> = emptyList(),
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val contacts = MutableStateFlow<List<ContactEntry>>(emptyList())
    private val recent = MutableStateFlow<List<ContactEntry>>(emptyList())
    private val groupMode = MutableStateFlow(startWith.isNotEmpty())
    private val picked = MutableStateFlow(
        startWith.map { ContactEntry(container.messages.displayName(it), it, container.messages.photoUri(it)) },
    )

    init {
        viewModelScope.launch { contacts.value = container.contactsSource.all() }
        viewModelScope.launch {
            val repo = container.messages
            recent.value = runCatching { repo.conversations().first() }.getOrDefault(emptyList())
                // People: not short codes or named senders (codes, banks, deliveries), and each once,
                // even if a group's thread ever lists just one of them.
                .filter { it.recipients.size == 1 && !it.isFiltered && ContactLookup.isReachable(it.recipients.single()) }
                .sortedByDescending { it.timestamp }
                .distinctBy { normalizeAddress(it.recipients.single()) }
                .take(RECENT)
                .map { c -> ContactEntry(c.displayName, c.recipients.single(), c.photoUri) }
        }
    }

    val state: StateFlow<NewChatUiState> = combine(query, contacts, groupMode, picked, recent) { q, all, group, picked, recent ->
        // A contact's email addresses only when looked for: the full list stays one row per number.
        val matches = if (q.isBlank()) all.filterNot { isEmailAddress(it.number) } else all.filter { it.matches(q) }
        NewChatUiState(
            query = q,
            groups = matches.groupBy { c -> c.name.first().uppercaseChar().takeIf { it.isLetter() }?.toString() ?: "#" }.toList(),
            dialable = dialable(q),
            groupMode = group,
            picked = picked,
            recent = recent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewChatUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun startGroup() {
        groupMode.value = true
    }

    /** Adds or removes someone from the group being assembled. */
    fun toggle(contact: ContactEntry) {
        val current = picked.value
        picked.value = if (current.any { it.number == contact.number }) current.filterNot { it.number == contact.number } else current + contact
    }

    private fun ContactEntry.matches(q: String): Boolean {
        if (isEmailAddress(number)) return name.contains(q, ignoreCase = true) || number.contains(q.trim(), ignoreCase = true)
        val digits = q.filter(Char::isDigit)
        return name.contains(q, ignoreCase = true) || (digits.isNotEmpty() && number.filter(Char::isDigit).contains(digits))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(viewModel: NewChatViewModel, onBack: () -> Unit, onStart: (recipients: List<String>) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The field shows what's typed straight from here: echoed back through the ViewModel's
    // combined flows, fast typing would drop and reorder characters.
    var typed by rememberSaveable { mutableStateOf("") }
    val type = { value: String -> typed = value; viewModel.setQuery(value) }
    // Restored after the app was closed, the field keeps its text; the ViewModel starts over.
    LaunchedEffect(Unit) { viewModel.setQuery(typed) }
    val choose = { contact: ContactEntry ->
        if (state.groupMode) {
            viewModel.toggle(contact)
            type("")
        } else {
            onStart(listOf(contact.number))
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.groupMode) "New group" else "New chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (state.groupMode) {
                        TextButton(onClick = { onStart(state.picked.map { it.number }) }, enabled = state.picked.size >= 2) { Text("Next") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize().imePadding()) {
            item("to") {
                // From what's typed right now; the ViewModel's view of it can be a frame behind.
                ToField(typed, type, onDone = { dialable(typed)?.let { choose(ContactEntry(ContactLookup.formatAddress(it), it)) } })
            }
            if (state.groupMode) {
                // Always there in a new group, a hint until someone is picked: the first pick
                // mustn't push the list down, under a finger already reaching for the next.
                item("picked") {
                    if (state.picked.isEmpty()) {
                        Text(
                            "Pick two or more people",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp).heightIn(min = PICKED_ROW_HEIGHT).wrapContentHeight(Alignment.CenterVertically),
                        )
                        return@item
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).heightIn(min = PICKED_ROW_HEIGHT),
                    ) {
                        state.picked.forEach { contact ->
                            InputChip(
                                selected = true,
                                onClick = { viewModel.toggle(contact) },
                                label = { Text(contact.name) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
            }
            if (!state.groupMode && state.query.isBlank()) {
                item("create-group") {
                    FilledTonalButton(
                        onClick = viewModel::startGroup,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_group), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Create group")
                    }
                }
            }
            state.dialable?.let { number ->
                item("dial") {
                    ContactCard(
                        title = "${if (state.groupMode) "Add" else "Send to"} ${ContactLookup.formatAddress(number)}",
                        subtitle = null,
                        avatarName = number,
                        seed = number,
                        shape = RoundedCornerShape(24.dp),
                        onClick = { choose(ContactEntry(ContactLookup.formatAddress(number), number)) },
                    )
                }
            }
            if (state.query.isBlank() && state.recent.isNotEmpty()) {
                item("h-recent") {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 8.dp))
                }
                itemsIndexed(state.recent, key = { _, c -> "r-${c.number}" }) { i, contact ->
                    ContactCard(
                        title = contact.name,
                        // Not in contacts, the name is the number: once is enough.
                        subtitle = ContactLookup.formatAddress(contact.number).takeIf { it != contact.name },
                        avatarName = contact.name,
                        seed = contact.number,
                        photoUri = contact.photoUri,
                        shape = groupShape(i, state.recent.size),
                        checked = state.groupMode && state.picked.any { it.number == contact.number },
                        onClick = { choose(contact) },
                    )
                }
            }
            state.groups.forEach { (letter, contacts) ->
                item("h-$letter") {
                    Text(
                        letter,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(contacts, key = { _, c -> "c-${c.name}-${c.number}" }) { i, contact ->
                    ContactCard(
                        title = contact.name,
                        subtitle = ContactLookup.formatAddress(contact.number),
                        avatarName = contact.name,
                        seed = contact.number,
                        photoUri = contact.photoUri,
                        shape = groupShape(i, contacts.size),
                        checked = state.groupMode && state.picked.any { it.number == contact.number },
                        onClick = { choose(contact) },
                    )
                }
            }
            if (state.groups.isEmpty() && state.dialable == null) {
                item("empty") {
                    Text(
                        if (state.query.isBlank()) "Type a phone number or email address to start" else "No contacts match",
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

@Composable
private fun ToField(query: String, onQueryChange: (String) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Letters for names, or the dial pad for a number, as Messages offers.
    var dialPad by rememberSaveable { mutableStateOf(false) }
    // Shown again if it had been put away: the point of the button is the keyboard.
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(64.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 8.dp)) {
            Text("To:", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(16.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(if (dialPad) "Phone number" else "Name, phone number or email", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = if (dialPad) KeyboardType.Phone else KeyboardType.Text, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onDone() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            IconButton(onClick = { dialPad = !dialPad; focus.requestFocus(); keyboard?.show() }) {
                Icon(
                    painterResource(if (dialPad) R.drawable.ic_keyboard else R.drawable.ic_dialpad),
                    contentDescription = if (dialPad) "Type a name" else "Dial a number",
                )
            }
        }
    }
}

/** The picked people's row in a new group: one line of chips (an InputChip is 32dp, plus its touch margin). */
private val PICKED_ROW_HEIGHT = 48.dp

/** Grouped-list corners: rounded outer edges, tight seams between neighbors. */
private fun groupShape(index: Int, count: Int): RoundedCornerShape {
    val big = 24.dp
    val small = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small,
        topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small,
        bottomEnd = if (index == count - 1) big else small,
    )
}

@Composable
private fun ContactCard(
    title: String,
    subtitle: String?,
    avatarName: String,
    seed: String,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    checked: Boolean = false,
    photoUri: String? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Avatar(avatarName, seed = seed, size = 48.dp, photoUri = photoUri)
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (checked) Icon(Icons.Filled.CheckCircle, contentDescription = "Added", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** What's typed, if it's a phone number to text rather than a name to look up. */
/** What's typed, if it can be sent to as it is: a phone number, or an email address (by MMS). */
private fun dialable(query: String): String? {
    // A pasted "mailto:" link is the address after it, as written in lowercase (email is).
    val t = query.trim().removePrefix("mailto:").removePrefix("MAILTO:")
    if (isEmailAddress(t)) return t.lowercase()
    return t.takeIf { it.count(Char::isDigit) >= 3 && it.all { c -> c.isDigit() || c in "+()- ." } }
}

/** How many recent people New chat offers first. */
private const val RECENT = 5
