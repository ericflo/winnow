package com.ericflo.winnow.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.R
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.data.SearchHit
import com.ericflo.winnow.ui.components.Avatar
import com.ericflo.winnow.ui.review.ReviewInboxCard
import com.ericflo.winnow.ui.components.shortTimestamp
import kotlinx.coroutines.launch
import com.ericflo.winnow.data.SwipeChoice
import androidx.compose.ui.graphics.vector.rememberVectorPainter

@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onOpenThread: (threadId: Long, recipients: List<String>) -> Unit,
    onNewChat: () -> Unit,
    onOpenFiltered: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSettings: () -> Unit,
    onMakeDefault: () -> Unit,
    onOpenStarred: () -> Unit = {},
    onOpenScheduled: () -> Unit = {},
    /** In the two-pane layout, the conversation open beside the list. */
    openThreadId: Long? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheduledCount by viewModel.scheduledCount.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A conversation swiped toward Delete, waiting on the confirmation.
    var swipedToDelete by remember { mutableStateOf<Long?>(null) }
    val swipes by viewModel.swipes.collectAsStateWithLifecycle()
    var makeDefaultDismissed by rememberSaveable { mutableStateOf(false) }
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val farDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 6 } }
    val closeSearch = {
        searching = false
        viewModel.setQuery("")
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = searching && selected.isEmpty(), onBack = closeSearch)

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun archive(ids: Set<Long>) {
        viewModel.setArchived(ids, true)
        selected = emptySet()
        scope.launch {
            val result = snackbar.showSnackbar(if (ids.size == 1) "Conversation archived" else "${ids.size} conversations archived", actionLabel = "Undo")
            if (result == SnackbarResult.ActionPerformed) viewModel.setArchived(ids, false)
        }
    }

    val navBar = WindowInsets.navigationBars.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val selection = state.conversations.filter { it.threadId in selected }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (selected.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onNewChat,
                    expanded = atTop,
                    icon = { Icon(painterResource(R.drawable.ic_chat), contentDescription = null) },
                    text = { Text("Start chat") },
                )
            }
        },
        // The large header draws under the status bar and the list pads for the nav bar itself.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (searching) SearchBar(state.query, viewModel::setQuery, onClose = closeSearch)
                if (state.loading) return@Column
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = navBar.calculateStartPadding(direction),
                        end = navBar.calculateEndPadding(direction),
                        bottom = navBar.calculateBottomPadding() + 96.dp,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (!searching) {
                        item("header") { LargeHeader(onSearch = { searching = true }, onMenu = { menuOpen = true }) }
                        if (!state.live && !makeDefaultDismissed) {
                            item("make-default") { MakeDefaultCard(onMakeDefault, onDismiss = { makeDefaultDismissed = true }) }
                        }
                        item("review") {
                            ReviewInboxCard(state.review, state.classifier, onStart = viewModel::startReview, onDismiss = viewModel::dismissReview)
                        }
                        // Only worth offering when there's something unread (or the filter is on).
                        if (state.unreadConversations > 0 || state.unreadOnly) {
                            item("filters") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    FilterChip(selected = !state.unreadOnly, onClick = { viewModel.setUnreadOnly(false) }, label = { Text("All") })
                                    FilterChip(
                                        selected = state.unreadOnly,
                                        onClick = { viewModel.setUnreadOnly(!state.unreadOnly) },
                                        label = { Text(if (state.unreadConversations > 0) "Unread · ${state.unreadConversations}" else "Unread") },
                                    )
                                }
                            }
                        }
                    } else if (state.query.isNotBlank() && state.conversations.isNotEmpty()) {
                        item("h-conversations") { SectionHeader("Conversations") }
                    }
                    items(state.conversations, key = { it.threadId }) { conversation ->
                        val open = { onOpenThread(conversation.threadId, conversation.recipients) }
                        val enabled = selected.isEmpty() && !searching
                        @Composable
                        fun swipe(choice: SwipeChoice): Swipe? = if (!enabled) null else when (choice) {
                            SwipeChoice.ARCHIVE -> Swipe(painterResource(R.drawable.ic_archive), "Archive", removes = true) { archive(setOf(conversation.threadId)) }
                            SwipeChoice.DELETE -> Swipe(rememberVectorPainter(Icons.Filled.Delete), "Delete", destructive = true) { swipedToDelete = conversation.threadId }
                            SwipeChoice.READ -> if (conversation.unread) {
                                Swipe(rememberVectorPainter(Icons.Outlined.CheckCircle), "Mark as read") { viewModel.setRead(setOf(conversation.threadId), true) }
                            } else {
                                Swipe(rememberVectorPainter(Icons.Outlined.MailOutline), "Mark as unread") { viewModel.setRead(setOf(conversation.threadId), false) }
                            }
                            SwipeChoice.PIN -> Swipe(painterResource(R.drawable.ic_pin), if (conversation.pinned) "Unpin" else "Pin") {
                                viewModel.setPinned(setOf(conversation.threadId), !conversation.pinned)
                            }
                            SwipeChoice.NONE -> null
                        }
                        SwipeAction(start = swipe(swipes.first), end = swipe(swipes.second)) {
                            ConversationRow(
                                conversation,
                                showVerdict = conversation.verdict?.effectiveAction == Action.SILENCE,
                                selected = conversation.threadId in selected,
                                highlighted = conversation.threadId == openThreadId,
                                onClick = { if (selected.isEmpty()) open() else toggle(conversation.threadId) },
                                onLongClick = { toggle(conversation.threadId) },
                            )
                        }
                    }
                    if (searching && state.messageHits.isNotEmpty()) {
                        item("h-messages") { SectionHeader("Messages") }
                        items(state.messageHits, key = { "hit-${it.threadId}-${it.timestamp}" }) { hit ->
                            SearchHitRow(hit, onClick = { onOpenThread(hit.threadId, hit.recipients) })
                        }
                    }
                    if (state.conversations.isEmpty() && state.messageHits.isEmpty()) {
                        item("empty") {
                            Text(
                                if (state.query.isNotBlank()) "Nothing matches \"${state.query}\"" else "No conversations yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = !atTop && !searching && selected.isEmpty(), enter = fadeIn(), exit = fadeOut()) {
                CompactBar(onSearch = { searching = true }, onMenu = { menuOpen = true })
            }
            if (selected.isNotEmpty()) {
                SelectionBar(
                    selection = selection,
                    onClose = { selected = emptySet() },
                    onPin = { pin ->
                        viewModel.setPinned(selected, pin)
                        selected = emptySet()
                    },
                    onArchive = { archive(selected) },
                    onRead = { read ->
                        viewModel.setRead(selected, read)
                        selected = emptySet()
                    },
                    onDelete = { confirmDelete = true },
                    onBlock = { conversation ->
                        viewModel.block(conversation)
                        selected = emptySet()
                    },
                )
            }
            AnimatedVisibility(
                visible = farDown,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Scroll to top") }
            }
        }
    }

    swipedToDelete?.let { id ->
        DeleteDialog(
            count = 1,
            onConfirm = {
                viewModel.delete(setOf(id))
                swipedToDelete = null
            },
            onDismiss = { swipedToDelete = null },
        )
    }

    if (confirmDelete) {
        DeleteDialog(
            count = selected.size,
            onConfirm = {
                viewModel.delete(selected)
                selected = emptySet()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (menuOpen) {
        MenuSheet(
            state = state,
            onDismiss = { menuOpen = false },
            onOpenFiltered = {
                menuOpen = false
                onOpenFiltered()
            },
            onOpenArchived = {
                menuOpen = false
                onOpenArchived()
            },
            onOpenStarred = {
                menuOpen = false
                onOpenStarred()
            },
            scheduledCount = scheduledCount,
            onOpenScheduled = {
                menuOpen = false
                onOpenScheduled()
            },
            onOpenActivity = {
                menuOpen = false
                onOpenActivity()
            },
            onMarkAllRead = {
                menuOpen = false
                viewModel.markAllRead()
            },
            onOpenSettings = {
                menuOpen = false
                onOpenSettings()
            },
            onMakeDefault = onMakeDefault,
        )
    }
}

/**
 * What a swipe does: [icon] and [label] show under the row as it slides, and [run] runs once
 * it's swiped away. A row that [removes] itself from the list stays swiped; any other slides back.
 */
class Swipe(
    val icon: Painter,
    val label: String,
    val removes: Boolean = false,
    val destructive: Boolean = false,
    val run: () -> Unit,
)

/** A row with a [Swipe] toward the end ([start], a right swipe in left-to-right) and/or the start ([end]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeAction(start: Swipe?, end: Swipe?, content: @Composable () -> Unit) {
    if (start == null && end == null) {
        content()
        return
    }
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    // Not rememberSwipeToDismissBoxState(), which is saveable: a lazy list restores saved state
    // when an item's key comes back, and a restored "dismissed" row would run its swipe again
    // (archive, Undo, archived again).
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    val scope = rememberCoroutineScope()
    val currentStart by rememberUpdatedState(start)
    val currentEnd by rememberUpdatedState(end)
    // Must keep its identity: SwipeToDismissBox restarts its dismiss effect when this changes,
    // and calls it again with whatever direction the row has by then, Settled included.
    val onDismiss = remember(state) {
        { value: SwipeToDismissBoxValue ->
            val swipe = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentStart
                SwipeToDismissBoxValue.EndToStart -> currentEnd
                SwipeToDismissBoxValue.Settled -> null
            }
            swipe?.run?.invoke()
            if (swipe?.removes != true) scope.launch { state.reset() }
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = start != null,
        enableDismissFromEndToStart = end != null,
        onDismiss = onDismiss,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val swipe = (if (toEnd) start else end) ?: return@SwipeToDismissBox
            val colors = MaterialTheme.colorScheme
            val (container, onContainer) = if (swipe.destructive) colors.errorContainer to colors.onErrorContainer else colors.primaryContainer to colors.onPrimaryContainer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize().background(container).padding(horizontal = 28.dp),
            ) {
                if (!toEnd) Spacer(Modifier.weight(1f))
                Icon(swipe.icon, contentDescription = null, tint = onContainer)
                Spacer(Modifier.width(8.dp))
                Text(swipe.label, style = MaterialTheme.typography.labelLarge, color = onContainer)
            }
        },
    ) { content() }
}

@Composable
private fun SelectionBar(
    selection: List<ConversationSummary>,
    onClose: () -> Unit,
    onPin: (Boolean) -> Unit,
    onArchive: () -> Unit,
    onRead: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onBlock: (ConversationSummary) -> Unit,
) {
    var overflow by remember { mutableStateOf(false) }
    val allPinned = selection.isNotEmpty() && selection.all { it.pinned }
    val anyUnread = selection.any { it.unread }
    val blockable = selection.singleOrNull()?.takeIf { !it.isGroup }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
            Text("${selection.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { onPin(!allPinned) }) {
                Icon(painterResource(R.drawable.ic_pin), contentDescription = if (allPinned) "Unpin" else "Pin")
            }
            IconButton(onClick = onArchive) { Icon(painterResource(R.drawable.ic_archive), contentDescription = "Archive") }
            IconButton(onClick = { onRead(anyUnread) }) {
                Icon(if (anyUnread) Icons.Outlined.CheckCircle else Icons.Outlined.MailOutline, contentDescription = if (anyUnread) "Mark as read" else "Mark as unread")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            if (blockable != null) {
                Box {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text("Block and filter") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_block), contentDescription = null) },
                            onClick = {
                                overflow = false
                                onBlock(blockable)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete this conversation?" else "Delete $count conversations?") },
        text = { Text("Messages are removed from this phone. This can't be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SearchHitRow(hit: SearchHit, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Avatar(hit.displayName, seed = hit.recipients.firstOrNull().orEmpty(), size = 44.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                hit.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(shortTimestamp(hit.timestamp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The tall tinted header with a centered title, which hands off to the list's rounded sheet. */
@Composable
private fun LargeHeader(onSearch: () -> Unit, onMenu: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(212.dp)) {
            Text(
                "Winnow",
                style = MaterialTheme.typography.displaySmall,
                color = colors.onSurface,
                modifier = Modifier.align(Alignment.Center),
            )
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search conversations") }
                MenuButton(onMenu)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(colors.surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        )
    }
}

@Composable
private fun CompactBar(onSearch: () -> Unit, onMenu: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 20.dp, end = 12.dp),
        ) {
            Text("Winnow", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search conversations") }
            MenuButton(onMenu)
        }
    }
}

/** Where Messages shows your profile photo: Winnow's mark, opening the menu. */
@Composable
private fun MenuButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_notification),
                contentDescription = "Menu",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Search conversations and messages", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}

@Composable
private fun MakeDefaultCard(onMakeDefault: () -> Unit, onDismiss: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onMakeDefault) { Text("Set as default") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}
