package com.ericflo.winnow.ui.trash

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.ericflo.winnow.backup.Trash
import com.ericflo.winnow.ui.components.Avatar
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class RecentlyDeletedViewModel(private val container: AppContainer) : ViewModel() {
    val items = container.trash.items

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices

    private val _restoring = MutableStateFlow<Set<String>>(emptySet())
    /** The files being put back right now, by name; their buttons wait. */
    val restoring: StateFlow<Set<String>> = _restoring

    /**
     * Putting messages back needs Winnow to be the SMS app, as writing any message does. It runs
     * in the app scope, so leaving this screen doesn't stop it halfway.
     */
    fun restore(item: Trash.Item) {
        if (item.file.name in _restoring.value) return
        if (!container.isDefaultSmsApp()) {
            _notices.tryEmit("Make Winnow your SMS app to restore conversations")
            return
        }
        _restoring.update { it + item.file.name }
        container.appScope.launch {
            try {
                val restored = container.trash.restore(item) ?: return@launch
                val added = if (restored.added == 1) "Restored 1 message" else "Restored ${restored.added} messages"
                _notices.emit(if (restored.complete) added else "$added. Some couldn't be put back, so it's still here to try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notices.emit("Couldn't restore it: ${e.message ?: e::class.simpleName}")
            } finally {
                _restoring.update { it - item.file.name }
            }
        }
    }

    fun forget(items: Collection<Trash.Item>) = viewModelScope.launch { container.trash.forget(items) }
}

/** Deleted conversations, kept 30 days: restore one, or let it go now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyDeletedScreen(viewModel: RecentlyDeletedViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val restoring by viewModel.restoring.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.notices.collect { snackbar.showSnackbar(it) } }
    // Gone for good: one item, or all of them.
    var forgetting by remember { mutableStateOf<List<Trash.Item>?>(null) }
    forgetting?.let { chosen ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(if (chosen.size == 1) "Delete it for good?" else "Delete all ${chosen.size} for good?") },
            text = { Text("They can't be restored after this.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forget(chosen)
                    forgetting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Recently deleted") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (items.isNotEmpty()) TextButton(onClick = { forgetting = items }) { Text("Empty") }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item("explainer") {
                Text(
                    "Deleted conversations and messages stay here for ${Trash.KEEP_DAYS} days, photos and all, then they're gone for good.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(items, key = { it.file.name }) { item ->
                // Rounded up: deleted a minute ago is "30 days", not 29.
                val day = 24 * 60 * 60_000L
                val daysLeft = ((item.expiresAt - System.currentTimeMillis() + day - 1) / day).coerceAtLeast(0)
                ListItem(
                    leadingContent = { Avatar(item.title, seed = item.recipients.firstOrNull().orEmpty(), size = 44.dp) },
                    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            if (item.snippet.isNotEmpty()) Text(item.snippet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${if (item.messages == 1) "1 message" else "${item.messages} messages"}${if (item.someMessages) " from this conversation" else ""} · " +
                                    if (daysLeft <= 1) "gone tomorrow" else "gone in $daysLeft days",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingContent = {
                        Column {
                            TextButton(onClick = { viewModel.restore(item) }, enabled = item.file.name !in restoring) {
                                Text(if (item.file.name in restoring) "Restoring…" else "Restore")
                            }
                        }
                    },
                )
                // A smaller way to let one go now.
                TextButton(
                    onClick = { forgetting = listOf(item) },
                    enabled = item.file.name !in restoring,
                    modifier = Modifier.padding(start = 72.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text(" Delete now")
                }
            }
            if (items.isEmpty()) {
                item("empty") {
                    Text(
                        "Nothing here. Deleted conversations and messages wait here for ${Trash.KEEP_DAYS} days.",
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
