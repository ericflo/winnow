package com.ericflo.winnow.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classify.ReviewStatus
import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.data.ProviderKind
import com.ericflo.winnow.data.SearchHit
import com.ericflo.winnow.data.withState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.ericflo.winnow.data.SwipeChoice
import com.ericflo.winnow.data.PreviousVerdict

enum class ListMode { INBOX, FILTERED, ARCHIVED }

data class InboxUiState(
    val loading: Boolean = true,
    val live: Boolean = true,
    val isDefault: Boolean = false,
    val query: String = "",
    val conversations: List<ConversationSummary> = emptyList(),
    /** Message bodies matching [query], beyond conversation names and snippets. */
    val messageHits: List<SearchHit> = emptyList(),
    val filteredCount: Int = 0,
    val archivedCount: Int = 0,
    /** Which classifier is active, for the menu, e.g. "Jev via OpenRouter". */
    val classifier: String = "",
    /** Reviewing older, never-classified conversations, unless the user dismissed the prompt. */
    val review: ReviewStatus = ReviewStatus.Unknown,
    /** Only conversations with unread messages are shown. */
    val unreadOnly: Boolean = false,
    /** Conversations in this list with unread messages, whatever the filter. */
    val unreadConversations: Int = 0,
)

/** Backs the inbox and the Filtered and Archived lists. */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InboxViewModel(private val container: AppContainer, private val mode: ListMode) : ViewModel() {
    private val repo = container.messages
    private val states = container.conversationStates
    private val query = MutableStateFlow("")
    private val unreadOnly = MutableStateFlow(false)
    private val isDefault = MutableStateFlow(container.isDefaultSmsApp())

    private val classifier = container.settings.settings.map { s ->
        when {
            s.provider == ProviderKind.ON_DEVICE -> s.provider.label
            container.classifiers.provider(s) == null -> "${s.provider.label} (not set up)"
            else -> s.provider.label
        }
    }

    private val all = combine(repo.conversations(), states.observe()) { list, s -> list.withState(s) }

    private val hits = query.debounce(250).distinctUntilChanged().mapLatest { q -> if (q.length < 2) emptyList() else repo.search(q) }

    private val review = combine(container.historyReviewer.status, container.settings.settings) { status, s ->
        if (s.reviewPromptDismissed) ReviewStatus.Unknown else status
    }

    val state: StateFlow<InboxUiState> = combine(
        combine(all, hits, ::Pair),
        combine(container.isLive, isDefault, ::Pair),
        combine(query, unreadOnly, ::Pair),
        classifier,
        review,
    ) { (all, hits), (live, isDefault), (query, unreadOnly), classifier, review ->
        val shown = all.filter { c ->
            when (mode) {
                ListMode.INBOX -> !c.isFiltered && !c.archived
                ListMode.FILTERED -> c.isFiltered
                ListMode.ARCHIVED -> c.archived && !c.isFiltered
            }
        }
        val unread = shown.filter { it.unreadCount > 0 }
        val filtered = if (unreadOnly && query.isBlank()) unread else shown
        val matching = if (query.isBlank()) filtered else filtered.filter { it.matches(query) }
        InboxUiState(
            loading = false,
            live = live,
            isDefault = isDefault,
            query = query,
            conversations = matching,
            messageHits = hits,
            filteredCount = all.count { it.isFiltered },
            archivedCount = all.count { it.archived && !it.isFiltered },
            classifier = classifier,
            // Only offered once Winnow can actually read and file real messages.
            review = if (live && isDefault) review else ReviewStatus.Unknown,
            unreadOnly = unreadOnly,
            unreadConversations = unread.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    fun refresh() {
        isDefault.value = container.isDefaultSmsApp()
        if (mode == ListMode.INBOX) container.historyReviewer.refresh()
    }

    fun startReview() = container.historyReviewer.start()

    fun dismissReview() = launch { container.settings.update { it.copy(reviewPromptDismissed = true) } }

    fun setUnreadOnly(value: Boolean) {
        unreadOnly.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /** What swiping a conversation right and left does, from Settings. */
    val swipes: StateFlow<Pair<SwipeChoice, SwipeChoice>> = container.settings.settings
        .map { it.swipeRight to it.swipeLeft }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SwipeChoice.ARCHIVE to SwipeChoice.ARCHIVE)

    fun markAllRead() = launch { repo.markAllRead() }

    fun setPinned(threadIds: Set<Long>, pinned: Boolean) = launch { states.setPinned(threadIds, pinned) }

    fun setArchived(threadIds: Set<Long>, archived: Boolean) = launch { states.setArchived(threadIds, archived) }

    fun setRead(threadIds: Set<Long>, read: Boolean) = launch {
        threadIds.forEach { if (read) repo.markRead(it) else repo.markUnread(it) }
    }

    fun delete(threadIds: Set<Long>) = launch {
        repo.deleteThreads(threadIds)
        states.forget(threadIds)
        container.notifier.forget(threadIds)
    }

    /** "Not spam" for a filtered 1:1 conversation: always allow its sender. */
    fun allow(conversation: ConversationSummary, onDone: (PreviousVerdict) -> Unit = {}) = launch {
        if (!conversation.isGroup) onDone(repo.overrideVerdict(conversation.threadId, conversation.address, Action.ALLOW))
    }

    /** Takes back a correction, leaving the conversation as it was before. */
    fun undo(previous: PreviousVerdict) = launch { repo.restoreVerdict(previous) }

    /** Always filter the sender of a 1:1 conversation. */
    fun block(conversation: ConversationSummary) = launch {
        if (!conversation.isGroup) repo.overrideVerdict(conversation.threadId, conversation.address, Action.FILTER)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun ConversationSummary.matches(q: String) =
        displayName.contains(q, ignoreCase = true) || snippet.contains(q, ignoreCase = true) || recipients.any { it.contains(q) }
}
