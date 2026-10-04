package com.ericflo.winnow.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.OutgoingAttachment
import com.ericflo.winnow.data.SimCard
import com.ericflo.winnow.data.Tapback
import com.ericflo.winnow.data.StoredVerdict
import com.ericflo.winnow.data.db.ScheduledMessageEntity
import com.ericflo.winnow.data.db.StarredEntity
import com.ericflo.winnow.data.displayNameFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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
import com.ericflo.winnow.data.normalizeAddress
import kotlinx.coroutines.CompletableDeferred

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
) {
    val isGroup: Boolean get() = recipients.size > 1
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
    private val repo = container.messages
    private val states = container.conversationStates
    private val threadId = MutableStateFlow(initialThreadId)
    private val title = displayNameFor(recipients, repo::displayName)
    private val subtitle = when {
        recipients.size > 1 -> "${recipients.size + 1} people"
        else -> recipients.singleOrNull()?.let(ContactLookup::formatAddress)?.takeIf { it != title }
    }

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

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

    val enterToSend: StateFlow<Boolean> = container.settings.settings.map { it.enterToSend }
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

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off messages for a snackbar. */
    val notices: SharedFlow<String> = _notices

    init {
        viewModelScope.launch {
            recipients.singleOrNull()?.let { _blocked.value = blockedNumbers.isBlocked(it) }
        }
        viewModelScope.launch {
            if (threadId.value < 0) threadId.value = repo.threadIdFor(recipients)
            val id = threadId.value
            // Read before marking read: where this visit's "new messages" begin.
            if (id >= 0) _unreadOnOpen.value = runCatching { repo.unreadIncoming(id) }.getOrDefault(emptyList())
            unreadCaptured.complete(Unit)
            repo.markRead(id)
            if (!inBubble) container.notifier.cancel(id)
            _sims.value = container.sims.available().takeIf { it.size >= 2 }.orEmpty()
            _selectedSim.value = container.simFor(id)
            states.get(id).draft?.let { saved -> if (_draft.value.isEmpty()) _draft.value = saved }
            _draft.drop(1).debounce(400).collect { states.saveDraft(id, it) }
        }
    }

    val state: StateFlow<ThreadUiState> = threadId
        .filter { it >= 0 }
        .flatMapLatest { id ->
            combine(repo.messages(id), states.observeTimed().map { it[id] }, container.starredDao.observeKeys(id)) { messages, s, starredKeys ->
                val stars = starredKeys.toSet()
                ThreadUiState(
                    title = s?.title ?: title,
                    subtitle = subtitle,
                    recipients = recipients,
                    messages = if (stars.isEmpty()) messages else messages.map { if (it.key in stars) it.copy(starred = true) else it },
                    verdict = messages.lastOrNull { !it.outgoing }?.verdict,
                    senderNames = messages.mapNotNull { it.sender }.distinct().associateWith(repo::displayName),
                    photos = (recipients + messages.mapNotNull { it.sender }).distinct()
                        .mapNotNull { address -> repo.photoUri(address)?.let { address to it } }.toMap(),
                    muted = s?.isMuted() == true,
                    mutedUntil = s?.takeIf { it.isMuted() }?.mutedUntil,
                    archived = s?.archived == true,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState(title, subtitle, recipients))

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun preview(url: String): LinkPreview? = container.linkPreviews.get(url)

    /** The real thread id, once a new conversation's thread has been created; negative before. */
    fun currentThreadId(): Long = threadId.value

    /**
     * While the conversation is on screen, incoming texts for it don't notify (see
     * IncomingMessageHandler) and coming back to it marks it read.
     */
    fun setVisible(visible: Boolean) {
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

    /** Leaving within the debounce window would drop the last keystrokes; save whatever is there. */
    override fun onCleared() {
        val id = threadId.value
        if (id >= 0) {
            val draft = _draft.value
            container.appScope.launch { states.saveDraft(id, draft) }
        }
        if (container.visibleThread.value == id) container.visibleThread.value = null
    }

    fun setDraft(value: String) {
        _draft.value = value
    }

    fun addAttachment(attachment: OutgoingAttachment) {
        _attachments.value = _attachments.value + attachment
    }

    fun newCameraPhoto() = container.sharedFiles.newCameraPhoto()

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

    fun removeAttachment(attachment: OutgoingAttachment) {
        _attachments.value = _attachments.value - attachment
    }

    /** A sent message waiting out the undo window; null when nothing is pending. */
    data class PendingSend(val text: String, val attachments: List<OutgoingAttachment>, val sendsAt: Long, val windowMillis: Long)

    private val _pending = MutableStateFlow<PendingSend?>(null)
    val pending: StateFlow<PendingSend?> = _pending.asStateFlow()
    private var pendingJob: Job? = null

    fun send() {
        val text = _draft.value.trim()
        val files = _attachments.value
        if (text.isEmpty() && files.isEmpty() || _pending.value != null) return
        // Replying means the new messages have been read; the divider has done its job.
        _unreadOnOpen.value = emptyList()
        _draft.value = ""
        _attachments.value = emptyList()
        val sim = _selectedSim.value
        viewModelScope.launch {
            states.saveDraft(threadId.value, "")
            val window = container.settings.current().undoSendSeconds * 1000L
            if (window <= 0) return@launch deliver(text, files, sim)
            _pending.value = PendingSend(text, files, System.currentTimeMillis() + window, window)
            // The app scope, not this ViewModel's: leaving the conversation must not lose the message.
            pendingJob = container.appScope.launch {
                delay(window)
                _pending.value = null
                deliver(text, files, sim)
            }
        }
    }

    /** Cancels a message still inside its undo window and puts it back in the composer. */
    fun undoSend() {
        val pending = _pending.value ?: return
        if (pendingJob?.isActive != true) return
        pendingJob?.cancel()
        _pending.value = null
        _draft.value = pending.text
        _attachments.value = pending.attachments
    }

    private suspend fun deliver(text: String, files: List<OutgoingAttachment>, sim: Int?) {
        try {
            repo.send(recipients, text, files, sim)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Put the message back so nothing typed is lost.
            _draft.value = text
            _attachments.value = files
            _notices.emit("Couldn't send: ${e.message ?: "unknown error"}")
        }
    }

    /** Schedules the draft. Attachments can't be scheduled (yet): MMS bodies aren't stored ahead of time. */
    fun schedule(sendAt: Long, label: String) {
        val text = _draft.value.trim()
        if (_attachments.value.isNotEmpty()) {
            _notices.tryEmit("Only text messages can be scheduled")
            return
        }
        if (text.isEmpty()) return
        _draft.value = ""
        viewModelScope.launch {
            states.saveDraft(threadId.value, "")
            scheduler.schedule(threadId.value, recipients, text, sendAt, _selectedSim.value)
            _notices.emit("Scheduled for $label")
        }
    }

    /** Remembers [subscriptionId] as this conversation's SIM. */
    fun selectSim(subscriptionId: Int) {
        _selectedSim.value = subscriptionId
        launch { states.setSim(threadId.value, subscriptionId) }
    }

    fun sendScheduledNow(id: Long) = launch { scheduler.sendNow(id) }

    fun cancelScheduled(id: Long) = launch { scheduler.cancel(id) }

    /** Moves a scheduled message back into the composer. */
    fun editScheduled(message: ScheduledMessageEntity) = launch {
        scheduler.cancel(message.id)
        _draft.value = message.body
    }

    fun retry(message: ChatMessage) = launch { repo.retry(message) }

    fun delete(message: ChatMessage) = launch { repo.deleteMessage(message) }

    /** Sends a reaction as text (`Loved “…”`), which iPhones show as a tapback and Winnow folds onto [message]. */
    fun react(message: ChatMessage, emoji: String) = launch {
        val attachment = message.attachments.firstOrNull()?.let { Tapback.attachmentName(it.contentType) } ?: "an attachment"
        val text = Tapback.compose(emoji, message.body, attachment)
        repo.send(recipients, text, subscriptionId = _selectedSim.value)
    }

    fun deleteMessages(messages: List<ChatMessage>) = launch { messages.forEach { repo.deleteMessage(it) } }

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

    fun deleteConversation(onDone: () -> Unit) = launch {
        val id = threadId.value
        repo.deleteThreads(setOf(id))
        states.forget(setOf(id))
        container.notifier.forget(setOf(id))
        onDone()
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
}
