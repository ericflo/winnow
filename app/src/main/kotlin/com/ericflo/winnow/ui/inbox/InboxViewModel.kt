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
import com.ericflo.winnow.data.Nudge
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import com.ericflo.winnow.data.MediaHit
import com.ericflo.winnow.ui.components.allWebLinks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import com.ericflo.winnow.data.StoreCounts
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
    ALL("All"), UNREAD("Unread"), PERSONAL("Personal"), REMINDERS("Reminders"), UPDATES("Updates"), OFFERS("Offers");
}

/** Which kind chip a conversation falls under, if any. */
fun ConversationSummary.kind(): InboxFilter? = when {
    verdict == null || verdict.category == Category.PERSONAL -> InboxFilter.PERSONAL
    verdict.category == Category.REMINDER -> InboxFilter.REMINDERS
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
    /** Reply reminders, by conversation: these sort to the top, after pinned ones. */
    val nudges: Map<Long, Nudge.Kind> = emptyMap(),
    /** Messages the user has labeled (see Labeler). */
    val labeled: Int = 0,
)

/** Backs the inbox and the Filtered and Archived lists. */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InboxViewModel(private val container: AppContainer, private val mode: ListMode) : ViewModel() {
    /** Ctrl+F and the like from a keyboard (see MainActivity.onKeyShortcut). */
    val keyShortcuts: kotlinx.coroutines.flow.SharedFlow<com.ericflo.winnow.KeyShortcut> get() = container.keyShortcuts
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

    init {
        // The app icon's badge counts notifications: one for a conversation read since, some other
        // way than opening it, would keep the badge with nothing unread to show for it.
        if (mode == ListMode.INBOX) {
            viewModelScope.launch {
                repo.conversations().collect { list -> container.notifier.keepOnlyUnread(list.filter { it.unread }.mapTo(HashSet()) { it.threadId }) }
            }
        }
    }

    /** The inbox itself, not Filtered or Archived. */
    val isInbox: Boolean get() = mode == ListMode.INBOX

    private var watchdog: kotlinx.coroutines.Job? = null

    /** What arrived while another app was the SMS app (see RoleWatch), until dismissed. */
    val away: StateFlow<com.ericflo.winnow.data.RoleWatch.Away?> = container.roleWatch.away

    fun dismissAway() = container.roleWatch.dismiss()

    private val rcsPrefs by lazy { container.appContext.getSharedPreferences("rcs_card", android.content.Context.MODE_PRIVATE) }
    // Read off the main thread (preferences come from disk); the card waits for it.
    private val rcsDismissedAt = MutableStateFlow(Int.MAX_VALUE).also { flow ->
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { flow.value = runCatching { rcsPrefs.getInt("dismissed_count", 0) }.getOrDefault(0) }
    }

    /**
     * Conversations that were RCS chats, wherever they're filed, while there are more than when the
     * user last said they'd seen the card: new messages in them may not reach Winnow.
     */
    val rcs: StateFlow<List<ConversationSummary>> =
        if (mode != ListMode.INBOX) MutableStateFlow(emptyList())
        else combine(all, rcsDismissedAt) { list, dismissed -> list.filter { it.rcs }.takeIf { it.size > dismissed }.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun dismissRcs(count: Int) {
        rcsDismissedAt.value = count
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { rcsPrefs.edit().putInt("dismissed_count", count).apply() }
    }

    /** The listing under way, for the empty inbox to say how long it's been. */
    fun listingProgress() = repo.listingProgress()

    /** Said once per run of the app: the problem report already has it. */
    private var listingReported = false

    /**
     * Why the inbox is empty, when it is (see [EmptyInbox]): worked out from the phone's own
     * message store, Recently deleted, and where the conversations Winnow did list are filed.
     * Null while the inbox has conversations to show, and in the other lists.
     */
    val emptyInbox: StateFlow<EmptyInbox?> =
        if (mode != ListMode.INBOX) MutableStateFlow(null)
        else combine(all, container.isLive, isDefault, container.trash.items) { all, live, default, trashed -> Triple(all, live to default, trashed.size) }
            .mapLatest { (all, access, trashed) ->
                val (live, default) = access
                if (all.any { !it.isFiltered && !it.archived }) return@mapLatest null
                val health = repo.listHealth()
                // Filed elsewhere is known from the list itself; only an empty list needs the store counted.
                val counts = if (live && all.isEmpty() && health != null) repo.storeCounts() else if (live) StoreCounts(0, 0, 0) else null
                EmptyInbox.of(
                    live, default, counts, health,
                    listed = all.size, filtered = all.count { it.isFiltered }, archived = all.count { it.archived && !it.isFiltered }, trashed = trashed,
                )
            }
            .onEach { e ->
                // A listing that finished and found texts on the phone but none to list: in the problem report.
                if (e is EmptyInbox.NotListed && !listingReported) {
                    listingReported = true
                    container.problems.note(com.ericflo.winnow.diagnostics.ProblemLog.Kind.LISTING, EmptyInbox.report(e.counts, e.health))
                }
                // One that hasn't finished: reported only if it's still going after a long while, with where it is.
                watchdog?.cancel()
                if (e == EmptyInbox.Listing) watchdog = viewModelScope.launch {
                    kotlinx.coroutines.delay(SLOW_LISTING_MILLIS)
                    if (repo.listHealth() == null && !listingReported && container.isLive.value) {
                        listingReported = true
                        val progress = repo.listingProgress()
                        val elapsed = progress?.let { System.currentTimeMillis() - it.startedAt } ?: SLOW_LISTING_MILLIS
                        container.problems.note(
                            com.ericflo.winnow.diagnostics.ProblemLog.Kind.LISTING,
                            EmptyInbox.slowReport(runCatching { repo.storeCounts() }.getOrNull(), progress, elapsed),
                        )
                    }
                }
            }
            .flowOn(kotlinx.coroutines.Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every archived conversation back in the inbox; [onDone] gets them, for an undo. */
    fun unarchiveAll(onDone: (Set<Long>) -> Unit) = launch {
        val ids = all.first().filter { it.archived && !it.isFiltered }.mapTo(HashSet()) { it.threadId }
        states.setArchived(ids, false)
        onDone(ids)
    }

    /** Lists the conversations again (see MessageRepository.relist). */
    fun relist() = repo.relist()

    private val hits = query.debounce(250).distinctUntilChanged().mapLatest { q -> if (q.length < 2) emptyList() else repo.search(q) }

    private val review = combine(container.historyReviewer.status, container.settings.settings) { status, s ->
        if (s.reviewPromptDismissed) ReviewStatus.Unknown else status
    }

    // Contacts changing (a birthday added) reads birthdays again.
    private val contactsChanged = repo.contactChanges().onStart { emit(Unit) }.onEach { container.birthdays.clear() }
    private val nudgeInputs = combine(container.settings.settings.map { it.nudges }.distinctUntilChanged(), container.dismissedNudges.keys, contactsChanged) { on, dismissed, _ -> on to dismissed }

    val state: StateFlow<InboxUiState> = combine(
        combine(all, hits, ::Pair),
        combine(container.isLive, isDefault, ::Pair),
        combine(query, filter, ::Pair),
        combine(classifier, review, container.verdictDao.observeLabelCount(), ::Triple),
        nudgeInputs,
    ) { (all, hits), (live, isDefault), (query, filter), (classifier, review, labeled), (nudgesOn, dismissed) ->
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
        // Reminders, Updates and Offers only when there are some; Personal only as their counterpart.
        val present = shown.mapNotNullTo(HashSet()) { it.kind() }
        val kinds = listOf(InboxFilter.REMINDERS, InboxFilter.UPDATES, InboxFilter.OFFERS).filter { it in present }
            .let { if (it.isEmpty()) it else listOf(InboxFilter.PERSONAL) + it }
        val matching = if (query.isBlank()) filtered else filtered.filter { it.matches(query) }
        // Only in the plain inbox: a search or a chip asked for something else.
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now()
        val startOfToday = today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val nudges = if (!nudgesOn || mode != ListMode.INBOX || query.isNotBlank() || filter !in setOf(InboxFilter.ALL, InboxFilter.PERSONAL)) {
            emptyMap()
        } else {
            matching.mapNotNull { c ->
                val contact = !c.isGroup && repo.contactName(c.address) != null
                val kind = Nudge.of(c, now, contact, birthday = contact && container.birthdays.isBirthday(c.address, today), startOfToday = startOfToday)
                    ?: return@mapNotNull null
                if (Nudge.key(c, kind, startOfToday) in dismissed) null else c.threadId to kind
            }.toMap()
        }
        // Pinned first, then reminders, then the rest, each by recency as before.
        val ordered = if (nudges.isEmpty()) matching else matching.sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.threadId in nudges })
        InboxUiState(
            loading = false,
            live = live,
            isDefault = isDefault,
            query = query,
            conversations = ordered,
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
            nudges = nudges,
            labeled = labeled,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    /** "Not now" on a reply reminder: it doesn't come back for that message. */
    fun dismissNudge(conversation: ConversationSummary) {
        val kind = state.value.nudges[conversation.threadId] ?: return
        val startOfToday = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        container.dismissedNudges.dismiss(Nudge.key(conversation, kind, startOfToday))
    }

    private val _alertsOff = MutableStateFlow(false)
    /** New texts can't alert the user: Winnow's notifications, or its message channel, are turned off. */
    val alertsOff: StateFlow<Boolean> = _alertsOff.asStateFlow()

    /** Android is holding Winnow back in the background (see AppContainer.restricted). */
    val restricted: StateFlow<Boolean> = container.restricted

    private val _contactsHidden = MutableStateFlow(false)
    /**
     * Winnow can't read contacts (not allowed, or no longer): a contact's text is sorted like a
     * stranger's, and no text goes to a classifier service (see ClassifierFactory.CONTACTS_HIDDEN).
     */
    val contactsHidden: StateFlow<Boolean> = _contactsHidden.asStateFlow()

    /** Winnow's page in Android's settings, where contacts can be allowed again. */
    fun appSettingsIntent(): android.content.Intent =
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", container.appContext.packageName, null))

    /** Crashes or freezes since the user last looked: the inbox offers the report. */
    val newProblems: StateFlow<Int> = container.problems.unseen
    /** The earlier version they all happened in, when they did (see ProblemLog.unseenEarlier). */
    val newProblemsEarlier: StateFlow<String?> = container.problems.unseenEarlier
    /** When the newest of them happened. */
    val newProblemsAt: StateFlow<Long?> = container.problems.unseenAt

    fun shareProblems(): android.content.Intent = container.problems.shareIntent().also { container.problems.markSeen() }

    fun dismissProblems() {
        container.appScope.launch(Dispatchers.IO) { container.problems.markSeen() }
    }

    fun alertSettingsIntent(): android.content.Intent = container.notifier.alertSettingsIntent()

    fun refresh() {
        // Coming back to the inbox: notifications for conversations read meanwhile go (see init).
        if (mode == ListMode.INBOX) launch {
            container.notifier.keepOnlyUnread(repo.conversations().first().filter { it.unread }.mapTo(HashSet()) { it.threadId })
        }
        isDefault.value = container.isDefaultSmsApp()
        if (mode == ListMode.INBOX) container.historyReviewer.refresh()
        _alertsOff.value = container.notifier.alertsOff()
        _contactsHidden.value = !container.contacts.canRead()
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
        .stateIn(viewModelScope, SharingStarted.Eagerly, SwipeChoice.ARCHIVE to SwipeChoice.LABEL)

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

    fun markAllRead() = launch {
        repo.markAllRead()
        container.notifier.keepOnlyUnread(emptySet())
    }

    fun setPinned(threadIds: Set<Long>, pinned: Boolean) = launch { states.setPinned(threadIds, pinned) }

    fun setArchived(threadIds: Set<Long>, archived: Boolean) = launch { states.setArchived(threadIds, archived) }

    fun setRead(threadIds: Set<Long>, read: Boolean) = launch {
        threadIds.forEach { if (read) repo.markRead(it) else repo.markUnread(it) }
        if (read) threadIds.forEach(container.notifier::cancelMessages)
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

    private val actionPolicy = container.settings.settings.map { it.actionPolicy }
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.ericflo.winnow.classifier.message.ActionPolicy())

    /** Where each category files a message, by the user's settings (for the label sheet). */
    fun actionFor(category: Category): com.ericflo.winnow.classifier.message.Action = actionPolicy.value.forCategory(category)

    /**
     * Labels the [threadIds] conversations as [category] (see Labeler), in the app scope so
     * leaving midway doesn't stop it. [onDone] gets what to say and the undo, on the main thread.
     */
    fun label(threadIds: Set<Long>, category: Category, onDone: (String, com.ericflo.winnow.classify.Labeler.Undo) -> Unit) {
        val conversations = state.value.conversations.filter { it.threadId in threadIds }.map { it.threadId to it.recipients }
        // Many take a moment, and the selection is already gone: say it's under way.
        if (conversations.size > BULK_NOTICE) container.toast("Labeling ${conversations.size} conversations…")
        container.appScope.launch {
            val result = container.labeler.labelConversations(conversations, category)
            val undo = result.undo
            if (undo == null) {
                container.toast("Nothing received there to label yet")
                return@launch
            }
            withContext(Dispatchers.Main) { onDone(com.ericflo.winnow.classify.Labeler.summary(category, result), undo) }
        }
    }

    fun undoLabel(undo: com.ericflo.winnow.classify.Labeler.Undo) {
        container.appScope.launch { container.labeler.undo(undo) }
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
        val numeric = digits.length >= 3 && q.all { it.isDigit() || it in NUMBER_PUNCTUATION }
        return numeric && recipients.any { r ->
            val number = r.filter(Char::isDigit)
            // "+1 415…" typed against a number stored as 4155550177.
            number.contains(digits) || (digits.startsWith('1') && number.length == 10 && "1$number".startsWith(digits))
        }
    }

    private companion object {
        /** How long a first listing may take before it's reported as a problem, with where it's stuck. */
        const val SLOW_LISTING_MILLIS = 45_000L
        val NUMBER_PUNCTUATION = setOf(' ', '+', '-', '(', ')', '.')

        /** More conversations than this labeled at once, and a note says it's under way. */
        const val BULK_NOTICE = 5
    }
}
