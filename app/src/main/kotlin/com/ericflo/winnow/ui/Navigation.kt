package com.ericflo.winnow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ericflo.winnow.AppContainer
import kotlinx.coroutines.flow.getAndUpdate
import com.ericflo.winnow.ui.activity.ActivityScreen
import com.ericflo.winnow.ui.activity.ActivityViewModel
import com.ericflo.winnow.data.joinAddresses
import com.ericflo.winnow.data.splitAddresses
import com.ericflo.winnow.ui.details.ConversationDetailsScreen
import com.ericflo.winnow.ui.details.ConversationDetailsViewModel
import com.ericflo.winnow.ui.inbox.ConversationListScreen
import com.ericflo.winnow.ui.metrics.MetricsScreen
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

/** Where US carriers collect forwarded spam ("SPAM" on a keypad). */
private const val CARRIER_SPAM_SHORT_CODE = "7726"

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
    /** Attach the photos waiting in [AppContainer.pendingShare]. */
    val shared: Boolean = false,
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

/** [draft] carries a forwarded message into the conversation the user picks. */
@Serializable
data class NewChatRoute(val draft: String = "", val shared: Boolean = false)

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
    LaunchedEffect(pending) {
        pending?.let {
            nav.navigate(it) { launchSingleTop = true }
            onRouteConsumed()
        }
    }
    val openThread = { threadId: Long, recipients: List<String> -> nav.navigate(ThreadRoute(threadId, joinAddresses(recipients))) }

    NavHost(navController = nav, startDestination = if (onboarded) InboxRoute else OnboardingRoute) {
        composable<OnboardingRoute> {
            OnboardingScreen(
                isDefault = container::isDefaultSmsApp,
                onMakeDefault = onMakeDefault,
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
            )
        }
        composable<InboxRoute> {
            InboxScreen(
                viewModel = viewModel { InboxViewModel(container, ListMode.INBOX) },
                onOpenThread = openThread,
                onNewChat = { nav.navigate(NewChatRoute()) },
                onOpenFiltered = { nav.navigate(FilteredRoute) },
                onOpenArchived = { nav.navigate(ArchivedRoute) },
                onOpenActivity = { nav.navigate(ActivityRoute) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onMakeDefault = onMakeDefault,
            )
        }
        composable<FilteredRoute> {
            ConversationListScreen(
                viewModel = viewModel { InboxViewModel(container, ListMode.FILTERED) },
                mode = ListMode.FILTERED,
                onBack = { nav.popBackStack() },
                onOpenThread = openThread,
                onOpenMetrics = { nav.navigate(MetricsRoute) },
            )
        }
        composable<ArchivedRoute> {
            ConversationListScreen(
                viewModel = viewModel { InboxViewModel(container, ListMode.ARCHIVED) },
                mode = ListMode.ARCHIVED,
                onBack = { nav.popBackStack() },
                onOpenThread = openThread,
            )
        }
        composable<NewChatRoute> { entry ->
            val route = entry.toRoute<NewChatRoute>()
            NewChatScreen(
                viewModel = viewModel { NewChatViewModel(container) },
                onBack = { nav.popBackStack() },
                onStart = { recipients ->
                    nav.navigate(ThreadRoute(-1, joinAddresses(recipients), route.draft, route.shared)) {
                        popUpTo<NewChatRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<ThreadRoute> { entry ->
            val route = entry.toRoute<ThreadRoute>()
            ThreadScreen(
                // Keyed by conversation: a notification or SENDTO intent for another thread reuses this
                // entry (launchSingleTop), and must not get the previous thread's ViewModel back.
                viewModel = viewModel(key = "thread:${route.threadId}:${route.recipients}") {
                    ThreadViewModel(container, route.threadId, splitAddresses(route.recipients)).also { vm ->
                        if (route.draft.isNotEmpty()) vm.setDraft(route.draft)
                        if (route.shared) container.pendingShare.getAndUpdate { emptyList() }.forEach(vm::addAttachment)
                    }
                },
                onBack = { nav.popBackStack() },
                onForward = { text -> nav.navigate(NewChatRoute(draft = text)) },
                onReportSpam = { text -> nav.navigate(ThreadRoute(-1, CARRIER_SPAM_SHORT_CODE, text)) },
                onOpenDetails = { threadId -> nav.navigate(DetailsRoute(threadId, route.recipients)) },
            )
        }
        composable<DetailsRoute> { entry ->
            val route = entry.toRoute<DetailsRoute>()
            ConversationDetailsScreen(
                viewModel = viewModel { ConversationDetailsViewModel(container, route.threadId, splitAddresses(route.recipients)) },
                onBack = { nav.popBackStack() },
                onDeleted = { nav.popBackStack<InboxRoute>(inclusive = false) },
            )
        }
        composable<ActivityRoute> {
            ActivityScreen(
                viewModel = viewModel { ActivityViewModel(container) },
                onBack = { nav.popBackStack() },
                onOpenMetrics = { nav.navigate(MetricsRoute) },
            )
        }
        composable<MetricsRoute> {
            MetricsScreen(viewModel = viewModel { MetricsViewModel(container) }, onBack = { nav.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(
                viewModel = viewModel { SettingsViewModel(container) },
                onBack = { nav.popBackStack() },
                onMakeDefault = onMakeDefault,
                onOpenSenderRules = { nav.navigate(SenderRulesRoute) },
            )
        }
        composable<SenderRulesRoute> {
            SenderRulesScreen(container = container, onBack = { nav.popBackStack() })
        }
    }
}
