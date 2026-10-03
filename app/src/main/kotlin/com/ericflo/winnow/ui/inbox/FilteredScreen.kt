package com.ericflo.winnow.ui.inbox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.ui.components.FilteredAvatar

/** Winnow's "Spam & blocked": everything it kept out of the inbox, with why. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilteredScreen(
    viewModel: InboxViewModel,
    onBack: () -> Unit,
    onOpenConversation: (ConversationSummary) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Filtered") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item("explainer") {
                Text(
                    "Kept out of your inbox without a notification. Open one to see why, or to mark it as not spam.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(state.conversations, key = { it.threadId }) { conversation ->
                ConversationRow(
                    conversation,
                    showVerdict = true,
                    onClick = { onOpenConversation(conversation) },
                    leading = { FilteredAvatar(conversation.verdict?.category) },
                )
            }
            if (!state.loading && state.conversations.isEmpty()) {
                item("empty") {
                    Text(
                        "Nothing filtered yet. Scams, phishing, spam and political blasts will land here.",
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
