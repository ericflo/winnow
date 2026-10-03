package com.ericflo.winnow.ui.inbox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filtered = mode == ListMode.FILTERED
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (filtered) "Filtered" else "Archived") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item("explainer") {
                Text(
                    if (filtered) "Kept out of your inbox without a notification. Open one to see why, or swipe it to mark it as not spam."
                    else "Archived conversations come back to your inbox when a new message arrives.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(state.conversations, key = { it.threadId }) { conversation ->
                val swipe = when {
                    filtered && !conversation.isGroup -> Triple(rememberVectorPainter(Icons.Filled.CheckCircle), "Not spam") {
                        viewModel.allow(conversation)
                        scope.launch {
                            val result = snackbar.showSnackbar("${conversation.displayName} will always reach your inbox", actionLabel = "Undo")
                            if (result == SnackbarResult.ActionPerformed) viewModel.block(conversation)
                        }
                    }
                    !filtered -> Triple(painterResource(R.drawable.ic_unarchive), "Unarchive") {
                        viewModel.setArchived(setOf(conversation.threadId), false)
                    }
                    else -> null
                }
                SwipeAction(enabled = swipe != null, icon = swipe?.first ?: painterResource(R.drawable.ic_archive), label = swipe?.second.orEmpty(), onSwipe = { swipe?.third?.invoke() }) {
                    ConversationRow(
                        conversation,
                        showVerdict = filtered,
                        onClick = { onOpenThread(conversation.threadId, conversation.recipients) },
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
