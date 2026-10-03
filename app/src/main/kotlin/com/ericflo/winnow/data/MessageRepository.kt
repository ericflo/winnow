package com.ericflo.winnow.data

import com.ericflo.winnow.classifier.message.Action
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest

interface MessageRepository {
    fun conversations(): Flow<List<ConversationSummary>>

    fun messages(threadId: Long): Flow<List<ChatMessage>>

    fun displayName(address: String): String

    /** The thread for [address], created if needed. */
    suspend fun threadIdFor(address: String): Long

    suspend fun send(address: String, body: String)

    suspend fun markRead(threadId: Long)

    suspend fun markAllRead()

    /** Records the user's correction for a thread and remembers it for the sender. */
    suspend fun overrideVerdict(threadId: Long, address: String, action: Action)
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
    override suspend fun threadIdFor(address: String) = current.threadIdFor(address)
    override suspend fun send(address: String, body: String) = current.send(address, body)
    override suspend fun markRead(threadId: Long) = current.markRead(threadId)
    override suspend fun markAllRead() = current.markAllRead()
    override suspend fun overrideVerdict(threadId: Long, address: String, action: Action) =
        current.overrideVerdict(threadId, address, action)
}
