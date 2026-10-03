package com.ericflo.winnow.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.OutgoingAttachment
import com.ericflo.winnow.data.StoredVerdict
import com.ericflo.winnow.data.db.ScheduledMessageEntity
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
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

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
    val archived: Boolean = false,
) {
    val isGroup: Boolean get() = recipients.size > 1
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ThreadViewModel(
    private val container: AppContainer,
    initialThreadId: Long,
    private val recipients: List<String>,
) : ViewModel() {
    private val repo = container.messages
    private val states = container.conversationStates
    private val threadId = MutableStateFlow(initialThreadId)
    private val title = displayNameFor(recipients, repo::displayName)
    private val subtitle = when {
        recipients.size > 1 -> "${recipients.size + 1} people"
        else -> ContactLookup.formatAddress(recipients.single()).takeIf { it != title }
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
            repo.markRead(id)
            container.notifier.cancel(id)
            states.get(id).draft?.let { saved -> if (_draft.value.isEmpty()) _draft.value = saved }
            _draft.drop(1).debounce(400).collect { states.saveDraft(id, it) }
        }
    }

    val state: StateFlow<ThreadUiState> = threadId
        .filter { it >= 0 }
        .flatMapLatest { id ->
            combine(repo.messages(id), states.observe().map { it[id] }) { messages, s ->
                ThreadUiState(
                    title = title,
                    subtitle = subtitle,
                    recipients = recipients,
                    messages = messages,
                    verdict = messages.lastOrNull { !it.outgoing }?.verdict,
                    senderNames = messages.mapNotNull { it.sender }.distinct().associateWith(repo::displayName),
                    photos = (recipients + messages.mapNotNull { it.sender }).distinct()
                        .mapNotNull { address -> repo.photoUri(address)?.let { address to it } }.toMap(),
                    muted = s?.muted == true,
                    archived = s?.archived == true,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState(title, subtitle, recipients))

    /** The real thread id, once a new conversation's thread has been created; negative before. */
    fun currentThreadId(): Long = threadId.value

    fun setDraft(value: String) {
        _draft.value = value
    }

    fun addAttachment(attachment: OutgoingAttachment) {
        _attachments.value = _attachments.value + attachment
    }

    fun removeAttachment(attachment: OutgoingAttachment) {
        _attachments.value = _attachments.value - attachment
    }

    fun send() {
        val text = _draft.value.trim()
        val files = _attachments.value
        if (text.isEmpty() && files.isEmpty()) return
        _draft.value = ""
        _attachments.value = emptyList()
        viewModelScope.launch {
            states.saveDraft(threadId.value, "")
            try {
                repo.send(recipients, text, files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Put the message back so nothing typed is lost.
                _draft.value = text
                _attachments.value = files
                _notices.emit("Couldn't send: ${e.message ?: "unknown error"}")
            }
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
            scheduler.schedule(threadId.value, recipients, text, sendAt)
            _notices.emit("Scheduled for $label")
        }
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

    fun allow() = launch { repo.overrideVerdict(threadId.value, overrideAddress(), Action.ALLOW) }

    fun filter() = launch { repo.overrideVerdict(threadId.value, overrideAddress(), Action.FILTER) }

    fun setBlocked(block: Boolean) = launch {
        val number = recipients.singleOrNull() ?: return@launch
        if (block) blockedNumbers.block(number) else blockedNumbers.unblock(number)
        _blocked.value = blockedNumbers.isBlocked(number)
        _notices.emit(if (_blocked.value) "Blocked. Android will drop their texts and calls." else "Unblocked")
    }

    fun setMuted(muted: Boolean) = launch { states.setMuted(threadId.value, muted) }

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
        state.value.messages.lastOrNull { !it.outgoing }?.sender ?: recipients.first()

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
