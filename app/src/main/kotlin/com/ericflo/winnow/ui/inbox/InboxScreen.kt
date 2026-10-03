package com.ericflo.winnow.ui.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.R
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.data.ConversationSummary
import kotlinx.coroutines.launch

@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onOpenConversation: (ConversationSummary) -> Unit,
    onNewChat: () -> Unit,
    onOpenFiltered: () -> Unit,
    onOpenSettings: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val farDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 6 } }
    val closeSearch = {
        searching = false
        viewModel.setQuery("")
    }
    BackHandler(enabled = searching, onBack = closeSearch)

    val navBar = WindowInsets.navigationBars.asPaddingValues()
    val direction = LocalLayoutDirection.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewChat,
                expanded = atTop,
                icon = { Icon(painterResource(R.drawable.ic_chat), contentDescription = null) },
                text = { Text("Start chat") },
            )
        },
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (searching) {
                    SearchBar(state.query, viewModel::setQuery, onClose = closeSearch)
                }
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
                        if (!state.live) item("make-default") { MakeDefaultCard(onMakeDefault) }
                    }
                    items(state.conversations, key = { it.threadId }) { conversation ->
                        ConversationRow(
                            conversation,
                            showVerdict = conversation.verdict?.effectiveAction == Action.SILENCE,
                            onClick = { onOpenConversation(conversation) },
                        )
                    }
                    if (state.conversations.isEmpty()) {
                        item("empty") {
                            Text(
                                if (state.query.isNotBlank()) "No conversations match \"${state.query}\"" else "No conversations yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = !atTop && !searching, enter = fadeIn(), exit = fadeOut()) {
                CompactBar(onSearch = { searching = true }, onMenu = { menuOpen = true })
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

    if (menuOpen) {
        MenuSheet(
            state = state,
            onDismiss = { menuOpen = false },
            onOpenFiltered = {
                menuOpen = false
                onOpenFiltered()
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
                        Text("Search conversations", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
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
