package com.ericflo.winnow.ui.starred

import androidx.compose.foundation.clickable
import com.ericflo.winnow.R
import coil3.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.data.StarredMessage
import com.ericflo.winnow.ui.components.Avatar
import com.ericflo.winnow.ui.components.shortTimestamp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StarredViewModel(private val container: AppContainer) : ViewModel() {
    /** Newest star first; null while loading. */
    val starred: StateFlow<List<StarredMessage>?> = container.starredDao.observeAll().map { rows ->
        val byKey = container.messages.messagesByKey(rows.map { it.messageKey }).associateBy { it.message.key }
        rows.mapNotNull { byKey[it.messageKey] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun unstar(message: StarredMessage) {
        viewModelScope.launch { container.starredDao.unstar(message.message.key) }
    }

    fun displayName(address: String): String = container.messages.displayName(address)

    fun photoUri(address: String): String? = container.messages.photoUri(address)
}

/** Every starred message, across conversations. Tapping one opens its conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(viewModel: StarredViewModel, onBack: () -> Unit, onOpenThread: (threadId: Long, recipients: List<String>) -> Unit) {
    val starred by viewModel.starred.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Starred") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val list = starred ?: return@Scaffold
        if (list.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Text(
                        "Star a message to keep it here. Long-press any message, then Star.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            items(list, key = { it.message.key }) { item ->
                val m = item.message
                val who = when {
                    m.outgoing -> "You"
                    item.recipients.size > 1 -> m.sender?.let(viewModel::displayName) ?: item.conversationName
                    else -> item.conversationName
                }
                ListItem(
                    leadingContent = {
                        if (item.recipients.size > 1) {
                            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_group), contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(24.dp))
                            }
                        } else {
                            Avatar(item.conversationName, seed = item.recipients.firstOrNull().orEmpty(), size = 44.dp, photoUri = item.recipients.singleOrNull()?.let(viewModel::photoUri))
                        }
                    },
                    overlineContent = { Text(if (item.recipients.size > 1) "${item.conversationName} · $who" else who, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    headlineContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (m.body.isNotBlank() || m.attachments.none { it.isImage }) {
                                Text(m.body.ifBlank { "Attachment" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            m.attachments.firstOrNull { it.isImage }?.let { photo ->
                                AsyncImage(
                                    model = photo.uri,
                                    contentDescription = photo.name ?: "Photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(width = 120.dp, height = 90.dp).clip(RoundedCornerShape(12.dp)),
                                )
                            }
                        }
                    },
                    supportingContent = { Text(shortTimestamp(m.timestamp)) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.unstar(item) }) {
                            Icon(Icons.Filled.Star, contentDescription = "Unstar", tint = MaterialTheme.colorScheme.tertiary)
                        }
                    },
                    modifier = Modifier.clickable { onOpenThread(m.threadId, item.recipients) },
                )
            }
        }
    }
}
