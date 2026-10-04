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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ListItem
import com.ericflo.winnow.ui.components.RestrictedSettingHelp
import com.ericflo.winnow.ui.components.AttachmentThumbnail
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.ericflo.winnow.ui.components.GroupAvatar
import com.ericflo.winnow.ui.review.ReviewInboxCard
import com.ericflo.winnow.ui.components.shortTimestamp
import kotlinx.coroutines.launch
import com.ericflo.winnow.data.SwipeChoice
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.ericflo.winnow.data.searchSnippet
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onOpenThread: (threadId: Long, recipients: List<String>) -> Unit,
    /** A message found by search: its conversation, opened onto it with the words still searched for. */
    onOpenSearchHit: (hit: SearchHit, query: String) -> Unit = { hit, _ -> onOpenThread(hit.threadId, hit.recipients) },
    onNewChat: () -> Unit,
    onOpenFiltered: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSettings: () -> Unit,
    onMakeDefault: () -> Unit,
    onOpenStarred: () -> Unit = {},
    onOpenScheduled: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    /** In the two-pane layout, the conversation open beside the list. */
    openThreadId: Long? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheduledCount by viewModel.scheduledCount.collectAsStateWithLifecycle()
    val trashCount by viewModel.trashCount.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var searching by rememberSaveable { mutableStateOf(false) }
    // Ctrl+F: search, unless a conversation beside the list (a wide screen) has the keyboard's attention.
    val shortcutLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(openThreadId, shortcutLifecycle) {
        if (openThreadId != null) return@LaunchedEffect
        shortcutLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.keyShortcuts.collect { if (it == com.ericflo.winnow.KeyShortcut.FIND) searching = true }
        }
    }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A conversation swiped toward Delete, waiting on the confirmation.
    var swipedToDelete by remember { mutableStateOf<Long?>(null) }
    val swipes by viewModel.swipes.collectAsStateWithLifecycle()
    var makeDefaultDismissed by rememberSaveable { mutableStateOf(false) }
    var alertsOffDismissed by rememberSaveable { mutableStateOf(false) }
    val alertsOff by viewModel.alertsOff.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val farDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 6 } }
    // The search field shows what's typed straight from here: echoed back through the
    // ViewModel's combined flows, fast typing would drop and reorder characters.
    var typed by rememberSaveable { mutableStateOf("") }
    val browsing by viewModel.browsing.collectAsStateWithLifecycle()
    val browseResults by viewModel.browseResults.collectAsStateWithLifecycle()
    // Restored after the app was closed (or brought back into composition), the field and the
    // ViewModel's filter agree again.
    LaunchedEffect(Unit) { viewModel.setQuery(if (searching) typed else "") }
    val closeSearch = {
        searching = false
        typed = ""
        viewModel.setQuery("")
        viewModel.browse(null)
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
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                if (ids.size == 1) "Conversation archived" else "${ids.size} conversations archived",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
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
            // Not over search results: a search is for finding, and Back leads out of it.
            if (selected.isEmpty() && !searching) {
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
                if (searching) SearchBar(typed, { typed = it; viewModel.setQuery(it) }, onClose = closeSearch)
                if (state.loading) {
                    // The title while the first list loads (a cold start with many conversations
                    // takes a moment), not a blank screen with only the Start chat button on it.
                    // The list itself waits, so it comes back to where it was scrolled.
                    if (!searching) {
                        // The list's own side padding, so nothing moves when it arrives.
                        Box(Modifier.padding(start = navBar.calculateStartPadding(direction), end = navBar.calculateEndPadding(direction))) {
                            if (LocalConfiguration.current.screenHeightDp < SHORT_SCREEN_DP) {
                                CompactBar(onSearch = { searching = true }, onMenu = { menuOpen = true })
                            } else {
                                LargeHeader(onSearch = { searching = true }, onMenu = { menuOpen = true })
                            }
                        }
                    }
                    return@Column
                }
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
                        item("header") {
                            // A phone on its side has no height to spare for the tall title.
                            if (LocalConfiguration.current.screenHeightDp < SHORT_SCREEN_DP) {
                                CompactBar(onSearch = { searching = true }, onMenu = { menuOpen = true })
                            } else {
                                LargeHeader(onSearch = { searching = true }, onMenu = { menuOpen = true })
                            }
                        }
                        if (!state.live && !makeDefaultDismissed) {
                            item("make-default") { MakeDefaultCard(onMakeDefault, onDismiss = { makeDefaultDismissed = true }) }
                        }
                        // Texts arriving without a sound or a notification at all: worth saying every time the inbox opens.
                        // Only once Winnow is the SMS app: before that, the old app still sounds the alerts.
                        if (state.live && state.isDefault && alertsOff && !alertsOffDismissed) {
                            item("alerts-off") {
                                AlertsOffCard(
                                    onTurnOn = { runCatching { context.startActivity(viewModel.alertSettingsIntent()) } },
                                    onDismiss = { alertsOffDismissed = true },
                                )
                            }
                        }
                        item("review") {
                            ReviewInboxCard(state.review, state.classifier, onStart = viewModel::startReview, onDismiss = viewModel::dismissReview)
                        }
                        // Only worth offering when there's something unread (or the filter is on).
                        // Only worth offering when there's something to narrow down to (or a filter is on).
                        if (state.unreadConversations > 0 || state.kinds.isNotEmpty() || state.filter != InboxFilter.ALL) {
                            item("filters") {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                                ) {
                                    // The one that's on stays, even once nothing's left under it, so it can be turned off.
                                    val chips = (
                                        listOf(InboxFilter.ALL) +
                                            listOfNotNull(InboxFilter.UNREAD.takeIf { state.unreadConversations > 0 || state.filter == InboxFilter.UNREAD }) +
                                            state.kinds + state.filter
                                        ).distinct()
                                    chips.forEach { chip ->
                                        FilterChip(
                                            selected = state.filter == chip,
                                            // Tapping the one that's on goes back to everything.
                                            onClick = { viewModel.setFilter(if (state.filter == chip) InboxFilter.ALL else chip) },
                                            label = {
                                                Text(if (chip == InboxFilter.UNREAD && state.unreadConversations > 0) "Unread · ${state.unreadConversations}" else chip.label)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else if (state.query.isNotBlank() && state.conversations.isNotEmpty()) {
                        item("h-conversations") { SectionHeader("Conversations") }
                    }
                    // Search with nothing typed: browse every conversation's photos and videos, or links.
                    if (searching && typed.isBlank()) {
                        item("browse") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                FilterChip(
                                    selected = browsing == Browse.MEDIA,
                                    onClick = { viewModel.browse(if (browsing == Browse.MEDIA) null else Browse.MEDIA) },
                                    label = { Text("Photos & videos") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_photo), contentDescription = null, modifier = Modifier.size(18.dp)) },
                                )
                                FilterChip(
                                    selected = browsing == Browse.LINKS,
                                    onClick = { viewModel.browse(if (browsing == Browse.LINKS) null else Browse.LINKS) },
                                    label = { Text("Links") },
                                )
                            }
                        }
                    }
                    val browsingNow = if (searching && typed.isBlank()) browsing else null
                    if (browsingNow != null) {
                        browseItems(browsingNow, browseResults, onOpen = { hit -> onOpenSearchHit(hit, "") })
                    }
                    items(if (browsingNow != null) emptyList() else state.conversations, key = { it.threadId }) { conversation ->
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
                                nudge = state.nudges[conversation.threadId],
                                onDismissNudge = { viewModel.dismissNudge(conversation) },
                            )
                        }
                    }
                    if (searching && state.messageHits.isNotEmpty()) {
                        item("h-messages") { SectionHeader("Messages") }
                        // By message: two picture messages in one thread can share a second.
                        items(state.messageHits, key = { "hit-${it.key ?: "${it.threadId}-${it.timestamp}"}" }) { hit ->
                            SearchHitRow(hit, state.query, filtered = hit.threadId in state.filteredThreads, onClick = { onOpenSearchHit(hit, state.query.trim()) })
                        }
                    }
                    if (state.conversations.isEmpty() && state.messageHits.isEmpty() && !(searching && typed.isBlank() && browsing != null)) {
                        item("empty") {
                            Text(
                                when {
                                    state.query.isNotBlank() -> "Nothing matches \"${state.query}\""
                                    state.filter != InboxFilter.ALL -> "Nothing under ${state.filter.label} right now"
                                    else -> "No conversations yet"
                                },
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

    // Deleted conversations sit in Recently deleted; Undo puts them straight back.
    val deleted = { items: List<com.ericflo.winnow.backup.Trash.Item> ->
        scope.launch {
            val message = if (items.size == 1) "Moved to Recently deleted" else "${items.size} moved to Recently deleted"
            if (snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) viewModel.restore(items)
        }
        Unit
    }
    swipedToDelete?.let { id ->
        DeleteDialog(
            count = 1,
            onConfirm = {
                viewModel.delete(setOf(id), deleted)
                swipedToDelete = null
            },
            onDismiss = { swipedToDelete = null },
        )
    }

    if (confirmDelete) {
        DeleteDialog(
            count = selected.size,
            onConfirm = {
                viewModel.delete(selected, deleted)
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
            trashCount = trashCount,
            onOpenTrash = {
                menuOpen = false
                onOpenTrash()
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
        // What a swipe does, as actions a screen reader offers: a swipe is a gesture TalkBack
        // takes for itself.
        modifier = Modifier.semantics {
            customActions = listOfNotNull(start, end).distinctBy { it.label }.map { swipe ->
                CustomAccessibilityAction(swipe.label) { swipe.run(); true }
            }
        },
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
        text = { Text("Recently deleted keeps them for 30 days, in case you want them back.") },
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

/** Photos and videos three across, or links, each opening its conversation at that message. */
private fun LazyListScope.browseItems(kind: Browse, results: BrowseResults, onOpen: (SearchHit) -> Unit) {
    if (results.loading) {
        item("browse-loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) }
        return
    }
    when (kind) {
        Browse.MEDIA -> {
            if (results.media.isEmpty()) item("browse-empty") { BrowseEmpty("No photos or videos yet") }
            items(results.media.chunked(3), key = { row -> "media-${row.first().attachment.uri}" }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    row.forEach { media ->
                        AttachmentThumbnail(
                            media.attachment.uri,
                            media.attachment.contentType,
                            media.attachment.name,
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClickLabel = "Open in the conversation with ${media.displayName}") {
                                    onOpen(SearchHit(media.threadId, media.recipients, media.displayName, "", media.timestamp, media.key))
                                },
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Browse.LINKS -> {
            if (results.links.isEmpty()) item("browse-empty") { BrowseEmpty("No links yet") }
            items(results.links, key = { "link-${it.hit.key}-${it.url}" }) { link ->
                ListItem(
                    leadingContent = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
                    headlineContent = { Text(link.host, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text("${link.hit.displayName} · ${shortTimestamp(link.hit.timestamp)}\n${link.url}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    // The conversation, not the link: the message says who sent it and why Winnow trusts it or doesn't.
                    modifier = Modifier.clickable(onClickLabel = "Open in the conversation") { onOpen(link.hit) },
                )
            }
        }
    }
}

@Composable
private fun BrowseEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

@Composable
private fun SearchHitRow(hit: SearchHit, query: String, filtered: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (hit.members.size > 1) GroupAvatar(hit.members, 44.dp)
        else Avatar(hit.displayName, seed = hit.recipients.firstOrNull().orEmpty(), size = 44.dp, photoUri = hit.photoUri)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hit.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                // Found in Filtered: not what an inbox result usually is, so it says so.
                if (filtered) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Filtered",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                matchesInBold(searchSnippet(hit.body, query), query, MaterialTheme.colorScheme.onSurface),
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

/** [text] with each match of [query] in bold, in [color], as Messages shows what a search found. */
private fun matchesInBold(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val wanted = query.trim()
    if (wanted.isEmpty()) return@buildAnnotatedString
    var at = text.indexOf(wanted, ignoreCase = true)
    while (at >= 0) {
        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color), at, at + wanted.length)
        at = text.indexOf(wanted, at + wanted.length, ignoreCase = true)
    }
}

/** Shorter than this (a phone on its side), the inbox starts with the compact bar instead of [LargeHeader]. */
private const val SHORT_SCREEN_DP = 480

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
private fun AlertsOffCard(onTurnOn: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Notifications are off", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(4.dp))
            Text(
                "New texts arrive without a sound or a notification, even from people you know.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onTurnOn) { Text("Turn on") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Not now") }
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
                "Your texts appear here once Winnow is your default SMS app: until then Android doesn't let it read them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onMakeDefault) { Text("Set as default") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
            RestrictedSettingHelp(Modifier.padding(top = 12.dp))
        }
    }
}
