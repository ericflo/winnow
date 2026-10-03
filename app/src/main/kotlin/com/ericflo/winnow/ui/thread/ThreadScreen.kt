package com.ericflo.winnow.ui.thread

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.StoredVerdict
import com.ericflo.winnow.ui.components.Avatar
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.ui.components.headerLabel
import com.ericflo.winnow.ui.components.timeOfDay
import com.ericflo.winnow.ui.theme.categoryColors
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    viewModel: ThreadViewModel,
    address: String,
    initialDraft: String,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf(initialDraft) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(state.title, seed = address, size = 36.dp)
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
                    IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", address, null))) }) {
                        Icon(Icons.Filled.Call, contentDescription = "Call")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Always allow this sender") }, onClick = { menuOpen = false; viewModel.allow() })
                            DropdownMenuItem(text = { Text("Always filter this sender") }, onClick = { menuOpen = false; viewModel.filter() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.verdict?.let { verdict ->
                if (verdict.effectiveAction != Action.ALLOW || verdict.userAction != null) {
                    VerdictBanner(verdict, onAllow = viewModel::allow, onFilter = viewModel::filter)
                }
            }
            MessageList(address, state.messages, Modifier.weight(1f))
        }
    }
}

/** Why Winnow handled this conversation the way it did, and the one-tap correction. */
@Composable
private fun VerdictBanner(verdict: StoredVerdict, onAllow: () -> Unit, onFilter: () -> Unit) {
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
                if (verdict.effectiveAction != Action.ALLOW) TextButton(onClick = onAllow) { Text("Not spam") }
                if (verdict.effectiveAction != Action.FILTER) TextButton(onClick = onFilter) { Text("Filter sender") }
            }
        }
    }
}

private sealed interface ListItem {
    val key: String

    /** "Texting with … (SMS/MMS)": which transport this conversation uses. */
    data class Transport(val address: String) : ListItem {
        override val key get() = "transport"
    }

    data class Header(val label: String, override val key: String) : ListItem
    data class Bubble(val message: ChatMessage, val firstInGroup: Boolean, val lastInGroup: Boolean) : ListItem {
        override val key get() = "m${message.id}"
    }
}

/** A header opens every block of messages more than an hour after the previous one, like Messages. */
private const val BLOCK_GAP_MILLIS = 60 * 60_000L

/** Same-side messages within a block group into one visual stack. */
private const val GROUP_GAP_MILLIS = 5 * 60_000L

/** Transport line, time headers and grouped bubbles. Newest first, for a reversed list. */
private fun buildItems(address: String, messages: List<ChatMessage>): List<ListItem> {
    fun day(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
    fun newBlock(prev: ChatMessage?, m: ChatMessage) =
        prev == null || m.timestamp - prev.timestamp > BLOCK_GAP_MILLIS || day(prev.timestamp) != day(m.timestamp)

    fun grouped(a: ChatMessage?, b: ChatMessage?) =
        a != null && b != null && a.outgoing == b.outgoing && !newBlock(a, b) && b.timestamp - a.timestamp < GROUP_GAP_MILLIS

    val items = mutableListOf<ListItem>(ListItem.Transport(address))
    messages.forEachIndexed { i, m ->
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        if (newBlock(prev, m)) items += ListItem.Header(headerLabel(m.timestamp), "h${m.id}")
        items += ListItem.Bubble(m, firstInGroup = !grouped(prev, m), lastInGroup = !grouped(m, next))
    }
    return items.asReversed()
}

@Composable
private fun MessageList(address: String, messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val items = remember(address, messages) { buildItems(address, messages) }
    var revealed by rememberSaveable { mutableStateOf<Long?>(null) }
    LazyColumn(
        reverseLayout = true,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is ListItem.Transport -> CenteredNote(
                    "Texting with ${ContactLookup.formatAddress(item.address)} (SMS/MMS)",
                    Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                is ListItem.Header -> CenteredNote(item.label, Modifier.padding(top = 20.dp, bottom = 8.dp))
                is ListItem.Bubble -> MessageBubble(
                    item,
                    showTime = revealed == item.message.id,
                    onClick = { revealed = if (revealed == item.message.id) null else item.message.id },
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

@Composable
private fun MessageBubble(item: ListItem.Bubble, showTime: Boolean, onClick: () -> Unit) {
    val m = item.message
    val big = 22.dp
    val small = 6.dp
    val shape = if (m.outgoing) {
        RoundedCornerShape(big, if (item.firstInGroup) big else small, if (item.lastInGroup) big else small, big)
    } else {
        RoundedCornerShape(if (item.firstInGroup) big else small, big, big, if (item.lastInGroup) big else small)
    }
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth().padding(top = if (item.firstInGroup) 8.dp else 2.dp),
    ) {
        Box(Modifier.fillMaxWidth(0.8f), contentAlignment = if (m.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
            Text(
                m.body,
                style = MaterialTheme.typography.bodyLarge,
                color = if (m.outgoing) colors.onPrimaryContainer else colors.onSurface,
                modifier = Modifier
                    .clip(shape)
                    .background(if (m.outgoing) colors.primaryContainer else colors.surfaceContainerHigh)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        val status = when (m.status) {
            ChatMessage.Status.SENDING -> "Sending…"
            ChatMessage.Status.FAILED -> "Not sent"
            else -> if (showTime) timeOfDay(m.timestamp) else null
        }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (m.status == ChatMessage.Status.FAILED) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Composer(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainerHigh,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 20.dp)) {
                IconButton(onClick = {}, enabled = false) {
                    Icon(Icons.Outlined.AddCircle, contentDescription = "Attach (needs MMS support)")
                }
                Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
                    if (draft.isEmpty()) {
                        Text("Text message", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
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
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onSend,
            enabled = draft.isNotBlank(),
            modifier = Modifier.size(56.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}
