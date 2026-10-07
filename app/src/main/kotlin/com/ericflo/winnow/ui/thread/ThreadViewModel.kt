package com.ericflo.winnow.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.OutgoingAttachment
import com.ericflo.winnow.data.SimCard
import com.ericflo.winnow.backup.Trash
import com.ericflo.winnow.data.PhotoCrop
import com.ericflo.winnow.sms.SendReadiness
import com.ericflo.winnow.sms.PendingSends
import kotlin.random.Random
import com.ericflo.winnow.sms.StoredAsFailed
import com.ericflo.winnow.data.Tapback
import com.ericflo.winnow.data.StoredVerdict
import com.ericflo.winnow.data.isRcsAddress
import com.ericflo.winnow.data.db.ScheduledMessageEntity
import com.ericflo.winnow.data.db.StarredEntity
import com.ericflo.winnow.data.displayNameFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import com.ericflo.winnow.data.TextScale
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.ericflo.winnow.data.Attachment
import com.ericflo.winnow.data.LinkPreview
import com.ericflo.winnow.data.Member
import com.ericflo.winnow.data.GROUP_FACE_CANDIDATES
import com.ericflo.winnow.data.groupFaces
import com.ericflo.winnow.data.SmartAction
import com.ericflo.winnow.data.SmartLink
import com.ericflo.winnow.data.normalizeAddress
import kotlinx.coroutines.CompletableDeferred
import com.ericflo.winnow.data.CurrentLocation
import com.ericflo.winnow.data.ReturnedMessages
import com.ericflo.winnow.sms.MmsSender
import com.ericflo.winnow.data.WinnowSettings
import kotlinx.coroutines.CoroutineStart
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import com.ericflo.winnow.data.SmartLinks
import com.ericflo.winnow.data.subjectAndText
import kotlinx.coroutines.flow.transformLatest

data class ThreadUiState(
    val title: String,
    val subtitle: String?,
    val recipients: List<String>,
    val messages: List<ChatMessage> = emptyList(),
    /** Verdict on the newest incoming message. */
    val verdict: StoredVerdict? = null,
    /** Display names of group senders. */
    val senderNames: Map<String, String> = emptyMap(),
    /** Contact photos by address, for the header and group sender avatars. */
    val photos: Map<String, String> = emptyMap(),
    val muted: Boolean = false,
    /** When a timed mute ends; null for none or until turned off. */
    val mutedUntil: Long? = null,
    val archived: Boolean = false,
    /** The other person's number, in a one-to-one conversation with someone not in contacts. */
    val addableContact: String? = null,
    /** For a group, two of its people, for its avatar. */
    val members: List<Member> = emptyList(),
) {
    val isGroup: Boolean get() = recipients.size > 1

    /**
     * A chat Google Messages had over RCS: its people (or the chat itself) are RCS ids in the store.
     * New messages in it reach the phone only through Google Messages (see RcsSheet).
     */
    val rcs: Boolean by lazy { recipients.any(::isRcsAddress) || messages.any { m -> m.sender?.let(::isRcsAddress) == true } }

    /** Some of its people are RCS ids, which no text can be sent to (see RcsCantSend). */
    val cantSend: Boolean get() = recipients.any(::isRcsAddress)

    /** Its people Winnow can text: the ones with real numbers, for a new group with them. */
    val textable: List<String> get() = recipients.filterNot(::isRcsAddress)

    /**
     * Several people write here (a group, or an RCS chat whose people the store didn't list): bubbles
     * say who. One person's number written two ways ("+14155550100", "4155550100", as carriers do)
     * is one person. Worked out once per state: every bubble asks, and a conversation can hold
     * tens of thousands of messages.
     */
    val showsSenders: Boolean by lazy {
        isGroup || messages.asSequence().mapNotNull { it.sender }.distinct().map(::normalizeAddress).distinct().take(2).count() > 1
    }

    /** Who sent spam (scams and phishing among it) here that the user hasn't cleared, by normalized number. */
    private val fraudSenders: Set<String> by lazy {
        messages.filter { !it.outgoing && it.verdict?.isFraud == true }
            .mapNotNullTo(HashSet()) { (it.sender ?: recipients.singleOrNull())?.let(::normalizeAddress) }
    }

    /**
     * [m]'s links can't be tapped: it's fraud, or its sender sent fraud here. An older text from
     * them, never classified (it came before Winnow), can carry the same link. Not once the user
     * has said this one isn't spam.
     */
    fun linksOff(m: ChatMessage): Boolean {
        if (m.verdict?.isFraud == true) return true
        if (m.outgoing || m.verdict?.userAction == Action.ALLOW) return false
        val sender = (m.sender ?: recipients.singleOrNull())?.let(::normalizeAddress) ?: return false
        return sender in fraudSenders
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ThreadViewModel(
    private val container: AppContainer,
    initialThreadId: Long,
    private val recipients: List<String>,
    /**
     * Shown in a chat bubble. A bubble lives only as long as its notification, so reading the
     * conversation there must not cancel it.
     */
    private val inBubble: Boolean = false,
) : ViewModel() {
    /** Ctrl+F and the like from a keyboard (see MainActivity.onKeyShortcut). */
    val keyShortcuts: kotlinx.coroutines.flow.SharedFlow<com.ericflo.winnow.KeyShortcut> get() = container.keyShortcuts

    private val repo = container.messages
    private val states = container.conversationStates
    private val threadId = MutableStateFlow(initialThreadId)
    // What shows before the state's first emission, so made on the main thread: from names already
    // known only (see ContactLookup.nameIfKnown); the state reads the rest off it, a moment later.
    private val title = displayNameFor(recipients) { container.contacts.nameIfKnown(it) ?: ContactLookup.formatAddress(it) }
    private val subtitle = subtitleFor(title)

    /** [number]'s contact name, or null if it isn't a contact. Reads contacts: not on the main thread. */
    fun contactName(number: String): String? = repo.contactName(number)

    /** Whether contacts can be read, so "not a contact" means something. */
    fun canReadContacts(): Boolean = container.contacts.canRead()

    /** For a group, the two faces its avatar shows. [known]: only from what's known already (on the main thread). */
    private fun membersOf(known: Boolean = false): List<Member> =
        if (recipients.size > 1) {
            groupFaces(
                recipients.take(GROUP_FACE_CANDIDATES).map {
                    if (known) Member(it, container.contacts.nameIfKnown(it) ?: ContactLookup.formatAddress(it), container.contacts.photoIfKnown(it))
                    else Member(it, repo.displayName(it), repo.photoUri(it))
                },
            )
        } else {
            emptyList()
        }

    private fun subtitleFor(title: String) = when {
        recipients.size > 1 -> "${recipients.size + 1} people"
        else -> recipients.singleOrNull()?.let(ContactLookup::formatAddress)?.takeIf { it != title }
    }

    /**
     * The composer's text, which the text field edits in place. Changes from here (a location
     * link, Undo, sending) happen on the main thread, so they never race the typing.
     */
    val draftField = TextFieldState()
    val draft: StateFlow<String> = snapshotFlow { draftField.text.toString() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private fun currentDraft() = draftField.text.toString()

    private val _attachments = MutableStateFlow<List<OutgoingAttachment>>(emptyList())
    val attachments: StateFlow<List<OutgoingAttachment>> = _attachments.asStateFlow()

    private val blockedNumbers = container.blockedNumbers
    private val _blocked = MutableStateFlow(false)
    /** The single recipient is on Android's block list. */
    val blocked: StateFlow<Boolean> = _blocked.asStateFlow()
    /** Only 1:1 conversations can be blocked, and only while Winnow is the SMS app. */
    val canBlock: Boolean get() = recipients.size == 1 && blockedNumbers.available()

    private val scheduler = container.scheduler

    /** Texts in this conversation waiting for their send time. */
    val scheduled: StateFlow<List<ScheduledMessageEntity>> = threadId
        .filter { it >= 0 }
        .flatMapLatest { scheduler.observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** When messages here are to come back ("Remind me"), by message key. */
    val reminders: StateFlow<Map<String, com.ericflo.winnow.notify.Reminders.Mark>> = threadId
        .filter { it >= 0 }
        .flatMapLatest { container.reminders.observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Brings [message] back as a notification at [at] (moving a reminder it already has). */
    fun remind(message: ChatMessage, at: Long, label: String) = launch {
        container.reminders.set(message, recipients, at)
        _notices.emit(
            if (container.reminders.canShow()) "Reminder set: $label"
            else "Reminder set: $label. Notifications are off for Winnow, so it can't show: turn them on in Android's settings.",
        )
    }

    fun cancelReminder(message: ChatMessage) = launch { container.reminders.cancel(message.key) }

    /** The undo window in seconds; null until settings have loaded. */
    private val undoSeconds: StateFlow<Int?> = container.settings.settings.map<WinnowSettings, Int?> { it.undoSendSeconds }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val quickReplies: StateFlow<List<String>> = container.settings.settings.map { it.quickReplies }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Whether Winnow knows this phone's number, from Android or learned (see OwnNumbers): if it
     * does, there's nothing to ask for. Known until found otherwise, so the card never flashes.
     */
    private val _ownNumberKnown = MutableStateFlow(true)
    val ownNumberKnown: StateFlow<Boolean> = _ownNumberKnown.asStateFlow()
    private val _ownNumberDoubtful = MutableStateFlow(false)
    /** Android gives a number, but group texts aren't sent to it (see OwnNumbers.doubtful). */
    val ownNumberDoubtful: StateFlow<Boolean> = _ownNumberDoubtful.asStateFlow()

    /** Asks again (the Phone numbers permission was just allowed, say). */
    fun refreshOwnNumber() {
        viewModelScope.launch(Dispatchers.IO) {
            _ownNumberKnown.value = runCatching { com.ericflo.winnow.data.OwnNumbers(container.appContext).let { it.all().isNotEmpty() && !it.doubtful() } }.getOrDefault(true)
            _ownNumberDoubtful.value = runCatching { com.ericflo.winnow.data.OwnNumbers(container.appContext).doubtful() }.getOrDefault(false)
        }
    }

    /** The user typed their own number (see OwnNumberBanner); false if it doesn't look like one. */
    fun setOwnNumber(number: String): Boolean {
        if (number.any(Char::isLetter) || number.count(Char::isDigit) < 10) return false
        viewModelScope.launch(Dispatchers.IO) {
            com.ericflo.winnow.data.OwnNumbers(container.appContext).learn(number)
            _ownNumberKnown.value = true
            _ownNumberDoubtful.value = false
        }
        return true
    }

    /** Whether the user said "Not now" to the card asking for their own number; true until known, so it doesn't flash. */
    val ownNumberCardDismissed: StateFlow<Boolean> = container.settings.settings.map { it.ownNumberCardDismissed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun dismissOwnNumberCard() {
        // The app's scope: "Not now" then straight back mustn't lose it.
        container.appScope.launch { container.settings.update { it.copy(ownNumberCardDismissed = true) } }
    }

    /** A quick reply into the composer, after whatever's typed. */
    fun insertQuickReply(text: String) {
        setDraft(listOf(currentDraft().trimEnd(), text).filter { it.isNotEmpty() }.joinToString(" "))
    }

    val enterToSend: StateFlow<Boolean> = container.settings.settings.map { it.enterToSend }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Settings → Simple characters, so the composer counts what will go out. */
    val simpleCharacters: StateFlow<Boolean> = container.settings.settings.map { it.simpleCharacters }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Message text size; see [TextScale]. Pinching the conversation changes it. */
    val textScale: StateFlow<Float> = container.settings.settings
        .map { it.textScale }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    fun setTextScale(value: Float) {
        viewModelScope.launch { container.settings.update { it.copy(textScale = TextScale.clamp(value)) } }
    }

    private val _unreadOnOpen = MutableStateFlow<List<String>>(emptyList())
    // setVisible marks the thread read too, possibly before init has looked at what was unread.
    private val unreadCaptured = CompletableDeferred<Unit>()
    /** The messages that were unread when the conversation opened, oldest first. */
    val unreadOnOpen: StateFlow<List<String>> = _unreadOnOpen.asStateFlow()

    private val _sims = MutableStateFlow<List<SimCard>>(emptyList())
    /** The phone's SIMs when there are two or more to choose from; empty otherwise. */
    val sims: StateFlow<List<SimCard>> = _sims.asStateFlow()

    private val _selectedSim = MutableStateFlow<Int?>(null)
    /** The SIM this conversation's texts go out on; null for Android's default. */
    val selectedSim: StateFlow<Int?> = _selectedSim.asStateFlow()
    /** Whether [_selectedSim] is the conversation's choice yet (loaded, or picked): until then it's only "default". */
    @Volatile private var simKnown = false

    /**
     * The SIM to send on, as chosen when Send was tapped: one tapped before the conversation's
     * choice has loaded looks it up, rather than going out on whatever SIM is Android's default.
     */
    private fun simForSend(): suspend () -> Int? {
        val picked = _selectedSim.value
        if (simKnown) return { picked }
        val thread = threadId.value
        return { container.simFor(thread) }
    }

    /**
     * Opening onto a particular message: find-in-conversation for [query] with [focusKey] in view
     * (from a search result), or with no query just that message, briefly highlighted (from Starred).
     */
    data class SearchRequest(val query: String?, val focusKey: String?)

    private val _searchRequest = MutableStateFlow<SearchRequest?>(null)
    /** One-shot: the screen acts on it, then calls [searchRequestHandled]. */
    val searchRequest: StateFlow<SearchRequest?> = _searchRequest.asStateFlow()

    fun requestSearch(request: SearchRequest) {
        _searchRequest.value = request
    }

    fun searchRequestHandled() {
        _searchRequest.value = null
    }

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off messages for a snackbar. */
    val notices: SharedFlow<String> = _notices

    init {
        viewModelScope.launch {
            recipients.singleOrNull()?.let { _blocked.value = blockedNumbers.isBlocked(it) }
        }
        viewModelScope.launch {
            if (threadId.value < 0) {
                threadId.value = repo.threadIdFor(recipients)
                // Opened by number (a link, a new chat): the screen said it was visible before the
                // thread was known. Now its texts don't notify while it's showing.
                if (visible) setVisible(true)
            }
            val id = threadId.value
            // Read before marking read: where this visit's "new messages" begin.
            if (id >= 0) _unreadOnOpen.value = runCatching { repo.unreadIncoming(id) }.getOrDefault(emptyList())
            unreadCaptured.complete(Unit)
            repo.markRead(id)
            if (!inBubble) container.notifier.cancel(id)
            _sims.value = container.sims.available().takeIf { it.size >= 2 }.orEmpty()
            val sim = container.simFor(id)
            // Not over one picked meanwhile.
            if (!simKnown) _selectedSim.value = sim
            simKnown = true
            val saved = states.get(id)
            // A forwarded or shared draft joins what was already waiting here, rather than replacing it.
            saved.draft?.let { text -> setDraft(if (currentDraft().isEmpty()) text else ReturnedMessages.appendTo(text, currentDraft())) }
            // Joined to one opened (or typed) before this ran, never dropped for it.
            saved.draftSubject?.let { s -> setSubject(if (_subjectShown.value) ReturnedMessages.mergeSubjects(s, currentSubject()) else s) }
            subjectLoaded = true
            // The subject is kept with the draft from here on: whatever it is now (typed before
            // this ran), then every change, but not the saved one written back again.
            var savedSubject = saved.draftSubject
            launch(start = CoroutineStart.UNDISPATCHED) {
                subjectChanges().debounce(400).collect {
                    if (it != savedSubject) states.saveDraftSubject(id, it)
                    savedSubject = it
                }
            }
            val kept = withContext(Dispatchers.IO) { drafts.decode(saved.draftAttachments) }
            if (kept.isNotEmpty()) _attachments.value = kept + _attachments.value.filter { a -> kept.none { it.uri == a.uri } }
            // From here on every change to the attachments is kept, the first included: a share's
            // attachments or a returned message's are in by now, and must outlive the app too.
            launch(start = CoroutineStart.UNDISPATCHED) { _attachments.debounce(400).collect { keepAttachments(id) } }
            // Listening first, so one that comes back in between isn't missed.
            launch(start = CoroutineStart.UNDISPATCHED) { container.returnedMessages.arrived.filter { it == id }.collect { takeReturned(id) } }
            takeReturned(id)
            // Saved now, not on the first keystroke: a shared or forwarded draft (already in by
            // the time this runs) must outlive the app being closed before the user types.
            if (currentDraft().isNotEmpty()) states.saveDraft(id, currentDraft())
            draft.drop(1).debounce(400).collect { states.saveDraft(id, it) }
        }
    }

    val state: StateFlow<ThreadUiState> = threadId
        .filter { it >= 0 }
        .flatMapLatest { id ->
            combine(
                repo.messages(id),
                states.observeTimed().map { it[id] },
                container.starredDao.observeKeys(id),
                // Names and photos read again when contacts change, without reloading the messages.
                repo.contactChanges().onStart { emit(Unit) },
            ) { messages, s, starredKeys, _ ->
                val stars = starredKeys.toSet()
                // Read again each time: a contact added (or renamed) while this is open shows up.
                val name = displayNameFor(recipients, repo::displayName)
                val single = recipients.singleOrNull()
                ThreadUiState(
                    title = s?.title ?: name,
                    subtitle = subtitleFor(name),
                    recipients = recipients,
                    messages = if (stars.isEmpty()) messages else messages.map { if (it.key in stars) it.copy(starred = true) else it },
                    verdict = messages.lastOrNull { !it.outgoing }?.verdict,
                    senderNames = messages.mapNotNull { it.sender }.distinct().associateWith(repo::displayName),
                    photos = (recipients + messages.mapNotNull { it.sender }).distinct()
                        .mapNotNull { address -> repo.photoUri(address)?.let { address to it } }.toMap(),
                    muted = s?.isMuted() == true,
                    mutedUntil = s?.takeIf { it.isMuted() }?.mutedUntil,
                    archived = s?.archived == true,
                    members = membersOf(),
                    // A real number with no name: short codes and alphanumeric senders aren't people to add.
                    addableContact = single?.takeIf {
                        container.contacts.canRead() && repo.contactName(it) == null && ContactLookup.isReachable(it)
                    },
                )
            }
        }
        // Contact lookups can hit the disk (the whole list, after a change).
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState(title, subtitle, recipients, members = membersOf(known = true)))

    /**
     * Whose links may be previewed here (normalized addresses), or null when the setting is off.
     * A sender counts when they're a contact, or this is a one-to-one conversation the user has
     * texted in. In a group one contact doesn't vouch for strangers, so only contacts count.
     * Each message is checked too: see ThreadScreen.
     */
    val linkPreviewSenders: StateFlow<Set<String>?> = combine(container.settings.settings.map { it.linkPreviews }, state) { on, s ->
        if (!on) return@combine null
        val texted = s.recipients.size == 1 && s.messages.any { it.outgoing }
        s.recipients.filter { texted || container.contacts.isContact(it) }.map(::normalizeAddress).toSet()
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A sent message waiting out its undo window (see [pending]). */
    private val _pending = MutableStateFlow<PendingSend?>(null)

    /** The message the user last sent or scheduled an answer to: no reply ideas for it until it changes. */
    private val answered = MutableStateFlow<String?>(null)

    /**
     * Reply ideas for the newest message, when it's someone else's and worth answering: from
     * Android's on-device classifier (nothing leaves the phone), with the setting on. Never for
     * anything filtered, silenced or fraud, or a sender that can't take replies (a short code or
     * a name). Empty otherwise, and while it's being worked out.
     */
    val suggestedReplies: StateFlow<List<String>> = combine(container.settings.settings.map { it.suggestedReplies }, state, _pending, answered) { on, s, pending, answeredKey ->
        // Reactions ("Loved “…”") aren't something to answer, nor what an answer reads.
        val spoken = latestSpoken(s.messages, SUGGESTION_CONTEXT)
        val newest = spoken.lastOrNull()
        // Not while a reply to it is on its way (undo window, a photo shrinking): it's answered.
        val worthIt = on && newest != null && !newest.outgoing && pending == null && newest.key != answeredKey &&
            subjectAndText(newest.subject, newest.body).isNotBlank() &&
            (newest.verdict == null || newest.verdict.effectiveAction == Action.ALLOW) &&
            !s.linksOff(newest) &&
            s.recipients.all(ContactLookup::isReachable)
        newest?.key.takeIf { worthIt } to spoken
    }
        .distinctUntilChanged { a, b -> a.first == b.first }
        .transformLatest { (key, messages) ->
            // The last message's ideas never stand in for this one's while they're worked out.
            emit(emptyList())
            if (key == null) return@transformLatest
            val turns = messages.takeLast(SUGGESTION_CONTEXT)
                .map { SmartLinks.Turn(subjectAndText(it.subject, it.body), it.outgoing, it.sender, it.timestamp) }
                .filter { it.text.isNotBlank() }
            emit(container.smartLinks.suggestReplies(turns))
        }
        // Not on the main thread: it runs on every change to the conversation.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun preview(url: String): LinkPreview? = container.linkPreviews.get(url)

    suspend fun smartLinks(text: String): List<SmartLink> = container.smartLinks.find(text)

    suspend fun smartActions(text: String, link: SmartLink, sentAt: Long): List<SmartAction> = container.smartLinks.actions(text, link, sentAt)

    /** The real thread id, once a new conversation's thread has been created; negative before. */
    fun currentThreadId(): Long = threadId.value

    /**
     * While the conversation is on screen, incoming texts for it don't notify (see
     * IncomingMessageHandler) and coming back to it marks it read.
     */
    @Volatile private var visible = false

    fun setVisible(visible: Boolean) {
        this.visible = visible
        val id = threadId.value
        if (visible) {
            container.visibleThread.value = id.takeIf { it >= 0 }
            if (id >= 0) launch {
                unreadCaptured.await()
                repo.markRead(id)
                if (!inBubble) container.notifier.cancel(id)
            }
        } else if (container.visibleThread.value == id) {
            container.visibleThread.value = null
        }
    }

    private val drafts = container.draftAttachments

    /**
     * Copies the composer's attachments into draft storage, swaps the copies in, and saves the
     * list with the conversation, so the draft survives the app being closed.
     */
    private suspend fun keepAttachments(id: Long) = attachmentLock.withLock {
        val now = _attachments.value
        val kept = withContext(Dispatchers.IO) { now.associateWith { drafts.keep(it) ?: it } }
        kept.forEach { (old, new) ->
            if (old != new) {
                keptAs[old] = new
                draftCopies[old] = new
            }
        }
        // Changes made meanwhile stay; only the copied ones are swapped.
        _attachments.value = _attachments.value.map { kept[it] ?: it }
        states.saveDraftAttachments(id, drafts.encode(_attachments.value))
    }

    /** Keeping a draft's attachments and rotating one take turns, so neither undoes the other. */
    private val attachmentLock = Mutex()
    /** What each attachment was replaced by (kept as a draft, or turned), so a tap on the old one finds it. Guarded by [attachmentLock]. */
    private val keptAs = HashMap<OutgoingAttachment, OutgoingAttachment>()
    /** Just the draft copies among those: the same picture, under another name. Read without the lock (by the crop editor). */
    private val draftCopies = java.util.concurrent.ConcurrentHashMap<OutgoingAttachment, OutgoingAttachment>()

    /**
     * [attachment] as the composer holds it now: itself, or its draft copy (the same picture).
     * Null once it's gone (sent, removed) or been edited since, so it's no longer what was shown.
     */
    fun sameAs(attachment: OutgoingAttachment): OutgoingAttachment? {
        var current = attachment
        repeat(MAX_KEPT_HOPS) {
            if (current in _attachments.value) return current
            current = draftCopies[current] ?: return null
        }
        return current.takeIf { it in _attachments.value }
    }

    /** A message that failed after an earlier screen for this conversation had gone: back into the composer. */
    private suspend fun takeReturned(id: Long) {
        val returned = container.returnedMessages.take(id) ?: return
        // Its text was saved as the draft too, which this screen may have restored already.
        setDraft(ReturnedMessages.appendTo(currentDraft(), returned.text))
        mergeSubject(returned.subject)
        if (returned.shared) {
            // Shared from another app: checked like anything else attached (a video may need shrinking).
            returned.attachments.forEach(::addAttachment)
            return
        }
        // Its attachments were saved with the draft too; the same files mustn't show twice.
        _attachments.value = _attachments.value + returned.attachments.filter { r -> _attachments.value.none { it.uri == r.uri } }
        if (returned.separately) _sendSeparately.value = true
        _notices.emit("A message that couldn't be sent is back in the composer")
    }

    @Volatile private var cleared = false

    /** Leaving within the debounce window would drop the last keystrokes; save whatever is there. */
    override fun onCleared() {
        cleared = true
        recorder.stopAndDiscard()
        val id = threadId.value
        val left = _attachments.value
        if (id >= 0) {
            val draft = currentDraft()
            // Only once the saved one was read: before that, "no subject" would erase it.
            val subject = currentSubject().takeIf { subjectLoaded }
            // What's in the composer stays with the draft, kept where it outlives the app.
            container.appScope.launch {
                states.saveDraft(id, draft)
                if (subjectLoaded) states.saveDraftSubject(id, subject)
                val kept = left.mapNotNull { drafts.keep(it) }
                states.saveDraftAttachments(id, drafts.encode(kept))
                left.forEach(recorder::discard)
            }
        } else {
            left.forEach(recorder::discard)
        }
        if (container.visibleThread.value == id) container.visibleThread.value = null
    }

    fun setDraft(value: String) {
        draftField.setTextAndPlaceCursorAtEnd(value)
    }

    /** A GIF, sticker or picture from the keyboard, copied in before its permission can lapse. */
    fun addKeyboardContent(uri: android.net.Uri, type: String?) = launch {
        val attachment = withContext(Dispatchers.IO) { container.sharedFiles.import(uri, type) }
        if (attachment != null) addAttachment(attachment) else _notices.emit("Couldn't attach that")
    }

    /**
     * Photos are always taken (they're shrunk to fit when sent). A GIF, video or recording too big
     * for any MMS is turned away now, rather than failing once the message is on its way.
     */
    fun addAttachment(attachment: OutgoingAttachment) {
        // Photos shrink when sent; recordings (800 KB at most) and cards are small. Added at once,
        // so a Send right after Done includes them.
        val couldBeHuge = attachment.contentType.startsWith("video/") || attachment.contentType == "image/gif"
        if (!couldBeHuge) {
            _attachments.value = _attachments.value + attachment
            return
        }
        launch {
            val size = withContext(Dispatchers.IO) { container.sharedFiles.sizeOf(attachment.uri) }
            if (size != null && attachment.contentType.startsWith("video/") && size > roomLeft()) {
                // Too big for what the rest of the message leaves; made to fit.
                shrinkVideo(attachment)
            } else if (size != null && size > budget()) {
                val what = when {
                    attachment.contentType == "image/gif" -> "That GIF is"
                    attachment.contentType.startsWith("video/") -> "That video is"
                    attachment.contentType.startsWith("audio/") -> "That recording is"
                    else -> "That attachment is"
                }
                _notices.emit("$what too big to send by MMS (${size / 1000} KB; your carrier takes about ${budget() / 1000} KB)")
            } else {
                _attachments.value = _attachments.value + attachment
            }
        }
    }

    fun newCameraPhoto() = container.sharedFiles.newCameraPhoto()

    /** [message]'s photos, videos, recordings and cards, copied so they can be forwarded. */
    suspend fun forwardable(message: ChatMessage): List<OutgoingAttachment> =
        withContext(Dispatchers.IO) { message.attachments.mapNotNull { container.sharedFiles.copyPart(it) } }

    fun newCameraVideo() = container.sharedFiles.newCameraVideo()

    /**
     * Saves attachments to the phone's Pictures, Movies, Recordings or Download. Returns what to
     * tell the user; the caller shows it, since the photo viewer covers this screen's snackbar.
     */
    suspend fun save(attachments: List<Attachment>): String {
        val folders = withContext(Dispatchers.IO) { attachments.map { container.mediaExport.save(it) } }
        val saved = folders.filterNotNull()
        return when {
            saved.isEmpty() -> "Couldn't save that"
            saved.size < folders.size -> "Saved ${saved.size} of ${folders.size}"
            saved.size == 1 -> "Saved to ${saved.single()}"
            saved.distinct().size == 1 -> "Saved ${saved.size} to ${saved.first()}"
            else -> "Saved ${saved.size} attachments"
        }
    }

    /** A share sheet for attachments, or null if they couldn't be copied out. */
    suspend fun shareIntent(attachments: List<Attachment>): android.content.Intent? =
        withContext(Dispatchers.IO) { container.mediaExport.shareIntent(attachments) }

    private val recorder = container.newVoiceRecorder()
    private val _recording = MutableStateFlow(false)
    /** A voice message is being recorded. */
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    fun recordingElapsed(): Long = recorder.elapsed()

    fun startRecording() {
        if (recorder.start(onLimit = { viewModelScope.launch { stopRecording() } })) {
            _recording.value = true
        } else {
            _notices.tryEmit("Couldn't use the microphone")
        }
    }

    /** Keeps the recording as an attachment, ready to send. */
    fun stopRecording() {
        // Done tapped just as the time or size limit stopped it: nothing left to stop.
        if (!_recording.value) return
        _recording.value = false
        val voice = recorder.stop()
        if (voice != null) addAttachment(voice) else _notices.tryEmit("Too short to send")
    }

    /**
     * The conversation went off screen (the app was left, or another screen opened): the
     * microphone stops, and what was recorded waits in the composer.
     */
    fun finishRecording() {
        if (!_recording.value) return
        _recording.value = false
        recorder.stop()?.let(::addAttachment)
    }

    fun cancelRecording() {
        recorder.stopAndDiscard()
        _recording.value = false
    }

    private val _shrinking = MutableStateFlow<Int?>(null)
    /** A video is being made small enough to send: how far along, 0–100. */
    val shrinking: StateFlow<Int?> = _shrinking.asStateFlow()

    /** Bytes the composer's other attachments leave in an MMS: theirs at size, photos at the least they shrink to. */
    private suspend fun roomLeft(): Long {
        val others = _attachments.value
        val taken = withContext(Dispatchers.IO) {
            others.sumOf { a -> if (MmsSender.canShrink(a.contentType)) MmsSender.MIN_PHOTO_BYTES.toLong() else container.sharedFiles.sizeOf(a.uri) ?: 0L }
        }
        return budget() - taken
    }

    /**
     * Whether the carrier has the draft sent as an MMS from here (it's long; see
     * MmsSender.textNeedsMms). Worked out off the main thread, as the typing settles.
     */
    @OptIn(FlowPreview::class)
    val sendsAsMms: StateFlow<Boolean> = combine(draft.debounce(300), _selectedSim) { text, sim -> text to sim }
        .map { (text, sim) -> text.length > 70 && container.mmsSender.textNeedsMms(container.smsSender.prepared(text), sim) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** What might stop a message sending right now (see SendReadiness), for the composer and failed bubbles. */
    val readiness: StateFlow<SendReadiness.State> = container.sendReadiness.changes { _selectedSim.value }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SendReadiness.State())

    /** What one MMS can carry on the SIM this conversation sends from. */
    private suspend fun budget(): Long = withContext(Dispatchers.IO) { container.mmsSender.messageBudget(_selectedSim.value).toLong() }

    private suspend fun shrinkVideo(video: OutgoingAttachment) {
        // Whatever the rest of the message leaves: other videos, recordings and cards at their
        // size, photos at the least they shrink to.
        val budget = roomLeft()
        if (budget < MIN_VIDEO_ROOM) {
            _notices.emit("There's no room left in this MMS for that video. Send it in a message of its own.")
            return
        }
        _shrinking.value = 0
        val result = try {
            container.videoShrinker.shrink(android.net.Uri.parse(video.uri), budget) { _shrinking.value = it }
        } finally {
            _shrinking.value = null
        }
        if (result == null) {
            _notices.emit("That video is too big to send by MMS, and couldn't be made smaller")
            return
        }
        val name = video.name?.substringBeforeLast('.')?.let { "$it.mp4" } ?: "video.mp4"
        _attachments.value = _attachments.value + OutgoingAttachment(android.net.Uri.fromFile(result.file).toString(), "video/mp4", name)
        result.trimmedToMillis?.let { _notices.emit("Only the first ${it / 1000} seconds fit in an MMS, so that's what will be sent") }
    }

    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    /** Adds a map link for where the phone is to the draft; the user sends it (or doesn't). */
    fun shareLocation() {
        if (_locating.value) return
        _locating.value = true
        launch {
            val location = runCatching { container.currentLocation.get() }.getOrNull()
            _locating.value = false
            if (location == null) {
                _notices.emit("Couldn't get your location. Is location turned on?")
            } else {
                val link = CurrentLocation.mapLink(location)
                setDraft(listOf(currentDraft().trimEnd(), link).filter { it.isNotEmpty() }.joinToString(" "))
            }
        }
    }

    /** Attaches a card for a number from the phone-number picker. */
    fun attachPhone(phone: android.net.Uri) = launch {
        val card = withContext(Dispatchers.IO) { container.sharedFiles.phoneCard(phone) }
        if (card != null) addAttachment(card) else _notices.emit("Couldn't read that contact")
    }

    /** Attaches a contact from the picker as a vCard. */
    fun attachContact(contact: android.net.Uri) = launch {
        val card = withContext(Dispatchers.IO) { container.sharedFiles.contactCard(contact) }
        if (card != null) addAttachment(card) else _notices.emit("Couldn't read that contact")
    }

    /**
     * Turns a photo in the composer a quarter-turn clockwise, in its place. Taps queue: two quick
     * ones turn it twice, each following the copy the one before (or the draft) left.
     */
    fun rotateAttachment(attachment: OutgoingAttachment) =
        editAttachment(attachment, "Couldn't rotate that photo", afterEdits = true) { container.sharedFiles.rotated(it) }

    /**
     * Cuts a photo in the composer to [box] and draws [strokes] on it (see PhotoCrop), in its
     * place. Both were drawn on that picture, so it follows only a draft copy: a photo turned
     * meanwhile isn't changed.
     */
    fun editPhoto(attachment: OutgoingAttachment, box: PhotoCrop.Box, strokes: List<PhotoCrop.Stroke>) =
        editAttachment(attachment, "Couldn't edit that photo", afterEdits = false) { container.sharedFiles.cropped(it, box, strokes) }

    /**
     * Replaces a photo in the composer with [edit]'s copy of it. Edits queue: each follows the
     * copy the one before (or the draft) left.
     */
    private fun editAttachment(
        attachment: OutgoingAttachment,
        failure: String,
        /** Whether an edit made since counts as the same photo (a turn does; a crop's box doesn't). */
        afterEdits: Boolean,
        edit: (OutgoingAttachment) -> OutgoingAttachment?,
    ) = launch {
        attachmentLock.withLock {
            // Replaced since the tap (edited, or kept as a draft): edit what replaced it. Gone (sent, removed): nothing to do.
            var current = attachment
            val hops = if (afterEdits) keptAs else draftCopies
            repeat(MAX_KEPT_HOPS) {
                if (current !in _attachments.value) {
                    current = hops[current] ?: run {
                        if (!afterEdits && current in keptAs) _notices.emit("That photo changed before your edit was saved. Edit it again.")
                        return@withLock
                    }
                }
            }
            if (current !in _attachments.value) return@withLock
            val edited = withContext(Dispatchers.IO) { edit(current) }
            if (edited == null) {
                _notices.emit(failure)
                return@withLock
            }
            // Sent (or removed) while it was edited: the original is what went, and must stay; the copy isn't wanted.
            if (current !in _attachments.value) {
                withContext(Dispatchers.IO) { container.sharedFiles.discardCopy(edited) }
                return@withLock
            }
            keptAs[current] = edited
            _attachments.value = _attachments.value.map { if (it == current) edited else it }
            withContext(Dispatchers.IO) { container.sharedFiles.discardCopy(current) }
        }
    }

    /** A photo's upright width and height, for the crop editor; null (and says so) if it can't be read. */
    suspend fun photoSize(attachment: OutgoingAttachment): Pair<Int, Int>? =
        withContext(Dispatchers.IO) { container.sharedFiles.uprightSize(attachment.uri) }
            .also { if (it == null) _notices.emit("Couldn't open that photo") }

    fun removeAttachment(attachment: OutgoingAttachment) {
        _attachments.value = _attachments.value - attachment
        recorder.discard(attachment)
    }

    private val _sendSeparately = MutableStateFlow(false)
    /**
     * The composer holds a "send separately" message that came back (Undo, or every send
     * failed); Send sends it separately again instead of as one group text.
     */
    val sendSeparately: StateFlow<Boolean> = _sendSeparately.asStateFlow()

    /** Send the message back in the composer to the group after all. */
    fun clearSendSeparately() {
        _sendSeparately.value = false
    }


    private fun markAnswered() {
        answered.value = state.value.messages.lastOrNull { !it.outgoing }?.key
    }

    /** The composer's MMS subject line, while it shows (see [subjectShown]). */
    val subjectField = TextFieldState()
    private val _subjectShown = MutableStateFlow(false)
    /** Whether the composer has a subject field (Attach → Subject). */
    val subjectShown: StateFlow<Boolean> = _subjectShown.asStateFlow()
    /** The saved subject has been read, so saving what's here can't erase it. */
    @Volatile private var subjectLoaded = false

    /** The subject as it stands: null with no subject field, "" for an empty one. */
    private fun currentSubject(): String? = if (_subjectShown.value) subjectField.text.toString() else null

    /** Something to send as a subject, or null. */
    private fun sendableSubject(): String? = currentSubject()?.trim()?.takeIf { it.isNotEmpty() }

    private fun subjectChanges(): Flow<String?> =
        combine(_subjectShown, snapshotFlow { subjectField.text.toString() }) { shown, text -> if (shown) text else null }.distinctUntilChanged()

    private fun setSubject(value: String?) {
        // Set, not typed, so the field's own filter never sees it: one line here too.
        subjectField.setTextAndPlaceCursorAtEnd(value.orEmpty().replace("\r\n", " ").replace('\n', ' ').replace('\r', ' '))
        _subjectShown.value = value != null
    }

    /** A subject coming back (undo, a failed send) joins the one being written, never replaces it. */
    private fun mergeSubject(returned: String?) {
        if (returned.isNullOrBlank()) return
        setSubject(ReturnedMessages.mergeSubjects(returned, currentSubject()))
    }

    /** Shows the subject field (Attach → Subject). */
    fun addSubject() {
        if (!_subjectShown.value) setSubject("")
    }

    fun removeSubject() = setSubject(null)

    /** A sent message waiting out the undo window; null when nothing is pending. */
    data class PendingSend(
        val text: String,
        val attachments: List<OutgoingAttachment>,
        val sendsAt: Long,
        val windowMillis: Long,
        /** To each group member as their own text, rather than to the group. */
        val separately: Boolean = false,
        /** An MMS subject, if one was written. */
        val subject: String? = null,
        /** Its id in PendingSends, where it's kept until it goes. */
        val heldId: Long = 0,
    )

    val pending: StateFlow<PendingSend?> = _pending.asStateFlow()
    @Volatile private var pendingJob: Job? = null

    /** [separately]: in a group, each person gets their own text and replies come back one to one. */
    fun send(separately: Boolean = false) {
        val text = currentDraft().trim()
        val files = _attachments.value
        val subject = sendableSubject()
        if (text.isEmpty() && files.isEmpty() && subject == null || _pending.value != null) return
        // An RCS chat's people can't be texted (see ThreadUiState.cantSend): the composer isn't offered either.
        if (recipients.any(::isRcsAddress)) return
        val apart = (separately || _sendSeparately.value) && recipients.size > 1
        // Replying means the new messages have been read; the divider has done its job.
        _unreadOnOpen.value = emptyList()
        markAnswered()
        draftField.clearText()
        _attachments.value = emptyList()
        setSubject(null)
        _sendSeparately.value = false
        val sim = simForSend()
        // People the user texts become share-sheet targets too, not only people who text them.
        container.appScope.launch {
            val s = state.value
            container.notifier.publishConversation(threadId.value, recipients, s.title, recipients.singleOrNull()?.let { s.photos[it] })
        }
        val window = (undoSeconds.value ?: 0) * 1000L
        // Claimed right here, on the main thread, so a second Send meanwhile waits for this one.
        val waiting = if (window > 0) {
            PendingSend(text, files, System.currentTimeMillis() + window, window, apart, subject, heldId = Random.nextLong(1, Long.MAX_VALUE))
        } else {
            null
        }
        waiting?.let { _pending.value = it }
        val thread = threadId.value
        val people = recipients
        // The app scope, not this ViewModel's: leaving the conversation, mid-countdown or halfway
        // through sending to each person, must not lose the message.
        val job = container.appScope.launch(start = CoroutineStart.LAZY) {
            val subscription = sim()
            // Kept until it goes, before the draft is cleared: the app closing mid-countdown must
            // not lose it (an alarm sends it then; see PendingSends).
            // Failing that, the countdown still sends it.
            val held = waiting != null && runCatching {
                withContext(Dispatchers.IO) { container.pendingSends.hold(PendingSends.Held(waiting.heldId, thread, people, text, waiting.sendsAt, subject, apart, subscription)) }
            }.onFailure { android.util.Log.w("WinnowThread", "Couldn't keep a message waiting out Undo send", it) }.isSuccess
            states.saveDraft(thread, "")
            states.saveDraftSubject(thread, null)
            if (waiting != null) {
                if (held && files.isNotEmpty()) {
                    runCatching { withContext(Dispatchers.IO) { container.pendingSends.attach(waiting.heldId, drafts.encode(files.mapNotNull(drafts::keep))) } }
                }
                delay(window)
                // Undo and the end of the countdown race for it; whichever takes it, the other does nothing.
                if (!_pending.compareAndSet(waiting, null)) return@launch
                // Gone only if its alarm sent it: the app was held up past its time.
                if (held && runCatching { withContext(Dispatchers.IO) { container.pendingSends.take(waiting.heldId) } }.let { it.isSuccess && it.getOrNull() == null }) return@launch
            }
            deliver(text, files, subscription, apart, subject)
        }
        if (waiting != null) pendingJob = job
        job.start()
    }

    /** Cancels a message still inside its undo window and puts it back in the composer. */
    fun undoSend() {
        val pending = _pending.value ?: return
        if (!_pending.compareAndSet(pending, null)) return
        pendingJob?.cancel()
        container.appScope.launch {
            // Taken back from its alarm too. Already taken only if the alarm sent it, which it
            // can't do before its time: the app was held up past it.
            val held = runCatching { withContext(Dispatchers.IO) { container.pendingSends.take(pending.heldId) } }
            if (held.isSuccess && held.getOrNull() == null && System.currentTimeMillis() >= pending.sendsAt + PendingSends.GRACE_MILLIS) {
                _notices.emit("It had already gone out")
                return@launch
            }
            putBack(pending.text, pending.attachments, pending.separately, subject = pending.subject)
        }
    }

    /**
     * A message that didn't go out goes back in the composer, alongside anything typed or attached
     * since. If this screen is gone, it waits for the conversation to open again (and the text is
     * saved as the draft, in case that's after the app has closed).
     */
    private fun putBack(text: String, files: List<OutgoingAttachment>, separately: Boolean, reason: String? = null, subject: String? = null) {
        val id = threadId.value
        // Decided on the main thread, where the screen is cleared, so it can't go in between.
        container.appScope.launch(Dispatchers.Main.immediate) {
            if (!cleared) {
                setDraft(listOf(text, currentDraft()).filter { it.isNotBlank() }.joinToString("\n"))
                _attachments.value = files + _attachments.value
                if (separately) _sendSeparately.value = true
                mergeSubject(subject)
                return@launch
            }
            withContext(Dispatchers.IO) {
                if (id >= 0) {
                    val saved = states.get(id)
                    states.saveDraft(id, ReturnedMessages.appendTo(saved.draft.orEmpty(), text))
                    // The attachments too, kept where they outlive the app, alongside any already saved.
                    val kept = files.mapNotNull { drafts.keep(it) }
                    val already = drafts.decode(saved.draftAttachments)
                    states.saveDraftAttachments(id, drafts.encode(already + kept.filter { k -> already.none { it.uri == k.uri } }))
                    // The subject saved with the draft (joining any there) before it's handed back,
                    // so a conversation opening in between finds it one way or the other.
                    if (!subject.isNullOrBlank()) states.saveDraftSubject(id, ReturnedMessages.mergeSubjects(subject, saved.draftSubject))
                    container.returnedMessages.put(id, ReturnedMessages.Returned(text, kept, separately, subject = subject))
                }
            }
            container.toast("Couldn't send${reason?.let { ": $it" }.orEmpty()}. It's back in the conversation's composer.")
        }
    }

    private suspend fun deliver(text: String, files: List<OutgoingAttachment>, sim: Int?, separately: Boolean = false, subject: String? = null) {
        if (separately && recipients.size > 1) return deliverSeparately(text, files, sim, subject)
        try {
            repo.send(recipients, text, files, sim, subject)
            // Sent: the message holds its own copy of any recording now.
            if (container.isLive.value) files.forEach(recorder::discard)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StoredAsFailed) {
            // In the conversation, marked not sent, with its own copy of any recording: tried
            // again from there. Back in the composer as well, it could go out twice.
            if (container.isLive.value) files.forEach(recorder::discard)
            _notices.emit("Couldn't send: ${e.message ?: "unknown error"}. Tap it to try again.")
        } catch (e: Exception) {
            // Put the message back so nothing typed is lost.
            val reason = e.message ?: "unknown error"
            putBack(text, files, separately = false, reason = reason, subject = subject)
            _notices.emit("Couldn't send: $reason")
        }
    }

    /** One text per person, each in its own one-to-one conversation; the group thread doesn't get a copy. */
    private suspend fun deliverSeparately(text: String, files: List<OutgoingAttachment>, sim: Int?, subject: String? = null) {
        var stored = false
        val failed = recipients.filter { person ->
            try {
                repo.send(listOf(person), text, files, sim, subject)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: StoredAsFailed) {
                // In that person's conversation, marked not sent.
                stored = true
                true
            } catch (e: Exception) {
                true
            }
        }
        if (failed.size == recipients.size && !stored) {
            putBack(text, files, separately = true, subject = subject)
            _notices.emit("Couldn't send")
        } else {
            if (container.isLive.value) files.forEach(recorder::discard)
            val sent = recipients.size - failed.size
            _notices.emit(
                when {
                    failed.isEmpty() -> "Sent separately to $sent people. Replies come back one to one."
                    sent == 0 -> "Couldn't send to ${failed.joinToString { repo.displayName(it) }}"
                    else -> "Sent to $sent; couldn't send to ${failed.joinToString { repo.displayName(it) }}"
                },
            )
        }
    }

    /** Schedules the draft. Attachments can't be scheduled (yet): MMS bodies aren't stored ahead of time. */
    /** Schedules the draft; false if it can't be (nothing to send, or an MMS). */
    fun schedule(sendAt: Long, label: String): Boolean {
        val text = currentDraft().trim()
        if (_attachments.value.isNotEmpty()) {
            _notices.tryEmit("Only text messages can be scheduled")
            return false
        }
        if (sendableSubject() != null) {
            _notices.tryEmit("A message with a subject goes as an MMS, which can't be scheduled. Remove the subject, or send it now.")
            return false
        }
        if (text.isEmpty()) return false
        markAnswered()
        draftField.clearText()
        // "Separately" holds for scheduled texts too: one per person, in their own conversations.
        val apart = _sendSeparately.value && recipients.size > 1
        _sendSeparately.value = false
        // The app's scope: the composer is cleared already, so leaving the screen now mustn't
        // cancel the save, or the text would be neither scheduled nor a draft.
        val thread = threadId.value
        val people = recipients
        val simAt = simForSend()
        container.appScope.launch {
            val sim = simAt()
            states.saveDraft(thread, "")
            if (apart) {
                people.forEach { person -> scheduler.schedule(repo.threadIdFor(listOf(person)), listOf(person), text, sendAt, sim) }
                _notices.emit("Scheduled for $label, to each person separately")
            } else {
                scheduler.schedule(thread, people, text, sendAt, sim)
                _notices.emit("Scheduled for $label")
            }
        }
        return true
    }

    /** Remembers [subscriptionId] as this conversation's SIM. */
    fun selectSim(subscriptionId: Int) {
        _selectedSim.value = subscriptionId
        simKnown = true
        launch { states.setSim(threadId.value, subscriptionId) }
    }

    /** In the app's scope: cut off after sending, it would send again at its time. */
    fun sendScheduledNow(id: Long) {
        container.appScope.launch { runCatching { scheduler.sendNow(id) }.onFailure { _notices.emit("Couldn't send it: ${it.message ?: it::class.simpleName}") } }
    }

    fun cancelScheduled(id: Long) = launch { scheduler.cancel(id) }

    fun rescheduleScheduled(id: Long, at: Long) = launch {
        // Gone already if it went out while the picker was open.
        if (scheduler.reschedule(id, at)) _notices.emit("Rescheduled for ${scheduleLabel(at)}")
    }

    /** Moves a scheduled message back into the composer. */
    fun editScheduled(message: ScheduledMessageEntity) = launch {
        scheduler.cancel(message.id)
        // Beside anything being typed, not over it.
        setDraft(listOf(currentDraft(), message.body).filter { it.isNotBlank() }.joinToString("\n"))
    }

    fun retry(message: ChatMessage) = launch { repo.retry(message) }

    fun delete(message: ChatMessage) = deleteMessages(listOf(message))

    /** Sends a reaction as text (`Loved “…”`), which iPhones show as a tapback and Winnow folds onto [message]. */
    fun react(message: ChatMessage, emoji: String) = launch {
        val attachment = message.attachments.firstOrNull()?.let { Tapback.attachmentName(it.contentType) } ?: "an attachment"
        val text = Tapback.compose(emoji, message.body, attachment)
        repo.send(recipients, text, subscriptionId = simForSend()())
    }

    /** A labeling that just happened, said with an Undo. */
    data class Labeled(val text: String, val undo: com.ericflo.winnow.classify.Labeler.Undo)

    private val _labeled = MutableSharedFlow<Labeled>(extraBufferCapacity = 4)
    val labeled: SharedFlow<Labeled> = _labeled

    private val actionPolicy = container.settings.settings.map { it.actionPolicy }
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.ericflo.winnow.classifier.message.ActionPolicy())

    /** Where each category files a message, by the user's settings (for the label sheet). */
    fun actionFor(category: com.ericflo.winnow.classifier.message.Category): com.ericflo.winnow.classifier.message.Action =
        actionPolicy.value.forCategory(category)

    /** Labels this conversation (its newest received messages) as [category]. */
    fun labelConversation(category: com.ericflo.winnow.classifier.message.Category) = launch {
        val result = container.appScope.async { container.labeler.labelConversations(listOf(threadId.value to recipients), category) }.await()
        val undo = result.undo ?: return@launch _notices.emit("Nothing received here to label yet")
        _labeled.emit(Labeled(com.ericflo.winnow.classify.Labeler.summary(category, result), undo))
    }

    /** Labels one received [message] as [category]. */
    fun labelMessage(message: ChatMessage, category: com.ericflo.winnow.classifier.message.Category) = launch {
        val all = state.value.messages
        val result = container.appScope.async { container.labeler.labelMessages(threadId.value, recipients, listOf(message), category, all) }.await()
        val undo = result.undo ?: return@launch
        _labeled.emit(Labeled(com.ericflo.winnow.classify.Labeler.summary(category, result), undo))
    }

    fun undoLabel(undo: com.ericflo.winnow.classify.Labeler.Undo) = launch {
        container.appScope.async { container.labeler.undo(undo) }.await()
    }

    private val _deleted = MutableSharedFlow<List<Trash.Item>>(extraBufferCapacity = 4)
    /** Messages just moved to Recently deleted, for an Undo. */
    val deleted: SharedFlow<List<Trash.Item>> = _deleted

    /**
     * Moves [messages] to Recently deleted (in the app's scope: leaving doesn't stop it halfway).
     * Their reminders are kept with them, and come back if they do.
     */
    /** Android holds Winnow back in the background (see AppContainer.restricted). */
    val restricted: StateFlow<Boolean> = container.restricted

    /** Names someone Winnow can't name itself (an RCS member): every list shows the name from then on. */
    fun nameSender(address: String, name: String?) {
        container.contacts.setGivenName(address, name)
    }

    fun givenName(address: String): String? = container.contacts.givenName(address)

    /** Why Winnow did what it did with the message [key] (see Provenance). */
    suspend fun explain(key: String) = container.provenance.explain(key)

    fun deleteMessages(messages: List<ChatMessage>) = launch {
        val kept = mutableListOf<Trash.Item>()
        for ((thread, some) in messages.groupBy { it.threadId }) {
            val result = container.appScope.async { container.trash.deleteMessages(thread, some) }.await()
            result.problem?.let {
                _notices.emit(it)
                return@launch
            }
            kept += result.items
        }
        if (kept.isNotEmpty()) _deleted.emit(kept)
    }

    /**
     * Deletes the conversation when [messages] are all of it (see [isEverything]), unless a text
     * came in since: then just [messages] go, and the conversation stays open.
     */
    private val _deleting = MutableStateFlow(false)
    /** The conversation is being deleted: a long one takes a while (kept in Recently deleted first). */
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    /**
     * The conversation was deleted from here: the screen leaves it the next time it's in front
     * (a long delete can end with the user elsewhere, when going back isn't allowed).
     */
    private val _gone = MutableStateFlow(false)
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    fun deleteEverything(messages: List<ChatMessage>, onDone: () -> Unit = {}) {
        val id = threadId.value
        val newest = messages.maxOfOrNull { it.timestamp } ?: return
        _deleting.value = true
        container.appScope.launch {
            try {
                val result = container.trash.delete(setOf(id), unlessNewerThan = newest)
                result.problem?.let {
                    _notices.emit(it)
                    return@launch
                }
                if (id in result.skipped) deleteMessages(messages) else { _gone.value = true; withContext(Dispatchers.Main) { onDone() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notices.emit("Couldn't delete: ${e.message ?: e::class.simpleName}")
            } finally {
                _deleting.value = false
            }
        }
    }

    /**
     * Whether deleting [messages] would leave the conversation empty: then it's the conversation
     * that goes (draft, pin and all, see [deleteConversation]), since Android drops an empty one.
     */
    fun isEverything(messages: Collection<ChatMessage>): Boolean {
        val all = state.value.messages
        return all.isNotEmpty() && messages.mapTo(HashSet()) { it.key }.containsAll(all.map { it.key })
    }

    /** Undo: what [deleteMessages] moved to Recently deleted, back in the conversation. */
    fun undoDelete(items: List<Trash.Item>) = launch {
        val back = container.appScope.async { items.map { container.trash.restore(it) } }.await()
        if (back.any { it?.complete == false }) _notices.emit("Some of it couldn't be put back; it's still in Recently deleted")
    }

    /** Stars all of [messages], or unstars them all when they already are. */
    fun setStarred(messages: List<ChatMessage>, starred: Boolean) = launch {
        val now = System.currentTimeMillis()
        messages.forEach { m ->
            if (starred && !m.starred) container.starredDao.star(StarredEntity(m.key, threadId.value, now))
            if (!starred && m.starred) container.starredDao.unstar(m.key)
        }
    }

    fun toggleStar(message: ChatMessage) = launch {
        if (message.starred) {
            container.starredDao.unstar(message.key)
        } else {
            container.starredDao.star(StarredEntity(message.key, threadId.value, System.currentTimeMillis()))
        }
    }

    fun allow() = launch { overrideAddress().takeIf { it.isNotBlank() }?.let { repo.overrideVerdict(threadId.value, it, Action.ALLOW) } }

    fun filter() = launch { overrideAddress().takeIf { it.isNotBlank() }?.let { repo.overrideVerdict(threadId.value, it, Action.FILTER) } }

    fun setBlocked(block: Boolean) = launch {
        val number = recipients.singleOrNull() ?: return@launch
        if (block) blockedNumbers.block(number) else blockedNumbers.unblock(number)
        _blocked.value = blockedNumbers.isBlocked(number)
        _notices.emit(if (_blocked.value) "Blocked. Android will drop their texts and calls." else "Unblocked")
    }

    fun setMuted(muted: Boolean, until: Long? = null) = launch { states.setMuted(threadId.value, muted, until) }

    fun setArchived(archived: Boolean) = launch { states.setArchived(setOf(threadId.value), archived) }

    /** In the app scope: deleting closes a conversation pane, and clears this ViewModel with it. */
    /** Asks the launcher to put this conversation on the home screen. */
    fun addToHomeScreen() = launch {
        val s = state.value
        val pinned = withContext(Dispatchers.IO) {
            container.notifier.pinToHomeScreen(threadId.value, recipients, s.title, recipients.singleOrNull()?.let { s.photos[it] })
        }
        if (!pinned) _notices.emit("Your home screen doesn't take shortcuts")
    }

    fun deleteConversation(onDone: () -> Unit = {}) {
        val id = threadId.value
        _deleting.value = true
        container.appScope.launch {
            try {
                // Kept in Recently deleted for 30 days first.
                container.trash.delete(setOf(id)).problem?.let {
                    _notices.emit(it)
                    return@launch
                }
                _gone.value = true
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notices.emit("Couldn't delete: ${e.message ?: e::class.simpleName}")
            } finally {
                _deleting.value = false
            }
        }
    }

    /** The sender a correction applies to: the newest incoming sender, or the first recipient. */
    private fun overrideAddress(): String =
        state.value.messages.lastOrNull { !it.outgoing }?.sender ?: recipients.firstOrNull().orEmpty()

    /** Runs a user action; a failure becomes a notice instead of a crash. */
    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notices.emit("Something went wrong: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    companion object {
        /** How far a tap follows an attachment's replacements (rotations and draft copies). */
        private const val MAX_KEPT_HOPS = 16
        /** An MMS subject's length: what phones and carriers show without cutting it. */
        const val MAX_SUBJECT = 40
        /** Messages looked at for suggested replies (the classifier reads the last few of them). */
        private const val SUGGESTION_CONTEXT = 20
        /** Less than this left for a video, and it would be a smudge. */
        private const val MIN_VIDEO_ROOM = 150_000L
    }
}

/**
 * The newest [n] of [messages] that aren't reactions, oldest first. Read from the end, and only
 * as far as needed: a long conversation's whole history isn't, and is costly to go through.
 */
internal fun latestSpoken(messages: List<ChatMessage>, n: Int): List<ChatMessage> =
    messages.asReversed().asSequence().filter { Tapback.parse(it.body) == null }.take(n).toList().asReversed()
