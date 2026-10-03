package com.ericflo.winnow.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.data.ProviderKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InboxUiState(
    val loading: Boolean = true,
    val live: Boolean = true,
    val isDefault: Boolean = false,
    val query: String = "",
    val conversations: List<ConversationSummary> = emptyList(),
    val filteredCount: Int = 0,
    /** Which classifier is active, for the menu, e.g. "Jev via OpenRouter". */
    val classifier: String = "",
)

/** Backs both the inbox and the Filtered list ([showFiltered]). */
class InboxViewModel(private val container: AppContainer, private val showFiltered: Boolean) : ViewModel() {
    private val query = MutableStateFlow("")
    private val isDefault = MutableStateFlow(container.isDefaultSmsApp())

    private val classifier = container.settings.settings.map { s ->
        when {
            s.provider == ProviderKind.ON_DEVICE -> s.provider.label
            container.classifiers.provider(s) == null -> "${s.provider.label} (not set up)"
            else -> s.provider.label
        }
    }

    val state: StateFlow<InboxUiState> = combine(
        container.messages.conversations(),
        container.isLive,
        isDefault,
        query,
        classifier,
    ) { all, live, isDefault, query, classifier ->
        val (filtered, inbox) = all.partition { it.isFiltered }
        val shown = if (showFiltered) filtered else inbox
        InboxUiState(
            loading = false,
            live = live,
            isDefault = isDefault,
            query = query,
            conversations = if (query.isBlank()) shown else shown.filter { it.matches(query) },
            filteredCount = filtered.size,
            classifier = classifier,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    fun refresh() {
        isDefault.value = container.isDefaultSmsApp()
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun markAllRead() {
        viewModelScope.launch { container.messages.markAllRead() }
    }

    private fun ConversationSummary.matches(q: String) =
        displayName.contains(q, ignoreCase = true) || snippet.contains(q, ignoreCase = true) || address.contains(q)
}
