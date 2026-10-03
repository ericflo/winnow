package com.ericflo.winnow.data

import com.ericflo.winnow.classifier.message.Action
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest

/** An image or file chosen in the composer, not yet sent. */
data class OutgoingAttachment(val uri: String, val contentType: String, val name: String?)

/**
 * The message store. Winnow-only state (pinned, archived, muted, drafts) lives in
 * [ConversationStateStore] and is merged in by the UI layer.
 */
interface MessageRepository {
    fun conversations(): Flow<List<ConversationSummary>>

    fun messages(threadId: Long): Flow<List<ChatMessage>>

    /** A contact name, or a formatted number. */
    fun displayName(address: String): String

    /** The thread for exactly these recipients, created if needed. */
    suspend fun threadIdFor(recipients: List<String>): Long

    /** SMS to one recipient without attachments; MMS otherwise. */
    suspend fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment> = emptyList())

    /** Re-sends a failed outgoing message. */
    suspend fun retry(message: ChatMessage)

    suspend fun markRead(threadId: Long)

    suspend fun markUnread(threadId: Long)

    suspend fun markAllRead()

    suspend fun deleteThreads(threadIds: Collection<Long>)

    suspend fun deleteMessage(message: ChatMessage)

    suspend fun search(query: String): List<SearchHit>

    /** Records the user's correction for a thread and remembers it for the sender. */
    suspend fun overrideVerdict(threadId: Long, address: String, action: Action)
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
    override suspend fun threadIdFor(recipients: List<String>) = current.threadIdFor(recipients)
    override suspend fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment>) =
        current.send(recipients, body, attachments)
    override suspend fun retry(message: ChatMessage) = current.retry(message)
    override suspend fun markRead(threadId: Long) = current.markRead(threadId)
    override suspend fun markUnread(threadId: Long) = current.markUnread(threadId)
    override suspend fun markAllRead() = current.markAllRead()
    override suspend fun deleteThreads(threadIds: Collection<Long>) = current.deleteThreads(threadIds)
    override suspend fun deleteMessage(message: ChatMessage) = current.deleteMessage(message)
    override suspend fun search(query: String) = current.search(query)
    override suspend fun overrideVerdict(threadId: Long, address: String, action: Action) =
        current.overrideVerdict(threadId, address, action)
}
