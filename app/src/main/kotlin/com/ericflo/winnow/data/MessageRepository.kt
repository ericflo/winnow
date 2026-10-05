package com.ericflo.winnow.data

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.data.db.CorrectionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

/** An image or file chosen in the composer, not yet sent. */
data class OutgoingAttachment(val uri: String, val contentType: String, val name: String?)

/** What a correction replaced, so Undo can put it back exactly. */
data class PreviousVerdict(
    val threadId: Long,
    val address: String,
    val userAction: Action?,
    val senderRule: String?,
    /** A verdict the correction had to add (its newest text was never classified); Undo takes it away. */
    val insertedKey: String? = null,
    /** Each of the conversation's verdicts as the user had left it: labels set them one message at a time. */
    val rows: List<UserState> = emptyList(),
    /** Labels the correction contradicted, and so replaced, in what the model learned. */
    val labels: List<CorrectionEntity> = emptyList(),
) {
    data class UserState(val messageKey: String, val userAction: String?, val userCategory: String?)
}

/**
 * The message store. Winnow-only state (pinned, archived, muted, drafts) lives in
 * [ConversationStateStore] and is merged in by the UI layer.
 */
interface MessageRepository {
    fun conversations(): Flow<List<ConversationSummary>>

    fun messages(threadId: Long): Flow<List<ChatMessage>>

    /**
     * A conversation's messages as they are now, without verdicts: a one-off read, cheaper than
     * [messages]. In a group, who sent each received MMS is asked of the store one at a time, so a
     * caller that only needs the newest can say so with [newestSenders]: older ones may then come
     * without a sender.
     */
    suspend fun messagesNow(threadId: Long, newestSenders: Int = Int.MAX_VALUE): List<ChatMessage>

    /** A contact name, or a formatted number. */
    fun displayName(address: String): String

    /** The contact's name, or null if [address] isn't a contact: never decided by comparing formatted strings. */
    fun contactName(address: String): String? = null

    /** A contact's photo, if any. */
    fun photoUri(address: String): String? = null

    /** Emits when contacts change, so names and photos shown elsewhere can be read again. */
    fun contactChanges(): Flow<Unit> = emptyFlow()

    /** The thread for exactly these recipients, created if needed. */
    suspend fun threadIdFor(recipients: List<String>): Long

    /** SMS to one recipient without attachments; MMS otherwise. [subscriptionId] picks the SIM; null for the default. */
    /** [subject], when given, makes it an MMS with that subject, even to one person with nothing attached. */
    suspend fun send(
        recipients: List<String>,
        body: String,
        attachments: List<OutgoingAttachment> = emptyList(),
        subscriptionId: Int? = null,
        subject: String? = null,
    )

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
     * newer (a text that arrived after the rest were kept in Recently deleted) stays. True if
     * the conversation is gone; false if such a text kept it.
     */
    suspend fun deleteThreadUpTo(threadId: Long, smsUpTo: Long, mmsUpTo: Long): Boolean {
        deleteThreads(listOf(threadId))
        return true
    }

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
 * Serves the real SMS store once Winnow can read it, and nothing before that (see
 * NoAccessMessageRepository).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingMessageRepository(
    private val live: MessageRepository,
    private val demo: MessageRepository,
    private val isLive: StateFlow<Boolean>,
    /** Where the one conversation list everyone shares is kept (see [conversations]). */
    scope: kotlinx.coroutines.CoroutineScope? = null,
) : MessageRepository {
    private val current: MessageRepository get() = if (isLive.value) live else demo

    private val list = isLive.flatMapLatest { if (it) live.conversations() else demo.conversations() }

    // One list for every screen and job that reads it (the inbox, Filtered, the widget, Train, a
    // backlog run): one query of the whole store per change, not one each, and the newest list
    // handed over at once to whoever asks while another is reading it.
    private val shared = scope?.let { list.sharedWhileWatched(it) } ?: list

    override fun conversations() = shared
    override fun messages(threadId: Long) = isLive.flatMapLatest { if (it) live.messages(threadId) else demo.messages(threadId) }
    override suspend fun messagesNow(threadId: Long, newestSenders: Int) = current.messagesNow(threadId, newestSenders)
    override fun displayName(address: String) = current.displayName(address)
    override fun photoUri(address: String) = current.photoUri(address)
    override fun contactName(address: String) = current.contactName(address)
    override fun contactChanges() = isLive.flatMapLatest { if (it) live.contactChanges() else emptyFlow() }
    override suspend fun threadIdFor(recipients: List<String>) = current.threadIdFor(recipients)
    override suspend fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment>, subscriptionId: Int?, subject: String?) =
        current.send(recipients, body, attachments, subscriptionId, subject)
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
    override suspend fun deleteThreadUpTo(threadId: Long, smsUpTo: Long, mmsUpTo: Long): Boolean =
        current.deleteThreadUpTo(threadId, smsUpTo, mmsUpTo).also { gone -> if (gone) deleted.emit(setOf(threadId)) }
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

/**
 * One reading of this flow for everyone watching it, the newest value handed at once to whoever
 * starts watching while it runs; and when no one has watched for a few seconds, stopped, its
 * value forgotten, so a later watcher waits for a fresh one instead of getting what was true
 * when the last one left (hours ago, perhaps). Pure, so it's unit-tested.
 */
internal fun <T> Flow<T>.sharedWhileWatched(scope: kotlinx.coroutines.CoroutineScope): Flow<T> =
    shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0), replay = 1)

