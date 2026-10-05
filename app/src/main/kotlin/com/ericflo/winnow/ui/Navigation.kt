package com.ericflo.winnow.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.ui.activity.ActivityScreen
import com.ericflo.winnow.ui.activity.ActivityViewModel
import com.ericflo.winnow.data.joinAddresses
import com.ericflo.winnow.data.splitAddresses
import com.ericflo.winnow.ui.details.ConversationDetailsScreen
import com.ericflo.winnow.ui.details.ConversationDetailsViewModel
import com.ericflo.winnow.ui.inbox.ConversationListScreen
import com.ericflo.winnow.ui.metrics.MetricsScreen
import com.ericflo.winnow.ui.starred.StarredScreen
import com.ericflo.winnow.ui.starred.StarredViewModel
import com.ericflo.winnow.ui.metrics.MetricsViewModel
import com.ericflo.winnow.ui.inbox.InboxScreen
import com.ericflo.winnow.ui.inbox.InboxViewModel
import com.ericflo.winnow.ui.inbox.ListMode
import com.ericflo.winnow.ui.newchat.NewChatScreen
import com.ericflo.winnow.ui.newchat.NewChatViewModel
import com.ericflo.winnow.ui.onboarding.OnboardingScreen
import com.ericflo.winnow.ui.settings.SenderRulesScreen
import com.ericflo.winnow.ui.settings.SettingsScreen
import com.ericflo.winnow.ui.settings.SettingsViewModel
import com.ericflo.winnow.ui.thread.ThreadScreen
import com.ericflo.winnow.ui.thread.ThreadViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import com.ericflo.winnow.data.OutgoingAttachment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.ericflo.winnow.ui.scheduled.ScheduledScreen
import com.ericflo.winnow.ui.trash.RecentlyDeletedScreen
import com.ericflo.winnow.ui.trash.RecentlyDeletedViewModel
import com.ericflo.winnow.ui.scheduled.ScheduledViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.key
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.NavBackStackEntry
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavDestination.Companion.hasRoute
import com.ericflo.winnow.data.normalizeAddress
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Where US carriers collect forwarded spam ("SPAM" on a keypad). */
const val CARRIER_SPAM_SHORT_CODE = "7726"

@Serializable
data object OnboardingRoute

@Serializable
data object InboxRoute

/**
 * [recipients] is a comma-joined address list. A negative [threadId] means "look up or create
 * the thread for these recipients".
 */
@Serializable
data class ThreadRoute(
    val threadId: Long,
    val recipients: String,
    val draft: String = "",
    /** Shared photos or videos to attach, as [SharedAttachments.encode] wrote them. */
    val attachments: String = "",
    /** Find-in-conversation to open with, from a search result. */
    val search: String = "",
    /** The message to show first: the search result tapped, or a starred message. */
    val focus: String = "",
)

@Serializable
data class DetailsRoute(val threadId: Long, val recipients: String)

@Serializable
data object SettingsRoute

@Serializable
data object SenderRulesRoute

@Serializable
data object FilteredRoute

@Serializable
data object ArchivedRoute

@Serializable
data object ActivityRoute

@Serializable
data object MetricsRoute

@Serializable
data object StarredRoute

@Serializable
data object ScheduledRoute

@Serializable
data object TrainRoute

@Serializable
data object RecentlyDeletedRoute

/** A backlog run's results (see RunScreen). */
@Serializable
data class RunRoute(val runId: Long)

/** Every run (see RunsScreen). */
@Serializable
data object RunsRoute

/** Winnow's model, opened up (see ModelScreen). */
@Serializable
data object ModelRoute

/**
 * [draft] carries a forwarded message into the conversation the user picks; [with] (comma-joined
 * addresses) starts a new group with those people picked, from "Add people".
 */
@Serializable
data class NewChatRoute(val draft: String = "", val attachments: String = "", val with: String = "")

/** Shared attachments as one route argument, since routes take simple values. */
object SharedAttachments {
    @Serializable
    private data class Item(val uri: String, val type: String, val name: String? = null)

    fun encode(items: List<OutgoingAttachment>): String =
        if (items.isEmpty()) "" else Json.encodeToString(ListSerializer(Item.serializer()), items.map { Item(it.uri, it.contentType, it.name) })

    fun decode(value: String): List<OutgoingAttachment> =
        if (value.isEmpty()) emptyList()
        else runCatching { Json.decodeFromString(ListSerializer(Item.serializer()), value) }.getOrDefault(emptyList()).map { OutgoingAttachment(it.uri, it.type, it.name) }
}

@Composable
fun WinnowNavHost(
    container: AppContainer,
    pendingRoute: StateFlow<Any?>,
    onRouteConsumed: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    // Wait for settings so a returning user never sees onboarding flash by.
    val onboarded = settings?.onboarded ?: return
    val pending by pendingRoute.collectAsStateWithLifecycle()
    val pane = viewModel { ConversationPane(createSavedStateHandle(), container.messages.deletedThreads()) }
    // Who each full-screen conversation in the back stack is with, by entry, so a link to one
    // that's further down goes back to it instead of opening a second copy.
    val threadEntries = remember { HashMap<String, Set<String>>() }
    val twoPane = isTwoPane()
    LaunchedEffect(pending) {
        pending?.let {
            when {
                twoPane && it is ThreadRoute && openInPane(it, nav, pane, container) -> Unit
                it is ThreadRoute -> openThreadRoute(it, nav, threadEntries)
                else -> nav.navigate(it) { launchSingleTop = true }
            }
            onRouteConsumed()
        }
    }
    val openThread = { threadId: Long, recipients: List<String> -> nav.navigate(ThreadRoute(threadId, joinAddresses(recipients))) }
    val openMessage = { threadId: Long, recipients: List<String>, key: String ->
        nav.navigate(ThreadRoute(threadId, joinAddresses(recipients), focus = key))
    }

    // Every Back below is dropUnlessResumed: a second tap during the exit animation would
    // otherwise pop the screen underneath too, down to an empty, blank app.
    // Screens slide a little and fade, as Android's own apps move between screens, for Back
    // and the back gesture alike. NavHost's default back gesture shrinks the screen instead,
    // which looked broken on a phone.
    NavHost(
        navController = nav,
        startDestination = if (onboarded) InboxRoute else OnboardingRoute,
        enterTransition = { forwardEnter() },
        exitTransition = { forwardExit() },
        popEnterTransition = { backEnter() },
        popExitTransition = { backExit() },
        predictivePopEnterTransition = { backEnter() },
        predictivePopExitTransition = { backExit() },
    ) {
        composable<OnboardingRoute> {
            OnboardingScreen(
                isDefault = container::isDefaultSmsApp,
                onMakeDefault = onMakeDefault,
                initialClassifier = settings?.provider ?: com.ericflo.winnow.data.ProviderKind.ON_DEVICE,
                onChooseClassifier = { kind, key ->
                    scope.launch {
                        container.settings.update { s ->
                            val keyed = if (key.isBlank()) s.providers else s.providers + (kind to s.settingsFor(kind).copy(apiKey = key))
                            s.copy(provider = kind, providers = keyed)
                        }
                    }
                },
                onFinish = {
                    scope.launch { container.settings.update { it.copy(onboarded = true) } }
                    nav.navigate(InboxRoute) { popUpTo<OnboardingRoute> { inclusive = true } }
                },
                backups = container.backups,
            )
        }
        composable<InboxRoute> {
            // A tablet, an unfolded foldable or a wide window: the list and a conversation side by side.
            val twoPane = isTwoPane()
            val opened by pane.open.collectAsStateWithLifecycle()
            BackHandler(enabled = opened != null) { pane.close() }
            val inbox = @Composable { modifier: Modifier ->
                Box(modifier) {
                    InboxScreen(
                        viewModel = viewModel { InboxViewModel(container, ListMode.INBOX) },
                        onOpenThread = if (twoPane) { id, recipients -> pane.open(id, joinAddresses(recipients)) } else openThread,
                        onOpenSearchHit = { hit, query ->
                            if (twoPane) {
                                // No words (a photo or link browsed to): just that message, no find bar.
                                pane.open(hit.threadId, joinAddresses(hit.recipients), ThreadViewModel.SearchRequest(query.ifEmpty { null }, hit.key))
                            } else {
                                nav.navigate(ThreadRoute(hit.threadId, joinAddresses(hit.recipients), search = query, focus = hit.key.orEmpty()))
                            }
                        },
                        onNewChat = { nav.navigate(NewChatRoute()) },
                        onOpenFiltered = { nav.navigate(FilteredRoute) },
                        onOpenArchived = { nav.navigate(ArchivedRoute) },
                        onOpenActivity = { nav.navigate(ActivityRoute) },
                        onOpenSettings = { nav.navigate(SettingsRoute) },
                        onMakeDefault = onMakeDefault,
                        onOpenStarred = { nav.navigate(StarredRoute) },
                        onOpenTrain = { nav.navigate(TrainRoute) },
                        onOpenModel = { nav.navigate(ModelRoute) },
                        onOpenScheduled = { nav.navigate(ScheduledRoute) },
                        onOpenTrash = { nav.navigate(RecentlyDeletedRoute) },
                        openThreadId = opened?.threadId.takeIf { twoPane },
                    )
                }
            }
            // Wide: the list and the open conversation side by side. Narrow: the list, or full size the
            // conversation that was open beside it when the window narrowed (a tablet turned to
            // portrait). The conversation keeps its place in the tree either way, so a photo being
            // taken or a permission being asked for across the change still arrives.
            Row(Modifier.fillMaxSize()) {
                if (twoPane || opened == null) {
                    inbox(if (twoPane) Modifier.width(LIST_PANE_WIDTH).fillMaxHeight() else Modifier.fillMaxSize())
                }
                if (twoPane) VerticalDivider()
                if (twoPane || opened != null) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val open = opened
                        if (open == null) {
                            EmptyConversationPane()
                        } else {
                            // Keyed, so nothing remembered on screen (scroll, search, a playing voice
                            // message) carries over from the last conversation.
                            key(open.threadId) {
                                ProvideViewModelStore(remember(open.threadId) { pane.storeFor(open) }) {
                                    val thread = viewModel { ThreadViewModel(container, open.threadId, splitAddresses(open.recipients)) }
                                    val searchRequest by pane.searchRequest.collectAsStateWithLifecycle()
                                    LaunchedEffect(searchRequest) {
                                        searchRequest?.let { thread.requestSearch(it); pane.searchRequestHandled() }
                                    }
                                    ThreadScreen(
                                        viewModel = thread,
                                        onBack = pane::close,
                                        onForward = { text, attachments -> nav.navigate(NewChatRoute(draft = text, attachments = attachments)) },
                                        onReportSpam = whenResumed { text -> nav.navigate(ThreadRoute(-1, CARRIER_SPAM_SHORT_CODE, text)) },
                                        onOpenDetails = whenResumed { threadId -> nav.navigate(DetailsRoute(threadId, open.recipients)) },
                                        onMessageNumber = whenResumed { number -> nav.navigate(ThreadRoute(-1, number)) },
                onOpenRun = whenResumed { runId -> nav.navigate(RunRoute(runId)) },
                                        showBack = !twoPane,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        composable<RecentlyDeletedRoute> {
            RecentlyDeletedScreen(viewModel = viewModel { RecentlyDeletedViewModel(container) }, onBack = dropUnlessResumed { nav.popBackStack() })
        }
        composable<ScheduledRoute> {
            ScheduledScreen(viewModel = viewModel { ScheduledViewModel(container) }, onBack = dropUnlessResumed { nav.popBackStack() }, onOpenThread = openThread)
        }
        composable<TrainRoute> {
            com.ericflo.winnow.ui.train.TrainScreen(
                viewModel = viewModel { com.ericflo.winnow.ui.train.TrainViewModel(container) },
                onBack = dropUnlessResumed { nav.popBackStack() },
                // The whole conversation, for context; Back returns to the round as it was.
                onOpenThread = openThread,
                onOpenRun = { nav.navigate(RunRoute(it)) },
                onOpenRuns = { nav.navigate(RunsRoute) },
                onOpenModel = { nav.navigate(ModelRoute) },
            )
        }
        composable<RunRoute> { entry ->
            val route = entry.toRoute<RunRoute>()
            com.ericflo.winnow.ui.runs.RunScreen(
                viewModel = viewModel { com.ericflo.winnow.ui.runs.RunViewModel(container, route.runId) },
                onBack = dropUnlessResumed { nav.popBackStack() },
                onOpenThread = openThread,
            )
        }
        composable<ModelRoute> {
            com.ericflo.winnow.ui.model.ModelScreen(
                viewModel = viewModel { com.ericflo.winnow.ui.model.ModelViewModel(container) },
                onBack = dropUnlessResumed { nav.popBackStack() },
                onOpenMetrics = { nav.navigate(MetricsRoute) },
                onOpenRuns = { nav.navigate(RunsRoute) },
                onOpenTrain = { nav.navigate(TrainRoute) },
                onOpenThread = openThread,
            )
        }
        composable<RunsRoute> {
            com.ericflo.winnow.ui.runs.RunsScreen(
                viewModel = viewModel { com.ericflo.winnow.ui.runs.RunsViewModel(container) },
                onBack = dropUnlessResumed { nav.popBackStack() },
                onOpenRun = { nav.navigate(RunRoute(it)) },
            )
        }
        composable<StarredRoute> {
            StarredScreen(viewModel = viewModel { StarredViewModel(container) }, onBack = dropUnlessResumed { nav.popBackStack() }, onOpenMessage = openMessage)
        }
        composable<FilteredRoute> {
            ConversationListScreen(
                viewModel = viewModel { InboxViewModel(container, ListMode.FILTERED) },
                mode = ListMode.FILTERED,
                onBack = dropUnlessResumed { nav.popBackStack() },
                onOpenThread = openThread,
                onOpenMetrics = { nav.navigate(MetricsRoute) },
            )
        }
        composable<ArchivedRoute> {
            ConversationListScreen(
                viewModel = viewModel { InboxViewModel(container, ListMode.ARCHIVED) },
                mode = ListMode.ARCHIVED,
                onBack = dropUnlessResumed { nav.popBackStack() },
                onOpenThread = openThread,
            )
        }
        composable<NewChatRoute> { entry ->
            val route = entry.toRoute<NewChatRoute>()
            NewChatScreen(
                viewModel = viewModel { NewChatViewModel(container, splitAddresses(route.with)) },
                onBack = dropUnlessResumed { nav.popBackStack() },
                onStart = { recipients ->
                    nav.navigate(ThreadRoute(-1, joinAddresses(recipients), route.draft, route.attachments)) {
                        popUpTo<NewChatRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<ThreadRoute> { entry ->
            val route = entry.toRoute<ThreadRoute>()
            LaunchedEffect(entry.id) {
                threadEntries[entry.id] = recipientKey(route.recipients)
                entry.lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) threadEntries.remove(entry.id) })
            }
            // A notification for another conversation replaces this one in place: start over on screen too.
            key(route.threadId, route.recipients) {
            // Keyed by conversation: a notification or SENDTO intent for another thread reuses this
            // entry (launchSingleTop), and must not get the previous thread's ViewModel back.
            val threadVm = viewModel(key = "thread:${route.threadId}:${route.recipients}") {
                    ThreadViewModel(container, route.threadId, splitAddresses(route.recipients)).also { vm ->
                        // Once: after the app is closed and this entry restored, the draft saved
                        // since (more text, more attachments) is what comes back, not the share.
                        if (entry.savedStateHandle.get<Boolean>(ROUTE_APPLIED) != true) {
                            entry.savedStateHandle[ROUTE_APPLIED] = true
                            if (route.draft.isNotEmpty()) vm.setDraft(route.draft)
                            SharedAttachments.decode(route.attachments).forEach(vm::addAttachment)
                        }
                        if (route.search.isNotEmpty() || route.focus.isNotEmpty()) {
                            vm.requestSearch(ThreadViewModel.SearchRequest(route.search.ifEmpty { null }, route.focus.ifEmpty { null }))
                        }
                    }
                }
            // A message to show, for this conversation already on screen (a reminder tapped).
            LaunchedEffect(entry.id) {
                entry.savedStateHandle.getStateFlow(PENDING_FOCUS, "").collect { key ->
                    if (key.isEmpty()) return@collect
                    entry.savedStateHandle[PENDING_FOCUS] = ""
                    threadVm.requestSearch(ThreadViewModel.SearchRequest(null, key))
                }
            }
            ThreadScreen(
                viewModel = threadVm,
                onBack = dropUnlessResumed { nav.popBackStack() },
                onForward = { text, attachments -> nav.navigate(NewChatRoute(draft = text, attachments = attachments)) },
                onReportSpam = whenResumed { text -> nav.navigate(ThreadRoute(-1, CARRIER_SPAM_SHORT_CODE, text)) },
                onOpenDetails = whenResumed { threadId -> nav.navigate(DetailsRoute(threadId, route.recipients)) },
                onMessageNumber = whenResumed { number -> nav.navigate(ThreadRoute(-1, number)) },
                onOpenRun = whenResumed { runId -> nav.navigate(RunRoute(runId)) },
            )
            }
        }
        composable<DetailsRoute> { entry ->
            val route = entry.toRoute<DetailsRoute>()
            ConversationDetailsScreen(
                viewModel = viewModel { ConversationDetailsViewModel(container, route.threadId, splitAddresses(route.recipients)) },
                onBack = dropUnlessResumed { nav.popBackStack() },
                onDeleted = { nav.popBackStack<InboxRoute>(inclusive = false) },
                onAddPeople = dropUnlessResumed { nav.navigate(NewChatRoute(with = route.recipients)) },
            )
        }
        composable<ActivityRoute> {
            ActivityScreen(
                viewModel = viewModel { ActivityViewModel(container) },
                onBack = dropUnlessResumed { nav.popBackStack() },
                onOpenMetrics = { nav.navigate(MetricsRoute) },
                onOpenModel = { nav.navigate(ModelRoute) },
            )
        }
        composable<MetricsRoute> {
            MetricsScreen(viewModel = viewModel { MetricsViewModel(container) }, onBack = dropUnlessResumed { nav.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(
                viewModel = viewModel { SettingsViewModel(container) },
                onBack = dropUnlessResumed { nav.popBackStack() },
                onMakeDefault = onMakeDefault,
                onOpenSenderRules = { nav.navigate(SenderRulesRoute) },
            )
        }
        composable<SenderRulesRoute> {
            SenderRulesScreen(container = container, onBack = dropUnlessResumed { nav.popBackStack() })
        }
    }
}

/** Window width at which the inbox shows a conversation beside the list instead of on top of it. */
private const val TWO_PANE_MIN_WIDTH_DP = 840

/** Set on a conversation's back stack entry once its route's draft and attachments are in. */
private const val ROUTE_APPLIED = "routeApplied"
private const val PENDING_FOCUS = "pendingFocus"

/** Below this height (a phone on its side) two panes leave no room once the keyboard is up. */
private const val TWO_PANE_MIN_HEIGHT_DP = 480

/** A tablet, an unfolded foldable or a big window; not a phone turned sideways, however wide. */
@Composable
private fun isTwoPane(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp >= TWO_PANE_MIN_WIDTH_DP && configuration.screenHeightDp >= TWO_PANE_MIN_HEIGHT_DP
}
private val LIST_PANE_WIDTH = 400.dp

@Composable
private fun EmptyConversationPane() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Text("Choose a conversation", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [navigate], but only while this screen is resumed: a double tap doesn't open the next screen twice. */
@Composable
private fun <T> whenResumed(navigate: (T) -> Unit): (T) -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navigate(it) }
}

/**
 * A conversation from a notification or another app. The one already open is left alone (its
 * composer keeps what's in it) unless the route brings a draft or attachments; any other opens
 * on top, in its own back stack entry, so Back returns to the one before, half-written message
 * and all.
 */
private fun openThreadRoute(route: ThreadRoute, nav: NavHostController, threadEntries: Map<String, Set<String>>) {
    val wanted = recipientKey(route.recipients)
    fun showing(entry: NavBackStackEntry?) =
        entry?.destination?.hasRoute<ThreadRoute>() == true && recipientKey(entry.toRoute<ThreadRoute>().recipients) == wanted
    // Already open: it shows the message asked for, if any, where it is.
    fun focusThere(entry: NavBackStackEntry?) {
        if (route.focus.isNotEmpty()) entry?.savedStateHandle?.set(PENDING_FOCUS, route.focus)
    }
    if (route.draft.isEmpty() && route.attachments.isEmpty()) {
        if (showing(nav.currentBackStackEntry)) return focusThere(nav.currentBackStackEntry)
        // Further down: back to it. A second copy would show an old draft, and save it over the
        // newer one when it closed.
        if (wanted in threadEntries.values) {
            // Never past the list: an entry on its way out can still be in the registry.
            while (!showing(nav.currentBackStackEntry) && nav.previousBackStackEntry != null && nav.popBackStack()) Unit
            if (showing(nav.currentBackStackEntry)) return focusThere(nav.currentBackStackEntry)
        }
    }
    nav.navigate(route)
}

/** Who a conversation is with, however each number is written. */
private fun recipientKey(recipients: String): Set<String> = splitAddresses(recipients).map(::normalizeAddress).toSet()

/** ViewModels inside [content] come from [store]. */
@Composable
private fun ProvideViewModelStore(store: ViewModelStore, content: @Composable () -> Unit) {
    val owner = remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

/**
 * On a wide screen, a conversation opened from a notification or another app goes beside the
 * list rather than over it. False when it can't: it brings a draft or attachments along, or the
 * list isn't in the back stack (onboarding).
 */
private suspend fun openInPane(route: ThreadRoute, nav: NavHostController, pane: ConversationPane, container: AppContainer): Boolean {
    if (route.draft.isNotEmpty() || route.attachments.isNotEmpty()) return false
    if (runCatching { nav.getBackStackEntry<InboxRoute>() }.isFailure) return false
    // Going back to the list (always the bottom of the stack) would close a full-screen
    // conversation or new chat on the way.
    if (runCatching { nav.getBackStackEntry<ThreadRoute>() }.isSuccess || runCatching { nav.getBackStackEntry<NewChatRoute>() }.isSuccess) return false
    val threadId = route.threadId.takeIf { it >= 0 }
        ?: runCatching { container.messages.threadIdFor(splitAddresses(route.recipients)) }.getOrNull()?.takeIf { it >= 0 }
        ?: return false
    nav.popBackStack<InboxRoute>(inclusive = false)
    // A reminder (or anything else naming a message) opens beside the list at that message.
    if (route.focus.isNotEmpty()) pane.open(threadId, route.recipients, ThreadViewModel.SearchRequest(null, route.focus))
    else pane.open(threadId, route.recipients)
    return true
}

/** How long a move between screens takes, and its curve: Material's emphasized decelerate. */
private const val NAV_MILLIS = 300
private val NavEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** How far a screen slides as it comes or goes: a short nudge, not a whole width. */
private fun nudge(width: Int) = width / 10

private fun forwardEnter(): EnterTransition =
    slideInHorizontally(tween(NAV_MILLIS, easing = NavEasing)) { nudge(it) } + fadeIn(tween(NAV_MILLIS, easing = NavEasing))

private fun forwardExit(): ExitTransition =
    slideOutHorizontally(tween(NAV_MILLIS, easing = NavEasing)) { -nudge(it) } + fadeOut(tween(NAV_MILLIS / 2))

private fun backEnter(): EnterTransition =
    slideInHorizontally(tween(NAV_MILLIS, easing = NavEasing)) { -nudge(it) } + fadeIn(tween(NAV_MILLIS, easing = NavEasing))

private fun backExit(): ExitTransition =
    slideOutHorizontally(tween(NAV_MILLIS, easing = NavEasing)) { nudge(it) } + fadeOut(tween(NAV_MILLIS / 2))
