package com.ericflo.winnow.ui.scheduled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.data.db.ScheduledMessageEntity
import com.ericflo.winnow.data.displayNameFor
import com.ericflo.winnow.data.splitAddresses
import com.ericflo.winnow.ui.thread.scheduleLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A scheduled text, with who it's going to as the inbox would name them. */
data class ScheduledItem(val message: ScheduledMessageEntity, val to: String, val recipients: List<String>)

class ScheduledViewModel(private val container: AppContainer) : ViewModel() {
    /** Soonest first; null while loading. */
    val items: StateFlow<List<ScheduledItem>?> = combine(container.scheduler.observeAll(), container.conversationStates.observe()) { rows, states ->
        rows.map { m ->
            val recipients = splitAddresses(m.recipients)
            ScheduledItem(m, states[m.threadId]?.title ?: displayNameFor(recipients, container.messages::displayName), recipients)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun sendNow(id: Long) = viewModelScope.launch { container.scheduler.sendNow(id) }

    fun cancel(id: Long) = viewModelScope.launch { container.scheduler.cancel(id) }
}

/** Every scheduled text in one place, from the menu; each conversation shows its own too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(viewModel: ScheduledViewModel, onBack: () -> Unit, onOpenThread: (Long, List<String>) -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scheduled") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val list = items ?: return@Scaffold
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            items(list, key = { it.message.id }) { item ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    overlineContent = { Text(scheduleLabel(item.message.sendAt)) },
                    headlineContent = { Text(item.to, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(item.message.body, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Send now") }, onClick = { menu = false; viewModel.sendNow(item.message.id) })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; viewModel.cancel(item.message.id) })
                            }
                        }
                    },
                    modifier = Modifier.clickable(onClickLabel = "Open conversation") { onOpenThread(item.message.threadId, item.recipients) },
                )
            }
            if (list.isEmpty()) {
                item("empty") {
                    Text(
                        "Nothing scheduled. In a conversation, long-press Send to pick a time.",
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
