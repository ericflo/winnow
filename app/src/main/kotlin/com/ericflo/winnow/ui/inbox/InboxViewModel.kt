package com.ericflo.winnow.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.data.ConversationSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class InboxTab { INBOX, FILTERED }

data class InboxUiState(
    val loading: Boolean = true,
    val live: Boolean = true,
    val tab: InboxTab = InboxTab.INBOX,
    val query: String = "",
    val conversations: List<ConversationSummary> = emptyList(),
    val filteredCount: Int = 0,
)

class InboxViewModel(container: AppContainer) : ViewModel() {
    private val tab = MutableStateFlow(InboxTab.INBOX)
    private val query = MutableStateFlow("")

    val state: StateFlow<InboxUiState> = combine(
        container.messages.conversations(),
        container.isLive,
        tab,
        query,
    ) { all, live, tab, query ->
        val (filtered, inbox) = all.partition { it.isFiltered }
        val shown = if (tab == InboxTab.FILTERED) filtered else inbox
        InboxUiState(
            loading = false,
            live = live,
            tab = tab,
            query = query,
            conversations = if (query.isBlank()) shown else shown.filter { it.matches(query) },
            filteredCount = filtered.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    fun selectTab(value: InboxTab) {
        tab.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    private fun ConversationSummary.matches(q: String) =
        displayName.contains(q, ignoreCase = true) || snippet.contains(q, ignoreCase = true) || address.contains(q)
}
