package com.ericflo.winnow.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.StoredVerdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ThreadUiState(
    val title: String,
    val subtitle: String?,
    val messages: List<ChatMessage> = emptyList(),
    /** Verdict on the newest incoming message. */
    val verdict: StoredVerdict? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModel(
    private val container: AppContainer,
    initialThreadId: Long,
    private val address: String,
) : ViewModel() {
    private val repo = container.messages
    private val threadId = MutableStateFlow(initialThreadId)
    private val title = repo.displayName(address)
    private val subtitle = ContactLookup.formatAddress(address).takeIf { it != title }

    init {
        viewModelScope.launch {
            if (threadId.value < 0) threadId.value = repo.threadIdFor(address)
            repo.markRead(threadId.value)
            container.notifier.cancel(threadId.value)
        }
    }

    val state: StateFlow<ThreadUiState> = threadId
        .flatMapLatest { id -> if (id < 0) flowOf(emptyList()) else repo.messages(id) }
        .map { messages -> ThreadUiState(title, subtitle, messages, messages.lastOrNull { !it.outgoing }?.verdict) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState(title, subtitle))

    fun send(body: String) {
        val text = body.trim()
        if (text.isEmpty()) return
        viewModelScope.launch { repo.send(address, text) }
    }

    fun allow() = override(Action.ALLOW)

    fun filter() = override(Action.FILTER)

    private fun override(action: Action) {
        viewModelScope.launch { repo.overrideVerdict(threadId.value, address, action) }
    }
}
