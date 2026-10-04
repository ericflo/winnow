package com.ericflo.winnow.data

import com.ericflo.winnow.data.db.ConversationStateDao
import com.ericflo.winnow.data.db.ConversationStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

/** Pinned, archived, muted and draft state per thread, merged into [ConversationSummary]s. */
class ConversationStateStore(private val dao: ConversationStateDao) {
    private val lock = Mutex()

    fun observe(): Flow<Map<Long, ConversationStateEntity>> = dao.observeAll().map { rows -> rows.associateBy { it.threadId } }

    /**
     * [observe], re-emitted every minute, for screens that show whether a conversation is muted:
     * a timed mute ends by the clock, with nothing in the database changing.
     */
    fun observeTimed(): Flow<Map<Long, ConversationStateEntity>> = combine(observe(), minuteTicks()) { states, _ -> states }

    private fun minuteTicks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    suspend fun get(threadId: Long): ConversationStateEntity = dao.get(threadId) ?: ConversationStateEntity(threadId)

    suspend fun all(): List<ConversationStateEntity> = dao.all()

    suspend fun setPinned(threadIds: Collection<Long>, pinned: Boolean) = updateAll(threadIds) { it.copy(pinned = pinned) }

    suspend fun setArchived(threadIds: Collection<Long>, archived: Boolean) =
        updateAll(threadIds) { it.copy(archived = archived, pinned = if (archived) false else it.pinned) }

    /** Brings an archived thread back to the inbox; a no-op (and no write) otherwise. */
    suspend fun unarchive(threadId: Long) {
        if (dao.get(threadId)?.archived == true) setArchived(listOf(threadId), false)
    }

    /** Mutes until [until] (epoch millis), or until turned off when null; unmuting clears both. */
    suspend fun setMuted(threadId: Long, muted: Boolean, until: Long? = null) =
        updateAll(listOf(threadId)) { it.copy(muted = muted, mutedUntil = until.takeIf { muted }) }

    suspend fun saveDraft(threadId: Long, draft: String) =
        updateAll(listOf(threadId)) { it.copy(draft = draft.takeIf(String::isNotBlank)) }

    suspend fun saveDraftAttachments(threadId: Long, encoded: String?) =
        updateAll(listOf(threadId)) { it.copy(draftAttachments = encoded) }

    suspend fun setTitle(threadId: Long, title: String?) =
        updateAll(listOf(threadId)) { it.copy(title = title?.trim()?.takeIf(String::isNotEmpty)) }

    suspend fun setSim(threadId: Long, subscriptionId: Int?) = updateAll(listOf(threadId)) { it.copy(subscriptionId = subscriptionId) }

    suspend fun forget(threadIds: Collection<Long>) = dao.delete(threadIds)

    private suspend fun updateAll(threadIds: Collection<Long>, transform: (ConversationStateEntity) -> ConversationStateEntity) {
        lock.withLock { threadIds.forEach { id -> dao.upsert(transform(get(id))) } }
    }
}

/** Applies Winnow-only state; pinned conversations sort first, then by recency. */
fun List<ConversationSummary>.withState(states: Map<Long, ConversationStateEntity>): List<ConversationSummary> =
    map { c ->
        val s = states[c.threadId] ?: return@map c
        val draft = s.draft ?: com.ericflo.winnow.data.DraftAttachments.summary(s.draftAttachments)
        c.copy(pinned = s.pinned, archived = s.archived, muted = s.isMuted(), draft = draft, displayName = s.title ?: c.displayName)
    }.sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.timestamp })
