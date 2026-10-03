package com.ericflo.winnow.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.ui.components.Avatar
import com.ericflo.winnow.ui.components.VerdictBadge
import com.ericflo.winnow.ui.components.shortTimestamp

@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onOpenConversation: (ConversationSummary) -> Unit,
    onStartChat: (address: String) -> Unit,
    onOpenSettings: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val expandedFab by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var showNewChat by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Box(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                SearchPill(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewChat = true },
                expanded = expandedFab,
                icon = { Icon(Icons.Filled.Create, contentDescription = null) },
                text = { Text("Start chat") },
            )
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        LazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            // One stable first item, so the list doesn't scroll when the card appears or goes away.
            item("header") {
                Column {
                    if (!state.live) MakeDefaultCard(onMakeDefault)
                    TabChips(state.tab, state.filteredCount, viewModel::selectTab)
                }
            }
            if (state.tab == InboxTab.FILTERED && state.conversations.isNotEmpty()) {
                item("filtered-explainer") {
                    Text(
                        "Winnow kept these out of your inbox and didn't notify you. Open one to mark it as not spam.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }
            items(state.conversations, key = { it.threadId }) { conversation ->
                ConversationRow(conversation, showVerdict = state.tab == InboxTab.FILTERED, onClick = { onOpenConversation(conversation) })
            }
            if (!state.loading && state.conversations.isEmpty()) {
                item("empty") { EmptyState(state.tab, state.query) }
            }
            item("fab-clearance") { Spacer(Modifier.height(88.dp)) }
        }
    }

    if (showNewChat) {
        NewChatDialog(
            onDismiss = { showNewChat = false },
            onStart = {
                showNewChat = false
                onStartChat(it)
            },
        )
    }
}

@Composable
private fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search conversations", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
    }
}

@Composable
private fun TabChips(tab: InboxTab, filteredCount: Int, onSelect: (InboxTab) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        FilterChip(
            selected = tab == InboxTab.INBOX,
            onClick = { onSelect(InboxTab.INBOX) },
            label = { Text("Inbox") },
        )
        FilterChip(
            selected = tab == InboxTab.FILTERED,
            onClick = { onSelect(InboxTab.FILTERED) },
            label = { Text(if (filteredCount > 0) "Filtered · $filteredCount" else "Filtered") },
        )
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, showVerdict: Boolean, onClick: () -> Unit) {
    val unread = conversation.unread
    val verdict = conversation.verdict
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Avatar(conversation.displayName, seed = conversation.address)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    shortTimestamp(conversation.timestamp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(8.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.primary, modifier = Modifier.size(10.dp))
                }
            }
            if (verdict != null && (showVerdict || verdict.effectiveAction == Action.SILENCE)) {
                VerdictBadge(verdict, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun MakeDefaultCard(onMakeDefault: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Make Winnow your SMS app", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Winnow can only filter messages as your default SMS app. Until then you're looking at sample conversations.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onMakeDefault) { Text("Set as default") }
        }
    }
}

@Composable
private fun EmptyState(tab: InboxTab, query: String) {
    val text = when {
        query.isNotBlank() -> "No conversations match \"$query\""
        tab == InboxTab.FILTERED -> "Nothing filtered. Spam, scams and political texts will land here."
        else -> "No conversations yet"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

@Composable
private fun NewChatDialog(onDismiss: () -> Unit, onStart: (String) -> Unit) {
    var number by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New conversation") },
        text = {
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Phone number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
        },
        confirmButton = {
            TextButton(onClick = { onStart(number.trim()) }, enabled = number.isNotBlank()) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
