package com.ericflo.winnow.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.backup.Trash
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
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
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import com.ericflo.winnow.data.MediaHit
import com.ericflo.winnow.ui.components.allWebLinks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.ericflo.winnow.data.SwipeChoice
import com.ericflo.winnow.data.PreviousVerdict

enum class ListMode { INBOX, FILTERED, ARCHIVED }

/**
 * The inbox's chips. Personal, Updates and Offers come from what Winnow's classifier made of
 * each conversation's newest text: unclassified ones (contacts, texts you started) are personal.
 */
enum class InboxFilter(val label: String) {
    ALL("All"), UNREAD("Unread"), PERSONAL("Personal"), UPDATES("Updates"), OFFERS("Offers");
}

/** Which kind chip a conversation falls under, if any. */
fun ConversationSummary.kind(): InboxFilter? = when {
    verdict == null || verdict.category == Category.PERSONAL -> InboxFilter.PERSONAL
    verdict.category == Category.TRANSACTIONAL -> InboxFilter.UPDATES
    verdict.category == Category.MARKETING -> InboxFilter.OFFERS
    // Rescued by the user ("Not spam"): someone they want to hear from.
    verdict.userAction == Action.ALLOW -> InboxFilter.PERSONAL
    else -> null
}

/** What empty search can browse across every conversation. */
enum class Browse { MEDIA, LINKS }

/** A link from some conversation; opening it opens that conversation, never the link itself. */
data class LinkHit(val url: String, val host: String, val hit: SearchHit)

data class BrowseResults(val loading: Boolean = false, val media: List<MediaHit> = emptyList(), val links: List<LinkHit> = emptyList())

data class InboxUiState(
    val loading: Boolean = true,
    val live: Boolean = true,
    val isDefault: Boolean = false,
    val query: String = "",
    val conversations: List<ConversationSummary> = emptyList(),
    /** Message bodies matching [query], beyond conversation names and snippets. */
    val messageHits: List<SearchHit> = emptyList(),
    /** Conversations in Filtered, so a message found in one can say so. */
    val filteredThreads: Set<Long> = emptySet(),
    val filteredCount: Int = 0,
    val archivedCount: Int = 0,
    /** Which classifier is active, for the menu, e.g. "Jev via OpenRouter". */
    val classifier: String = "",
    /** Reviewing older, never-classified conversations, unless the user dismissed the prompt. */
    val review: ReviewStatus = ReviewStatus.Unknown,
    val filter: InboxFilter = InboxFilter.ALL,
    /** The kind chips worth offering: there's at least one conversation of that kind. */
    val kinds: List<InboxFilter> = emptyList(),
    /** Conversations in this list with unread messages, whatever the filter. */
    val unreadConversations: Int = 0,
)

/** Backs the inbox and the Filtered and Archived lists. */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InboxViewModel(private val container: AppContainer, private val mode: ListMode) : ViewModel() {
    private val repo = container.messages
    private val states = container.conversationStates
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(InboxFilter.ALL)
    private val isDefault = MutableStateFlow(container.isDefaultSmsApp())

    private val classifier = container.settings.settings.map { s ->
        when {
            s.provider == ProviderKind.ON_DEVICE -> s.provider.label
            container.classifiers.provider(s) == null -> "${s.provider.label} (not set up)"
            else -> s.provider.label
        }
    }

    // Shared: the list and the photo/link browser read the same conversations, loaded once.
    private val all = combine(repo.conversations(), states.observeTimed()) { list, s -> list.withState(s) }
        // Not kept once nobody's looking: the browser mustn't start from a stale list.
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1)

    private val hits = query.debounce(250).distinctUntilChanged().mapLatest { q -> if (q.length < 2) emptyList() else repo.search(q) }

    private val review = combine(container.historyReviewer.status, container.settings.settings) { status, s ->
        if (s.reviewPromptDismissed) ReviewStatus.Unknown else status
    }

    val state: StateFlow<InboxUiState> = combine(
        combine(all, hits, ::Pair),
        combine(container.isLive, isDefault, ::Pair),
        combine(query, filter, ::Pair),
        classifier,
        review,
    ) { (all, hits), (live, isDefault), (query, filter), classifier, review ->
        val shown = all.filter { c ->
            when (mode) {
                ListMode.INBOX -> !c.isFiltered && !c.archived
                ListMode.FILTERED -> c.isFiltered
                ListMode.ARCHIVED -> c.archived && !c.isFiltered
            }
        }
        val unread = shown.filter { it.unreadCount > 0 }
        val filtered = when {
            query.isNotBlank() || filter == InboxFilter.ALL -> shown
            filter == InboxFilter.UNREAD -> unread
            else -> shown.filter { it.kind() == filter }
        }
        // Updates and Offers only when there are some; Personal only as their counterpart.
        val present = shown.mapNotNullTo(HashSet()) { it.kind() }
        val kinds = listOf(InboxFilter.UPDATES, InboxFilter.OFFERS).filter { it in present }
            .let { if (it.isEmpty()) it else listOf(InboxFilter.PERSONAL) + it }
        val matching = if (query.isBlank()) filtered else filtered.filter { it.matches(query) }
        InboxUiState(
            loading = false,
            live = live,
            isDefault = isDefault,
            query = query,
            conversations = matching,
            messageHits = hits,
            filteredThreads = all.filter { it.isFiltered }.mapTo(HashSet()) { it.threadId },
            filteredCount = all.count { it.isFiltered },
            archivedCount = all.count { it.archived && !it.isFiltered },
            classifier = classifier,
            // Only offered once Winnow can actually read and file real messages.
            review = if (live && isDefault) review else ReviewStatus.Unknown,
            filter = filter,
            kinds = kinds,
            unreadConversations = unread.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    fun refresh() {
        isDefault.value = container.isDefaultSmsApp()
        if (mode == ListMode.INBOX) container.historyReviewer.refresh()
    }

    fun startReview() = container.historyReviewer.start()

    fun dismissReview() = launch { container.settings.update { it.copy(reviewPromptDismissed = true) } }

    fun setFilter(value: InboxFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /** Texts waiting for their send time, for the menu. */
    val trashCount: StateFlow<Int> = container.trash.items.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val scheduledCount: StateFlow<Int> = container.scheduler.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** What swiping a conversation right and left does, from Settings. */
    val swipes: StateFlow<Pair<SwipeChoice, SwipeChoice>> = container.settings.settings
        .map { it.swipeRight to it.swipeLeft }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SwipeChoice.ARCHIVE to SwipeChoice.ARCHIVE)

    private val _browsing = MutableStateFlow<Browse?>(null)
    val browsing: StateFlow<Browse?> = _browsing.asStateFlow()

    /** Photos and videos, or links, from conversations that aren't filtered (spam stays out of it). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val browseResults: StateFlow<BrowseResults> = _browsing.flatMapLatest { kind ->
        flow {
            if (kind == null) return@flow emit(BrowseResults())
            emit(BrowseResults(loading = true))
            val allowed = all.first().filterNot { it.isFiltered }.mapTo(HashSet()) { it.threadId }
            emit(
                when (kind) {
                    Browse.MEDIA -> BrowseResults(media = repo.recentMedia().filter { it.threadId in allowed })
                    Browse.LINKS -> BrowseResults(
                        links = repo.textsWithLinks().filter { it.threadId in allowed }.flatMap { hit ->
                            allWebLinks(hit.body).map { url -> LinkHit(url, url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url, hit) }
                        },
                    )
                },
            )
        }.flowOn(Dispatchers.Default)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseResults())

    fun browse(kind: Browse?) {
        _browsing.value = kind
    }

    fun markAllRead() = launch { repo.markAllRead() }

    fun setPinned(threadIds: Set<Long>, pinned: Boolean) = launch { states.setPinned(threadIds, pinned) }

    fun setArchived(threadIds: Set<Long>, archived: Boolean) = launch { states.setArchived(threadIds, archived) }

    fun setRead(threadIds: Set<Long>, read: Boolean) = launch {
        threadIds.forEach { if (read) repo.markRead(it) else repo.markUnread(it) }
    }

    /**
     * Into Recently deleted, for 30 days, then gone. [onDone] gets what Undo would put back. In
     * the app scope, so leaving the inbox mid-way doesn't stop it between keeping and deleting.
     */
    fun delete(threadIds: Set<Long>, onDone: (List<Trash.Item>) -> Unit = {}) {
        container.appScope.launch {
            val deleted = container.trash.delete(threadIds)
            deleted.problem?.let(container::toast)
            if (deleted.items.isNotEmpty()) withContext(Dispatchers.Main) { onDone(deleted.items) }
        }
    }

    /** Undo for [delete]: back out of Recently deleted. */
    fun restore(items: List<Trash.Item>) {
        // In the app scope: leaving the inbox mustn't stop it halfway.
        container.appScope.launch {
            if (!container.isDefaultSmsApp()) {
                container.toast("Make Winnow your SMS app to restore conversations")
                return@launch
            }
            // Every one tried, whatever happens to the others.
            val results = items.map { item ->
                try {
                    container.trash.restore(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null.also { container.toast("Couldn't restore it: ${e.message ?: e::class.simpleName}") }
                }
            }
            if (results.any { it?.complete == false }) {
                container.toast("Some messages couldn't be put back, so they're still in Recently deleted")
            }
        }
    }

    /** "Not spam" for a filtered 1:1 conversation: always allow its sender. */
    fun allow(conversation: ConversationSummary, onDone: (PreviousVerdict) -> Unit = {}) = launch {
        if (!conversation.isGroup) onDone(repo.overrideVerdict(conversation.threadId, conversation.address, Action.ALLOW))
    }

    /** "Not spam" for several filtered 1:1 conversations at once; [onDone] gets what Undo needs. */
    fun allowAll(conversations: List<ConversationSummary>, onDone: (List<PreviousVerdict>) -> Unit = {}) = launch {
        onDone(conversations.filterNot { it.isGroup }.map { repo.overrideVerdict(it.threadId, it.address, Action.ALLOW) })
    }

    fun undoAll(previous: List<PreviousVerdict>) = launch { previous.forEach { repo.restoreVerdict(it) } }

    /** Takes back a correction, leaving the conversation as it was before. */
    fun undo(previous: PreviousVerdict) = launch { repo.restoreVerdict(previous) }

    /** Always filter the sender of a 1:1 conversation. */
    fun block(conversation: ConversationSummary) = launch {
        if (!conversation.isGroup) repo.overrideVerdict(conversation.threadId, conversation.address, Action.FILTER)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun ConversationSummary.matches(q: String): Boolean {
        if (displayName.contains(q, ignoreCase = true) || snippet.contains(q, ignoreCase = true) || recipients.any { it.contains(q) }) return true
        // A number typed any way ("415-555", "(415) 555", "+1 415") finds it however it's written or shown.
        val digits = q.filter(Char::isDigit)
        val numeric = digits.length >= 3 && q.all { it.isDigit() || it in " +-().".toSet() }
        return numeric && recipients.any { r -> r.filter(Char::isDigit).let { it.contains(digits) || ("1$it").contains(digits) } }
    }
}
