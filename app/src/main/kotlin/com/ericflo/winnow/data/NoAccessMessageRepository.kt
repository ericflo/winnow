package com.ericflo.winnow.data

import com.ericflo.winnow.classifier.message.Action
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the app shows before it's the default SMS app, when Android won't let it read texts:
 * nothing. Never sample conversations, which looked like someone else's messages on a fresh
 * install. Once Winnow is the default SMS app, the real store (every existing text included)
 * takes over by itself.
 */
class NoAccessMessageRepository : MessageRepository {
    override fun conversations(): Flow<List<ConversationSummary>> = flowOf(emptyList())

    override fun messages(threadId: Long): Flow<List<ChatMessage>> = flowOf(emptyList())

    override suspend fun messagesNow(threadId: Long): List<ChatMessage> = emptyList()

    override fun displayName(address: String): String = ContactLookup.formatAddress(address)

    override fun contactName(address: String): String? = null

    override suspend fun threadIdFor(recipients: List<String>): Long = -1

    override suspend fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment>, subscriptionId: Int?, subject: String?) =
        throw IllegalStateException(NOT_DEFAULT)

    override suspend fun messagesByKey(keys: Collection<String>): List<StarredMessage> = emptyList()

    override suspend fun retry(message: ChatMessage) = throw IllegalStateException(NOT_DEFAULT)

    override suspend fun markRead(threadId: Long) = Unit

    override suspend fun markUnread(threadId: Long) = Unit

    override suspend fun markAllRead() = Unit

    override suspend fun deleteThreads(threadIds: Collection<Long>) = Unit

    override suspend fun deleteMessage(message: ChatMessage) = Unit

    override suspend fun search(query: String): List<SearchHit> = emptyList()

    override suspend fun overrideVerdict(threadId: Long, address: String, action: Action): PreviousVerdict =
        PreviousVerdict(threadId, address, userAction = null, senderRule = null)

    override suspend fun restoreVerdict(previous: PreviousVerdict) = Unit

    override fun verdictRecords(): Flow<List<VerdictRecord>> = flowOf(emptyList())

    private companion object {
        const val NOT_DEFAULT = "Make Winnow your default SMS app to send texts"
    }
}
