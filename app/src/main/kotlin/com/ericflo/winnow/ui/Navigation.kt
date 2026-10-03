package com.ericflo.winnow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.ui.inbox.InboxScreen
import com.ericflo.winnow.ui.inbox.InboxViewModel
import com.ericflo.winnow.ui.settings.SettingsScreen
import com.ericflo.winnow.ui.settings.SettingsViewModel
import com.ericflo.winnow.ui.thread.ThreadScreen
import com.ericflo.winnow.ui.thread.ThreadViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data object InboxRoute

/** A negative [threadId] means "look up or create the thread for [address]". */
@Serializable
data class ThreadRoute(val threadId: Long, val address: String, val draft: String = "")

@Serializable
data object SettingsRoute

@Composable
fun WinnowNavHost(
    container: AppContainer,
    pendingRoute: StateFlow<ThreadRoute?>,
    onRouteConsumed: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    val nav = rememberNavController()
    val pending by pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        pending?.let {
            nav.navigate(it) { launchSingleTop = true }
            onRouteConsumed()
        }
    }

    NavHost(navController = nav, startDestination = InboxRoute) {
        composable<InboxRoute> {
            InboxScreen(
                viewModel = viewModel { InboxViewModel(container) },
                onOpenConversation = { nav.navigate(ThreadRoute(it.threadId, it.address)) },
                onStartChat = { nav.navigate(ThreadRoute(-1, it)) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onMakeDefault = onMakeDefault,
            )
        }
        composable<ThreadRoute> { entry ->
            val route = entry.toRoute<ThreadRoute>()
            ThreadScreen(
                viewModel = viewModel { ThreadViewModel(container, route.threadId, route.address) },
                address = route.address,
                initialDraft = route.draft,
                onBack = { nav.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                viewModel = viewModel { SettingsViewModel(container) },
                onBack = { nav.popBackStack() },
                onMakeDefault = onMakeDefault,
            )
        }
    }
}
