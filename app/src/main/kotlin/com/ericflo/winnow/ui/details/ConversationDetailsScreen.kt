package com.ericflo.winnow.ui.details

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.ericflo.winnow.data.Member
import com.ericflo.winnow.data.GROUP_FACE_CANDIDATES
import com.ericflo.winnow.data.groupFaces
import com.ericflo.winnow.data.displayNameFor
import com.ericflo.winnow.data.normalizeAddress
import com.ericflo.winnow.ui.components.showOrCreateContact
import com.ericflo.winnow.ui.components.Avatar
import com.ericflo.winnow.ui.components.GroupAvatar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.ericflo.winnow.data.Attachment
import kotlinx.coroutines.flow.first
import com.ericflo.winnow.data.Transcript
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import com.ericflo.winnow.ui.components.AttachmentThumbnail
import com.ericflo.winnow.ui.components.ImageViewer
import com.ericflo.winnow.ui.components.VideoViewer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import com.ericflo.winnow.ui.components.MuteDialog
import androidx.compose.ui.text.style.TextOverflow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.ericflo.winnow.ui.components.allWebLinks
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.flowOn
import com.ericflo.winnow.ui.components.mutedLabel

/** Links listed in a conversation's details; the conversation itself has the rest. */
private const val MAX_LINKS = 20

data class DetailsUiState(
    val title: String,
    val people: List<Person> = emptyList(),
    val muted: Boolean = false,
    val mutedUntil: Long? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** For 1:1 conversations: the user's standing decision about this sender, if any. */
    val senderRule: SenderRule? = null,
    val blocked: Boolean = false,
    val canBlock: Boolean = false,
    /** The group's name, if the user gave it one. */
    val groupName: String? = null,
    /** Without contacts permission, nobody can be told apart from a stranger. */
    val canReadContacts: Boolean = true,
    /** The saved state has been read: until then the switches and choices would show defaults. */
    val loaded: Boolean = false,
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
    /** [rule] and [blocked] have been read once (a group has neither to read). */
    private val reloaded = MutableStateFlow(single == null)

    /** A conversation not yet in the message store has no notifications to configure. */
    val hasThread = threadId >= 0

    init {
        reload()
    }

    /** Loaded once for both the photos and the links. */
    private val messages = (if (threadId >= 0) repo.messages(threadId) else flowOf(emptyList()))
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** The conversation's photos and videos, newest first. */
    val media: StateFlow<List<Attachment>> = messages
        .map { messages -> messages.flatMap { m -> m.attachments.filter { it.isImage || it.isVideo } }.asReversed() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    data class Link(val url: String, val host: String, val timestamp: Long)

    /**
     * Links shared in the conversation, newest first, each once. Not from a message Winnow
     * flagged as fraud: its links are disabled in the conversation, and stay out of reach here.
     */
    val links: StateFlow<List<Link>> = messages
        .map { messages ->
            messages.asReversed().asSequence()
                .filter { it.verdict?.isFraud != true }
                .flatMap { m -> allWebLinks(m.body).map { url -> Link(url, url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url, m.timestamp) } }
                .distinctBy { it.url }
                .take(MAX_LINKS)
                .toList()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The whole conversation as a text file, in a share sheet. */
    suspend fun exportIntent(title: String): Intent? = withContext(Dispatchers.IO) {
        val messages = repo.messages(threadId).first()
        val text = Transcript.render(title, messages, nameOf = { m ->
            if (m.outgoing) "You" else repo.displayName(m.sender ?: recipients.firstOrNull().orEmpty())
        })
        container.mediaExport.shareText("Winnow - $title", text)
    }

    /** Android's notification settings for this conversation alone. */
    fun notificationSettings(title: String) =
        container.notifier.conversationSettings(threadId, recipients, title, recipients.singleOrNull()?.let(repo::photoUri))

    suspend fun save(attachment: Attachment): String =
        withContext(Dispatchers.IO) { container.mediaExport.save(attachment) }?.let { "Saved to $it" } ?: "Couldn't save that"

    suspend fun shareIntent(attachment: Attachment): Intent? = withContext(Dispatchers.IO) { container.mediaExport.shareIntent(listOf(attachment)) }

    val state: StateFlow<DetailsUiState> = combine(
        container.conversationStates.observeTimed().map { it[threadId] },
        combine(rule, blocked, reloaded) { r, b, done -> Triple(r, b, done) },
        // Names and photos read again when contacts change: someone just added shows by name.
        repo.contactChanges().onStart { emit(Unit) },
    ) { s, (rule, blocked, reloadedOnce), _ ->
        DetailsUiState(
            title = s?.title ?: displayNameFor(recipients, repo::displayName),
            groupName = s?.title,
            people = people(),
            muted = s?.isMuted() == true,
            mutedUntil = s?.takeIf { it.isMuted() }?.mutedUntil,
            pinned = s?.pinned == true,
            archived = s?.archived == true,
            senderRule = rule,
            blocked = blocked,
            canBlock = single != null && container.blockedNumbers.available(),
            canReadContacts = container.contacts.canRead(),
            // The switches and choices wait for every one of them, a blocked number's included.
            loaded = reloadedOnce,
        )
    }
        // Contact lookups can hit the disk (the whole list, after a change).
        .flowOn(Dispatchers.IO)
        // The people from the start, so a group's header doesn't flash the group glyph (or a 1:1's).
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsUiState(displayNameFor(recipients, repo::displayName), people = people()))

    private fun people() = recipients.map { address ->
        val name = repo.displayName(address)
        val number = ContactLookup.formatAddress(address)
        // Asked, not inferred from the name: a contact saved without one shows its number.
        DetailsUiState.Person(address, name, number, repo.photoUri(address), isContact = repo.contactName(address) != null)
    }

    fun setMuted(value: Boolean, until: Long? = null) = launch { container.conversationStates.setMuted(threadId, value, until) }

    fun setGroupName(value: String) = launch { container.conversationStates.setTitle(threadId, value) }

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

    /** Into Recently deleted, for 30 days, then gone. */
    fun delete(onDone: () -> Unit) = launch {
        val problem = container.trash.delete(setOf(threadId)).problem
        if (problem == null) onDone() else container.toast(problem)
    }

    private fun reload() = launch {
        val address = single ?: return@launch
        rule.value = container.verdictDao.senderRule(normalizeAddress(address))?.let { runCatching { SenderRule.valueOf(it) }.getOrNull() }
        blocked.value = container.blockedNumbers.isBlocked(address)
        reloaded.value = true
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailsScreen(
    viewModel: ConversationDetailsViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    /** A new group with everyone here, plus whoever the user adds. */
    onAddPeople: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val media by viewModel.media.collectAsStateWithLifecycle()
    val links by viewModel.links.collectAsStateWithLifecycle()
    // A link about to be opened: the whole address first, so a look-alike is seen for what it is.
    var opening by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    opening?.let { url ->
        AlertDialog(
            onDismissRequest = { opening = null },
            title = { Text("Open this link?") },
            text = { Text(url) },
            confirmButton = {
                TextButton(onClick = {
                    opening = null
                    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)
                    if (runCatching { context.startActivity(view) }.isFailure) Toast.makeText(context, "No app here can open that link", Toast.LENGTH_SHORT).show()
                }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = {
                    opening = null
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
                }) { Text("Copy") }
            },
        )
    }
    var watching by rememberSaveable { mutableStateOf<String?>(null) }
    val toast = { message: String -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    val share = { attachment: Attachment ->
        scope.launch {
            val intent = viewModel.shareIntent(attachment)
            if (intent == null || runCatching { context.startActivity(intent) }.isFailure) toast("Couldn't share that")
        }
        Unit
    }
    val save = { attachment: Attachment -> scope.launch { toast(viewModel.save(attachment)) }; Unit }
    var confirmDelete by remember { mutableStateOf(false) }
    var choosingMute by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val showContact = { number: String -> showOrCreateContact(context, number) }

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
                        GroupAvatar(groupFaces(state.people.take(GROUP_FACE_CANDIDATES).map { Member(it.address, it.name, it.photoUri) }), 88.dp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(state.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    if (person != null && person.name != person.number) {
                        Text(person.number, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (state.isGroup) {
                item("group-name") {
                    ListItem(
                        headlineContent = { Text("Group name") },
                        supportingContent = { Text(state.groupName ?: "Only you see it. Group texts have no shared name.") },
                        trailingContent = { Icon(Icons.Filled.Edit, contentDescription = "Rename group") },
                        modifier = Modifier.clickable { renaming = true },
                    )
                }
            }

            // The others are listed; the conversation's "3 people" counts the user too.
            section(if (state.isGroup) "You and ${state.people.size} others" else "Contact")
            items(state.people, key = { it.address }) { person ->
                ListItem(
                    leadingContent = { Avatar(person.name, seed = person.address, size = 40.dp, photoUri = person.photoUri) },
                    headlineContent = { Text(person.name) },
                    supportingContent = { Text(if (person.isContact || !state.canReadContacts) person.number else "Not in your contacts · tap to add") },
                    trailingContent = {
                        IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", person.address, null))) }) {
                            Icon(Icons.Filled.Call, contentDescription = "Call ${person.name}")
                        }
                    },
                    modifier = Modifier.clickable { showContact(person.address) },
                )
            }
            item("add-people") {
                ListItem(
                    leadingContent = {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_person_add), contentDescription = null) }
                    },
                    headlineContent = { Text("Add people") },
                    // Group texts can't gain members: the new people make a new conversation.
                    supportingContent = { Text("Starts a new group with ${if (state.isGroup) "everyone here" else state.people.singleOrNull()?.name ?: "them"} and whoever you add") },
                    modifier = Modifier.clickable(onClick = onAddPeople),
                )
            }

            if (links.isNotEmpty()) {
                section("Links")
                items(links, key = { "link-${it.url}" }) { link ->
                    ListItem(
                        leadingContent = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
                        headlineContent = { Text(link.host, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(link.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable(onClickLabel = "Open link") { opening = link.url },
                    )
                }
            }

            if (media.isNotEmpty()) {
                section("Photos & videos")
                item("media") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        itemsIndexed(media, key = { i, a -> "$i:${a.uri}" }) { _, attachment ->
                            AttachmentThumbnail(
                                attachment.uri,
                                attachment.contentType,
                                attachment.name,
                                Modifier
                                    .size(96.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { if (attachment.isVideo) watching = attachment.uri else viewing = attachment.uri },
                            )
                        }
                    }
                }
            }

            // Not before the saved state is in: a switch shown as off and then turned on by it can
            // stay drawn half-way (its thumb doesn't move when it's composed ahead of being shown).
            if (state.loaded) {
            section("Conversation")
            item("muted") {
                Toggle("Notifications", if (state.muted) mutedLabel(state.mutedUntil) else "On", !state.muted) { on ->
                    if (on) viewModel.setMuted(false) else choosingMute = true
                }
            }
            if (!state.muted && viewModel.hasThread) {
                item("sound") {
                    ListItem(
                        headlineContent = { Text("Sound and vibration") },
                        supportingContent = { Text("Just for this conversation, in Android's settings") },
                        modifier = Modifier.clickable {
                            // Off the main thread: a group's shortcut icon is drawn from its people's photos.
                            scope.launch {
                                val intent = withContext(Dispatchers.IO) { viewModel.notificationSettings(state.title) }
                                runCatching { context.startActivity(intent) }
                            }
                        },
                    )
                }
            }
            item("pinned") { Toggle("Pin to top", null, state.pinned, viewModel::setPinned) }
            item("archived") { Toggle("Archived", null, state.archived, viewModel::setArchived) }

            if (!state.isGroup) {
                section("Winnow")
                item("rule-none") { RuleRow("Classify normally", "Winnow decides each message", state.senderRule == null) { viewModel.setRule(null) } }
                item("rule-allow") { RuleRow("Always allow", "Every message reaches your inbox", state.senderRule == SenderRule.ALWAYS_ALLOW) { viewModel.setRule(SenderRule.ALWAYS_ALLOW) } }
                item("rule-filter") { RuleRow("Always filter", "Every message goes to Filtered, silently", state.senderRule == SenderRule.ALWAYS_FILTER) { viewModel.setRule(SenderRule.ALWAYS_FILTER) } }
                if (state.canBlock) {
                    item("blocked") {
                        val email = state.people.singleOrNull()?.address?.let { com.ericflo.winnow.data.isEmailAddress(it) } == true
                        Toggle(if (email) "Block address" else "Block number", "Android drops their texts and calls", state.blocked, viewModel::setBlocked)
                    }
                }
            }
            }

            if (viewModel.hasThread) {
                item("export") {
                    ListItem(
                        headlineContent = { Text("Export conversation") },
                        supportingContent = { Text("Share it as a text file") },
                        modifier = Modifier.clickable {
                            scope.launch {
                                val intent = viewModel.exportIntent(state.title)
                                if (intent == null || runCatching { context.startActivity(intent) }.isFailure) toast("Couldn't export this conversation")
                            }
                        },
                    )
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
    viewing?.let { uri ->
        // Oldest first, like the conversation, so swiping right goes back in time.
        val images = remember(media) { media.filter { it.isImage }.asReversed() }
        ImageViewer(images, images.indexOfFirst { it.uri == uri }, onDismiss = { viewing = null }, onShare = share, onSave = save)
    }
    watching?.let { uri ->
        val video = media.firstOrNull { it.uri == uri }
        VideoViewer(uri, onDismiss = { watching = null }, onShare = video?.let { { share(it) } }, onSave = video?.let { { save(it) } })
    }
    if (choosingMute) {
        MuteDialog(onMute = { until -> viewModel.setMuted(true, until); choosingMute = false }, onDismiss = { choosingMute = false })
    }
    if (renaming) {
        var name by remember { mutableStateOf(state.groupName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name this group") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    placeholder = { Text("e.g. Lake house crew") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { renaming = false; viewModel.setGroupName(name) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            text = { Text("It goes to Recently deleted, where it can be restored for 30 days.") },
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
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun RuleRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
