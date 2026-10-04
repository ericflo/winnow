package com.ericflo.winnow.ui.thread

import com.ericflo.winnow.data.SimCard
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Check
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.telephony.SmsMessage
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ericflo.winnow.R
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.VerificationCodes
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.OutgoingAttachment
import com.ericflo.winnow.data.StoredVerdict
import com.ericflo.winnow.data.Tapback
import com.ericflo.winnow.data.db.ScheduledMessageEntity
import com.ericflo.winnow.ui.components.Avatar
import com.ericflo.winnow.ui.components.ImageViewer
import com.ericflo.winnow.ui.components.headerLabel
import com.ericflo.winnow.ui.components.isEmojiOnly
import com.ericflo.winnow.ui.components.linkify
import com.ericflo.winnow.ui.components.timeOfDay
import com.ericflo.winnow.ui.theme.avatarColors
import com.ericflo.winnow.ui.theme.categoryColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    viewModel: ThreadViewModel,
    onBack: () -> Unit,
    onForward: (String) -> Unit,
    /** Opens a conversation with the carrier's spam-reporting short code, pre-filled. */
    onReportSpam: (String) -> Unit,
    onOpenDetails: (threadId: Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sims by viewModel.sims.collectAsStateWithLifecycle()
    val selectedSim by viewModel.selectedSim.collectAsStateWithLifecycle()
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    val scheduled by viewModel.scheduled.collectAsStateWithLifecycle()
    var confirmBlock by remember { mutableStateOf(false) }
    var confirmReport by remember { mutableStateOf(false) }
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.notices.collect { snackbar.showSnackbar(it) } }
    LifecycleResumeEffect(viewModel) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var detailsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copy(text: String, notice: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text)))
            snackbar.showSnackbar(notice)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addAttachment(OutgoingAttachment(uri.toString(), context.contentResolver.getType(uri) ?: "image/jpeg", null))
    }
    val single = state.recipients.singleOrNull()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    // Tapping the title opens the conversation's details, as in Messages.
                    val openDetails = Modifier.clickable {
                        viewModel.currentThreadId().takeIf { it >= 0 }?.let(onOpenDetails)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = openDetails) {
                        if (state.isGroup) GroupAvatar(36.dp) else Avatar(state.title, seed = single.orEmpty(), size = 36.dp, photoUri = state.photos[single])
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            state.subtitle?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                actions = {
                    if (single != null) {
                        IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", single, null))) }) {
                            Icon(Icons.Filled.Call, contentDescription = "Call")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Details") },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.currentThreadId().takeIf { it >= 0 }?.let(onOpenDetails)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (state.muted) "Unmute notifications" else "Mute notifications") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_muted), contentDescription = null) },
                                onClick = { menuOpen = false; viewModel.setMuted(!state.muted) },
                            )
                            DropdownMenuItem(
                                text = { Text(if (state.archived) "Unarchive" else "Archive") },
                                leadingIcon = { Icon(painterResource(if (state.archived) R.drawable.ic_unarchive else R.drawable.ic_archive), contentDescription = null) },
                                onClick = { menuOpen = false; viewModel.setArchived(!state.archived) },
                            )
                            if (single != null) {
                                DropdownMenuItem(
                                    text = { Text("Always allow this sender") },
                                    leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                                    onClick = { menuOpen = false; viewModel.allow() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Always filter this sender") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_block), contentDescription = null) },
                                    onClick = { menuOpen = false; viewModel.filter() },
                                )
                            }
                            if (viewModel.canBlock) {
                                DropdownMenuItem(
                                    text = { Text(if (blocked) "Unblock number" else "Block number") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_block), contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        if (blocked) viewModel.setBlocked(false) else confirmBlock = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete conversation") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                sims = sims,
                selectedSim = sims.firstOrNull { it.subscriptionId == selectedSim },
                onSelectSim = viewModel::selectSim,
                draft = draft,
                onDraftChange = viewModel::setDraft,
                attachments = attachments,
                onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveAttachment = viewModel::removeAttachment,
                isSms = single != null && attachments.isEmpty(),
                onSend = viewModel::send,
                onSchedule = viewModel::schedule,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.verdict?.let { verdict ->
                if (verdict.effectiveAction != Action.ALLOW || verdict.userAction != null) {
                    VerdictBanner(verdict, onAllow = viewModel::allow, onFilter = viewModel::filter, onReport = { confirmReport = true })
                }
            }
            MessageList(
                state = state,
                scheduled = scheduled,
                onScheduledSendNow = viewModel::sendScheduledNow,
                onScheduledEdit = viewModel::editScheduled,
                onScheduledDelete = viewModel::cancelScheduled,
                onViewImage = { viewing = it },
                onActions = { actionsFor = it },
                onRetry = viewModel::retry,
                onCopyCode = { copy(it, "Code copied") },
                modifier = Modifier.weight(1f),
            )
        }
    }

    actionsFor?.let { message ->
        MessageActionsSheet(
            message = message,
            onDismiss = { actionsFor = null },
            onCopy = { copy(message.body, "Message copied") },
            onForward = { onForward(message.body) },
            onDelete = { viewModel.delete(message) },
            onDetails = { detailsFor = message },
        )
    }
    detailsFor?.let { message -> MessageDetailsDialog(message, state, sims, onDismiss = { detailsFor = null }) }
    viewing?.let { ImageViewer(it, onDismiss = { viewing = null }) }
    if (confirmReport) {
        val spam = state.messages.lastOrNull { !it.outgoing }?.body.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmReport = false },
            title = { Text("Report to your carrier?") },
            text = {
                Text(
                    "US carriers collect spam at 7726 (\"SPAM\"). Winnow will open a message to 7726 with this text " +
                        "filled in for you to send. Your carrier usually replies asking for the sender's number.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReport = false
                    onReportSpam(spam)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirmReport = false }) { Text("Cancel") } },
        )
    }
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("Block ${state.title}?") },
            text = {
                Text(
                    "Android will drop texts and calls from this number before any app sees them. " +
                        "To only keep them out of your inbox, use \"Always filter this sender\" instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    viewModel.setBlocked(true)
                }) { Text("Block") }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            text = { Text("Messages are removed from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteConversation(onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GroupAvatar(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_group),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** Why Winnow handled this conversation the way it did, and the one-tap correction. */
@Composable
private fun VerdictBanner(verdict: StoredVerdict, onAllow: () -> Unit, onFilter: () -> Unit, onReport: () -> Unit) {
    val (container, content) = categoryColors(if (verdict.userAction == Action.ALLOW) null else verdict.category)
    val label = verdict.category?.label ?: "This sender"
    val percent = if (verdict.confidence < 1.0) " · ${(verdict.confidence * 100).toInt()}%" else ""
    val (title, detail) = when {
        verdict.userAction == Action.ALLOW -> "You allowed this sender" to "Their messages will always reach your inbox."
        verdict.userAction != null -> "You filtered this sender" to "Their messages will skip your inbox without a notification."
        verdict.action == Action.FILTER -> "Filtered as $label$percent" to "${verdict.source}. Kept out of your inbox, no notification."
        else -> "Silenced: $label$percent" to "${verdict.source}. Delivered without a notification."
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (verdict.effectiveAction) {
                        Action.FILTER -> Icons.Filled.Warning
                        Action.SILENCE -> Icons.Filled.Info
                        Action.ALLOW -> Icons.Filled.CheckCircle
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (verdict.userAction == null && verdict.category in REPORTABLE) TextButton(onClick = onReport) { Text("Report") }
                if (verdict.effectiveAction != Action.ALLOW) TextButton(onClick = onAllow) { Text("Not spam") }
                if (verdict.effectiveAction != Action.FILTER) TextButton(onClick = onFilter) { Text("Filter sender") }
            }
        }
    }
}

private val REPORTABLE = setOf(Category.SPAM, Category.SCAM, Category.PHISHING)

private sealed interface ListItem {
    val key: String

    /** "Texting with … (SMS/MMS)": which transport this conversation uses. */
    data class Transport(val text: String) : ListItem {
        override val key get() = "transport"
    }

    data class Header(val label: String, override val key: String) : ListItem
    data class Bubble(
        val message: ChatMessage,
        val firstInGroup: Boolean,
        val lastInGroup: Boolean,
        /** Tapback emojis drawn on this bubble, e.g. "❤️" or "😂 2". */
        val reactions: List<String> = emptyList(),
    ) : ListItem {
        override val key get() = message.key
    }
}

/**
 * Folds tapback texts into the messages they quote. Returns the messages still worth showing
 * and the reactions per message key. A tapback whose original isn't found stays a message.
 */
private fun foldTapbacks(messages: List<ChatMessage>): Pair<List<ChatMessage>, Map<String, List<String>>> {
    val reactions = HashMap<String, MutableList<String>>()
    val shown = mutableListOf<ChatMessage>()
    for (m in messages) {
        val tapback = Tapback.parse(m.body)
        val target = tapback?.let { t ->
            shown.lastOrNull { prior ->
                if (t.quoted == "an image") prior.attachments.any { it.isImage } else t.matches(prior.body)
            }
        }
        if (tapback == null || target == null) {
            shown += m
            continue
        }
        val list = reactions.getOrPut(target.key) { mutableListOf() }
        if (tapback.removal) list.remove(tapback.emoji) else list += tapback.emoji
    }
    val labels = reactions.mapValues { (_, emojis) ->
        emojis.groupingBy { it }.eachCount().map { (emoji, n) -> if (n > 1) "$emoji $n" else emoji }
    }.filterValues { it.isNotEmpty() }
    return shown to labels
}

/** A header opens every block of messages more than an hour after the previous one, like Messages. */
private const val BLOCK_GAP_MILLIS = 60 * 60_000L

/** Same-sender messages within a block group into one visual stack. */
private const val GROUP_GAP_MILLIS = 5 * 60_000L

/** Transport line, time headers and grouped bubbles. Newest first, for a reversed list. */
private fun buildItems(transport: String, messages: List<ChatMessage>): List<ListItem> {
    fun day(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
    fun newBlock(prev: ChatMessage?, m: ChatMessage) =
        prev == null || m.timestamp - prev.timestamp > BLOCK_GAP_MILLIS || day(prev.timestamp) != day(m.timestamp)
    fun grouped(a: ChatMessage?, b: ChatMessage?) =
        a != null && b != null && a.outgoing == b.outgoing && a.sender == b.sender &&
            !newBlock(a, b) && b.timestamp - a.timestamp < GROUP_GAP_MILLIS

    val (shown, reactions) = foldTapbacks(messages)
    val items = mutableListOf<ListItem>(ListItem.Transport(transport))
    shown.forEachIndexed { i, m ->
        val prev = shown.getOrNull(i - 1)
        val next = shown.getOrNull(i + 1)
        if (newBlock(prev, m)) items += ListItem.Header(headerLabel(m.timestamp), "h-${m.key}")
        items += ListItem.Bubble(m, firstInGroup = !grouped(prev, m), lastInGroup = !grouped(m, next), reactions = reactions[m.key].orEmpty())
    }
    return items.asReversed()
}

@Composable
private fun MessageList(
    state: ThreadUiState,
    scheduled: List<ScheduledMessageEntity>,
    onScheduledSendNow: (Long) -> Unit,
    onScheduledEdit: (ScheduledMessageEntity) -> Unit,
    onScheduledDelete: (Long) -> Unit,
    onViewImage: (String) -> Unit,
    onActions: (ChatMessage) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val transport = if (state.isGroup) "Group texting with ${state.recipients.size} people (MMS)"
    else "Texting with ${ContactLookup.formatAddress(state.recipients.firstOrNull().orEmpty())} (SMS/MMS)"
    val items = remember(transport, state.messages) { buildItems(transport, state.messages) }
    val latestOutgoing = state.messages.lastOrNull { it.outgoing }?.key
    var revealed by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        reverseLayout = true,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Reversed list: scheduled messages sit below everything already sent, latest last.
        items(scheduled.asReversed(), key = { "scheduled-${it.id}" }) { message ->
            ScheduledBubble(
                message,
                onSendNow = { onScheduledSendNow(message.id) },
                onEdit = { onScheduledEdit(message) },
                onDelete = { onScheduledDelete(message.id) },
            )
        }
        items(items, key = { it.key }) { item ->
            when (item) {
                is ListItem.Transport -> CenteredNote(item.text, Modifier.padding(vertical = 4.dp))
                is ListItem.Header -> CenteredNote(item.label, Modifier.padding(top = 20.dp, bottom = 8.dp))
                is ListItem.Bubble -> MessageBubble(
                    item = item,
                    senderName = item.message.sender?.let { state.senderNames[it] }?.takeIf { state.isGroup },
                    senderPhoto = item.message.sender?.let { state.photos[it] },
                    showTime = revealed == item.key,
                    isLatestOutgoing = item.key == latestOutgoing,
                    onClick = { revealed = if (revealed == item.key) null else item.key },
                    onLongClick = { onActions(item.message) },
                    onViewImage = onViewImage,
                    onRetry = { onRetry(item.message) },
                    onCopyCode = onCopyCode,
                )
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    item: ListItem.Bubble,
    senderName: String?,
    senderPhoto: String?,
    showTime: Boolean,
    isLatestOutgoing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onViewImage: (String) -> Unit,
    onRetry: () -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val m = item.message
    val colors = MaterialTheme.colorScheme
    val big = 22.dp
    val small = 6.dp
    val shape = if (m.outgoing) {
        RoundedCornerShape(big, if (item.firstInGroup) big else small, if (item.lastInGroup) big else small, big)
    } else {
        RoundedCornerShape(if (item.firstInGroup) big else small, big, big, if (item.lastInGroup) big else small)
    }
    val fraud = m.verdict?.isFraud == true
    val showAvatarColumn = senderName != null

    Column(
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth().padding(top = if (item.firstInGroup) 8.dp else 2.dp),
    ) {
        if (senderName != null && item.firstInGroup) {
            Text(
                senderName,
                style = MaterialTheme.typography.labelMedium,
                color = avatarColors(m.sender.orEmpty()).second,
                modifier = Modifier.padding(start = 48.dp, bottom = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (showAvatarColumn) {
                if (item.lastInGroup) Avatar(senderName.orEmpty(), seed = m.sender.orEmpty(), size = 36.dp, photoUri = senderPhoto) else Spacer(Modifier.width(36.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(
                horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(if (showAvatarColumn) 0.85f else 0.8f),
            ) {
                m.attachments.forEach { attachment ->
                    if (attachment.isImage) {
                        AsyncImage(
                            model = attachment.uri,
                            contentDescription = attachment.name ?: "Image",
                            contentScale = ContentScale.Crop,
                            onError = { Log.w("WinnowImage", "Couldn't load ${attachment.uri}", it.result.throwable) },
                            modifier = Modifier
                                .widthIn(max = 260.dp)
                                .heightIn(max = 320.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .combinedClickable(onClick = { onViewImage(attachment.uri) }, onLongClick = onLongClick),
                        )
                    } else {
                        Surface(color = colors.surfaceContainerHighest, shape = RoundedCornerShape(14.dp)) {
                            Text(
                                attachment.name ?: attachment.contentType,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                when {
                    m.status == ChatMessage.Status.DOWNLOAD_FAILED -> Text(
                        "Couldn't download this MMS · Tap to retry",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.error,
                        modifier = Modifier
                            .clip(shape)
                            .background(colors.surfaceContainerHigh)
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    m.status == ChatMessage.Status.DOWNLOADING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(shape).background(colors.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Downloading MMS…", style = MaterialTheme.typography.bodyLarge)
                    }
                    m.body.isBlank() -> Unit
                    isEmojiOnly(m.body) -> Text(
                        m.body,
                        fontSize = 44.sp,
                        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
                    )
                    else -> Text(
                        linkify(m.body, links = !fraud, linkColor = if (m.outgoing) colors.onPrimaryContainer else colors.primary),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (m.outgoing) colors.onPrimaryContainer else colors.onSurface,
                        modifier = Modifier
                            .clip(shape)
                            .background(if (m.outgoing) colors.primaryContainer else colors.surfaceContainerHigh)
                            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                if (item.reactions.isNotEmpty()) {
                    // Tucked under the bubble's corner, as Messages draws reactions.
                    Surface(
                        color = colors.surfaceContainerHighest,
                        shape = CircleShape,
                        border = BorderStroke(2.dp, colors.surface),
                        modifier = Modifier
                            .offset(x = if (m.outgoing) (-8).dp else 8.dp, y = (-10).dp)
                            .clearAndSetSemantics { contentDescription = "Reactions: ${item.reactions.joinToString(", ")}" },
                    ) {
                        Text(
                            item.reactions.joinToString(" "),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                if (fraud && m.body.contains('.')) {
                    Text("Links turned off: this looks like ${m.verdict?.category?.label?.lowercase()}", style = MaterialTheme.typography.labelSmall, color = colors.error)
                }
                if (!m.outgoing) {
                    VerificationCodes.find(m.body)?.let { code ->
                        AssistChip(
                            onClick = { onCopyCode(code) },
                            label = { Text("Copy $code") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }
        val status = when (m.status) {
            ChatMessage.Status.SENDING -> "Sending…"
            ChatMessage.Status.FAILED -> "Not sent · Tap to retry"
            ChatMessage.Status.DELIVERED -> when {
                showTime -> "Delivered · ${timeOfDay(m.timestamp)}"
                isLatestOutgoing -> "Delivered"
                else -> null
            }
            else -> if (showTime) timeOfDay(m.timestamp) else null
        }
        if (status != null) {
            val failed = m.status == ChatMessage.Status.FAILED
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) colors.error else colors.onSurfaceVariant,
                modifier = Modifier
                    .then(if (failed) Modifier.clickable(onClick = onRetry) else Modifier)
                    .padding(start = if (showAvatarColumn) 56.dp else 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        fun act(action: () -> Unit) = { onDismiss(); action() }
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            if (message.body.isNotBlank()) {
                ListItem(
                    headlineContent = { Text("Copy text") },
                    leadingContent = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null) },
                    colors = colors,
                    modifier = Modifier.clickable(onClick = act(onCopy)),
                )
                ListItem(
                    headlineContent = { Text("Forward") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                    colors = colors,
                    modifier = Modifier.clickable(onClick = act(onForward)),
                )
            }
            ListItem(
                headlineContent = { Text("View details") },
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                colors = colors,
                modifier = Modifier.clickable(onClick = act(onDetails)),
            )
            ListItem(
                headlineContent = { Text("Delete") },
                leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
                colors = colors,
                modifier = Modifier.clickable(onClick = act(onDelete)),
            )
        }
    }
}

@Composable
private fun MessageDetailsDialog(message: ChatMessage, state: ThreadUiState, sims: List<SimCard>, onDismiss: () -> Unit) {
    val at = Instant.ofEpochMilli(message.timestamp).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
    val rows = buildList {
        add("Type" to if (message.kind == ChatMessage.Kind.MMS) "Multimedia message (MMS)" else "Text message (SMS)")
        if (message.outgoing) {
            add("To" to state.recipients.joinToString(", ") { ContactLookup.formatAddress(it) })
            add("Sent" to at)
        } else {
            val from = message.sender ?: state.recipients.firstOrNull().orEmpty()
            val number = ContactLookup.formatAddress(from)
            val name = state.senderNames[from] ?: state.title.takeIf { !state.isGroup }
            add("From" to if (name != null && name != number) "$name · $number" else number)
            add("Received" to at)
        }
        // Which SIM only matters on a phone with more than one.
        sims.firstOrNull { it.subscriptionId == message.subscriptionId }?.let { add("SIM" to "${it.slotName} · ${it.label}") }
        message.subject?.let { add("Subject" to it) }
        if (message.attachments.isNotEmpty()) add("Attachments" to message.attachments.joinToString { it.contentType })
        message.verdict?.let { v ->
            val percent = if (v.confidence < 1.0) " (${(v.confidence * 100).toInt()}%)" else ""
            add("Winnow" to "${v.category?.label ?: "Sender rule"}$percent → ${v.effectiveAction.name.lowercase()}")
            add("Decided by" to v.source)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Composer(
    sims: List<SimCard>,
    selectedSim: SimCard?,
    onSelectSim: (Int) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<OutgoingAttachment>,
    onAttach: () -> Unit,
    onRemoveAttachment: (OutgoingAttachment) -> Unit,
    isSms: Boolean,
    onSend: () -> Unit,
    onSchedule: (at: Long, label: String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding().imePadding()) {
        if (attachments.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(attachments, key = { it.uri }) { attachment ->
                    Box {
                        AsyncImage(
                            model = attachment.uri,
                            contentDescription = "Attachment",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(16.dp)),
                        )
                        IconButton(
                            onClick = { onRemoveAttachment(attachment) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).background(colors.surface.copy(alpha = 0.8f), CircleShape),
                        ) { Icon(Icons.Filled.Close, contentDescription = "Remove attachment", modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp)) {
                    IconButton(onClick = onAttach) { Icon(Icons.Outlined.AddCircle, contentDescription = "Attach a photo") }
                    Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
                        if (draft.isEmpty()) {
                            val kind = if (isSms) "Text message" else "MMS message"
                            Text(
                                selectedSim?.let { "$kind · ${it.label}" } ?: kind,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (isSms && draft.length >= 100) SegmentCounter(draft)
                    if (sims.size >= 2 && selectedSim != null) SimPicker(sims, selectedSim, onSelectSim)
                }
            }
            Spacer(Modifier.width(8.dp))
            SendButton(enabled = draft.isNotBlank() || attachments.isNotEmpty(), onSend = onSend, onSchedule = onSchedule)
        }
    }
}

/** A small badge with the SIM's number in its color; tapping it lists the SIMs to choose from. */
@Composable
private fun SimPicker(sims: List<SimCard>, selected: SimCard, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(selected.color))
                .clickable { open = true }
                .semantics { contentDescription = "Sending from ${selected.slotName}, ${selected.label}. Change SIM" },
            contentAlignment = Alignment.Center,
        ) {
            Text("${selected.slot + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sims.forEach { sim ->
                DropdownMenuItem(
                    leadingIcon = {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(Color(sim.color)), contentAlignment = Alignment.Center) {
                            Text("${sim.slot + 1}", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column {
                            Text(sim.label, style = MaterialTheme.typography.bodyLarge)
                            Text(sim.slotName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingIcon = if (sim.subscriptionId == selected.subscriptionId) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                    } else {
                        null
                    },
                    onClick = { open = false; onSelect(sim.subscriptionId) },
                )
            }
        }
    }
}

/** "37 / 2": characters left in the current SMS segment, and how many segments this will take. */
@Composable
private fun SegmentCounter(text: String) {
    val (segments, _, remaining) = SmsMessage.calculateLength(text, false).let { Triple(it[0], it[1], it[2]) }
    Text(
        "$remaining / $segments",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
}
