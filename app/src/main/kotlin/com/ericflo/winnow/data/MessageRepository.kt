package com.ericflo.winnow.data

import com.ericflo.winnow.classifier.message.Action
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest

/** An image or file chosen in the composer, not yet sent. */
data class OutgoingAttachment(val uri: String, val contentType: String, val name: String?)

/** What a correction replaced, so Undo can put it back exactly. */
data class PreviousVerdict(val threadId: Long, val address: String, val userAction: Action?, val senderRule: String?)

/**
 * The message store. Winnow-only state (pinned, archived, muted, drafts) lives in
 * [ConversationStateStore] and is merged in by the UI layer.
 */
interface MessageRepository {
    fun conversations(): Flow<List<ConversationSummary>>

    fun messages(threadId: Long): Flow<List<ChatMessage>>

    /** A contact name, or a formatted number. */
    fun displayName(address: String): String

    /** A contact's photo, if any. */
    fun photoUri(address: String): String? = null

    /** Emits when contacts change, so names and photos shown elsewhere can be read again. */
    fun contactChanges(): Flow<Unit> = emptyFlow()

    /** The thread for exactly these recipients, created if needed. */
    suspend fun threadIdFor(recipients: List<String>): Long

    /** SMS to one recipient without attachments; MMS otherwise. [subscriptionId] picks the SIM; null for the default. */
    suspend fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment> = emptyList(), subscriptionId: Int? = null)

    /** The messages with these keys (`sms:<id>`, `mms:<id>`) that still exist, with their conversations. */
    suspend fun messagesByKey(keys: Collection<String>): List<StarredMessage> = emptyList()

    /** The SIM the thread's newest incoming message arrived on, if the store knows. */
    suspend fun lastIncomingSubscription(threadId: Long): Int? = null

    /** Re-sends a failed outgoing message. */
    suspend fun retry(message: ChatMessage)

    suspend fun markRead(threadId: Long)

    /** Keys of the thread's unread incoming messages, oldest first: where "new messages" begin. */
    suspend fun unreadIncoming(threadId: Long): List<String> = emptyList()

    suspend fun markUnread(threadId: Long)

    suspend fun markAllRead()

    suspend fun deleteThreads(threadIds: Collection<Long>)

    /**
     * Deletes [threadId]'s messages up to these ids, with Winnow's records of them. Anything
     * newer (a text that arrived after the rest were kept in Recently deleted) stays.
     */
    suspend fun deleteThreadUpTo(threadId: Long, smsUpTo: Long, mmsUpTo: Long) = deleteThreads(listOf(threadId))

    /** Conversations as they're deleted, from whichever screen: one open beside the list closes. */
    fun deletedThreads(): Flow<Set<Long>> = emptyFlow()

    suspend fun deleteMessage(message: ChatMessage)

    suspend fun search(query: String): List<SearchHit>

    /** Who a conversation is with, by its thread; empty if there's no such thread. */
    suspend fun recipientsFor(threadId: Long): List<String> = emptyList()

    /** The newest photos and videos across every conversation. */
    suspend fun recentMedia(limit: Int = 240): List<MediaHit> = emptyList()

    /** The newest texts that look like they carry a link, across every conversation. */
    suspend fun textsWithLinks(limit: Int = 500): List<SearchHit> = emptyList()

    /** Records the user's correction for a thread and remembers it for the sender. */
    suspend fun overrideVerdict(threadId: Long, address: String, action: Action): PreviousVerdict

    /** Undoes [overrideVerdict]: the earlier correction and sender rule come back, and what was learned is unlearned. */
    suspend fun restoreVerdict(previous: PreviousVerdict)

    /** Every decision Winnow has recorded. */
    fun verdictRecords(): Flow<List<VerdictRecord>>
}

/** "Mom" for one recipient; "Alex, Sam, (555) 555-0199" for a group, using first names where known. */
fun displayNameFor(recipients: List<String>, single: (String) -> String): String = when (recipients.size) {
    0 -> "(no recipients)"
    1 -> single(recipients.first())
    else -> recipients.joinToString(", ") { address ->
        val name = single(address)
        if (name.first().isLetter()) name.substringBefore(' ') else name
    }
}

/**
 * Serves the real SMS store once Winnow can read it, and sample conversations before that
 * (fresh install, emulator, screenshots).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingMessageRepository(
    private val live: MessageRepository,
    private val demo: MessageRepository,
    private val isLive: StateFlow<Boolean>,
) : MessageRepository {
    private val current: MessageRepository get() = if (isLive.value) live else demo

    override fun conversations() = isLive.flatMapLatest { if (it) live.conversations() else demo.conversations() }
    override fun messages(threadId: Long) = isLive.flatMapLatest { if (it) live.messages(threadId) else demo.messages(threadId) }
    override fun displayName(address: String) = current.displayName(address)
    override fun photoUri(address: String) = current.photoUri(address)
    override fun contactChanges() = isLive.flatMapLatest { if (it) live.contactChanges() else emptyFlow() }
    override suspend fun threadIdFor(recipients: List<String>) = current.threadIdFor(recipients)
    override suspend fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment>, subscriptionId: Int?) =
        current.send(recipients, body, attachments, subscriptionId)
    override suspend fun lastIncomingSubscription(threadId: Long) = current.lastIncomingSubscription(threadId)
    override suspend fun messagesByKey(keys: Collection<String>) = current.messagesByKey(keys)
    override suspend fun retry(message: ChatMessage) = current.retry(message)
    override suspend fun markRead(threadId: Long) = current.markRead(threadId)
    override suspend fun unreadIncoming(threadId: Long) = current.unreadIncoming(threadId)
    override suspend fun markUnread(threadId: Long) = current.markUnread(threadId)
    override suspend fun markAllRead() = current.markAllRead()
    private val deleted = MutableSharedFlow<Set<Long>>(extraBufferCapacity = 8)
    override fun deletedThreads(): Flow<Set<Long>> = deleted
    override suspend fun deleteThreads(threadIds: Collection<Long>) {
        current.deleteThreads(threadIds)
        deleted.emit(threadIds.toSet())
    }
    override suspend fun deleteThreadUpTo(threadId: Long, smsUpTo: Long, mmsUpTo: Long) {
        current.deleteThreadUpTo(threadId, smsUpTo, mmsUpTo)
        deleted.emit(setOf(threadId))
    }
    override suspend fun deleteMessage(message: ChatMessage) = current.deleteMessage(message)
    override suspend fun search(query: String) = current.search(query)
    override suspend fun recentMedia(limit: Int) = current.recentMedia(limit)
    override suspend fun recipientsFor(threadId: Long) = current.recipientsFor(threadId)
    override suspend fun textsWithLinks(limit: Int) = current.textsWithLinks(limit)
    override suspend fun overrideVerdict(threadId: Long, address: String, action: Action) =
        current.overrideVerdict(threadId, address, action)
    override suspend fun restoreVerdict(previous: PreviousVerdict) = current.restoreVerdict(previous)
    override fun verdictRecords() = isLive.flatMapLatest { if (it) live.verdictRecords() else demo.verdictRecords() }
}
