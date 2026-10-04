package com.ericflo.winnow.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.R
import com.ericflo.winnow.ui.components.FilteredAvatar

/**
 * A secondary conversation list: Filtered (Winnow's "Spam & blocked", with why each was
 * filtered) or Archived (with a one-tap unarchive).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    viewModel: InboxViewModel,
    mode: ListMode,
    onBack: () -> Unit,
    onOpenThread: (threadId: Long, recipients: List<String>) -> Unit,
    /** Filtered only: the classifier accuracy screen. */
    onOpenMetrics: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filtered = mode == ListMode.FILTERED
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Several at once: long-press starts it, taps add and remove. A spam pile can go in one go.
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(state.conversations) { selected = selected.filterTo(HashSet()) { id -> state.conversations.any { it.threadId == id } } }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    var confirmDelete by remember { mutableStateOf(false) }
    val picked = state.conversations.filter { it.threadId in selected }
    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (picked.size == 1) "Delete this conversation?" else "Delete ${picked.size} conversations?") },
            text = { Text("Their messages are removed from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(selected)
                    selected = emptySet()
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
                    },
                    actions = {
                        // Not spam: only for one-to-one conversations, whose sender can be allowed.
                        if (filtered && picked.any { !it.isGroup }) {
                            IconButton(onClick = {
                                val rescued = picked.filterNot { it.isGroup }
                                selected = emptySet()
                                viewModel.allowAll(rescued) { previous ->
                                    scope.launch {
                                        val message = if (rescued.size == 1) "${rescued.single().displayName} will always reach your inbox" else "${rescued.size} senders will always reach your inbox"
                                        if (snackbar.showSnackbar(message, actionLabel = "Undo") == SnackbarResult.ActionPerformed) viewModel.undoAll(previous)
                                    }
                                }
                            }) { Icon(Icons.Filled.CheckCircle, contentDescription = "Not spam") }
                        }
                        if (!filtered) {
                            IconButton(onClick = {
                                viewModel.setArchived(selected, false)
                                selected = emptySet()
                            }) { Icon(painterResource(R.drawable.ic_unarchive), contentDescription = "Unarchive") }
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    },
                )
                return@Scaffold
            }
            TopAppBar(
                title = { Text(if (filtered) "Filtered" else "Archived") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (filtered) {
                        IconButton(onClick = onOpenMetrics) { Icon(painterResource(R.drawable.ic_insights), contentDescription = "Classifier accuracy") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item("explainer") {
                Text(
                    if (filtered) "Kept out of your inbox without a notification. Open one to see why, or swipe it to mark it as not spam. Long-press to pick several."
                    else "Archived conversations come back to your inbox when a new message arrives.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (filtered) {
                item("accuracy") {
                    ListItem(
                        leadingContent = { Icon(painterResource(R.drawable.ic_insights), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text("How accurate is this?") },
                        supportingContent = { Text("ROC curve, precision and recall, calibration and more") },
                        modifier = Modifier.clickable(onClick = onOpenMetrics),
                    )
                }
            }
            items(state.conversations, key = { it.threadId }) { conversation ->
                val swipe = when {
                    filtered && !conversation.isGroup -> Triple(rememberVectorPainter(Icons.Filled.CheckCircle), "Not spam") {
                        viewModel.allow(conversation) { previous ->
                            scope.launch {
                                val result = snackbar.showSnackbar("${conversation.displayName} will always reach your inbox", actionLabel = "Undo")
                                if (result == SnackbarResult.ActionPerformed) viewModel.undo(previous)
                            }
                        }
                    }
                    !filtered -> Triple(painterResource(R.drawable.ic_unarchive), "Unarchive") {
                        viewModel.setArchived(setOf(conversation.threadId), false)
                    }
                    else -> null
                }
                // Either way does the same thing here; both remove the row from this list.
                val action = swipe?.let { (icon, label, run) -> Swipe(icon, label, removes = true) { run() } }
                SwipeAction(start = action.takeIf { selected.isEmpty() }, end = action.takeIf { selected.isEmpty() }) {
                    ConversationRow(
                        conversation,
                        showVerdict = filtered,
                        selected = conversation.threadId in selected,
                        onClick = { if (selected.isEmpty()) onOpenThread(conversation.threadId, conversation.recipients) else toggle(conversation.threadId) },
                        onLongClick = { toggle(conversation.threadId) },
                        leading = {
                            if (filtered) FilteredAvatar(conversation.verdict?.category) else ConversationAvatar(conversation)
                        },
                        trailing = if (filtered) null else {
                            {
                                IconButton(onClick = { viewModel.setArchived(setOf(conversation.threadId), false) }) {
                                    Icon(painterResource(R.drawable.ic_unarchive), contentDescription = "Unarchive")
                                }
                            }
                        },
                    )
                }
            }
            if (!state.loading && state.conversations.isEmpty()) {
                item("empty") {
                    Text(
                        if (filtered) "Nothing filtered yet. Scams, phishing, spam and political blasts will land here."
                        else "No archived conversations. Swipe a conversation in your inbox to archive it.",
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
