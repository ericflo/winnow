package com.ericflo.winnow.data

import com.ericflo.winnow.data.db.ConversationStateDao
import com.ericflo.winnow.data.db.ConversationStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pinned, archived, muted and draft state per thread, merged into [ConversationSummary]s. */
class ConversationStateStore(private val dao: ConversationStateDao) {
    private val lock = Mutex()

    fun observe(): Flow<Map<Long, ConversationStateEntity>> = dao.observeAll().map { rows -> rows.associateBy { it.threadId } }

    suspend fun get(threadId: Long): ConversationStateEntity = dao.get(threadId) ?: ConversationStateEntity(threadId)

    suspend fun setPinned(threadIds: Collection<Long>, pinned: Boolean) = updateAll(threadIds) { it.copy(pinned = pinned) }

    suspend fun setArchived(threadIds: Collection<Long>, archived: Boolean) =
        updateAll(threadIds) { it.copy(archived = archived, pinned = if (archived) false else it.pinned) }

    /** Brings an archived thread back to the inbox; a no-op (and no write) otherwise. */
    suspend fun unarchive(threadId: Long) {
        if (dao.get(threadId)?.archived == true) setArchived(listOf(threadId), false)
    }

    suspend fun setMuted(threadId: Long, muted: Boolean) = updateAll(listOf(threadId)) { it.copy(muted = muted) }

    suspend fun saveDraft(threadId: Long, draft: String) =
        updateAll(listOf(threadId)) { it.copy(draft = draft.takeIf(String::isNotBlank)) }

    suspend fun forget(threadIds: Collection<Long>) = dao.delete(threadIds)

    private suspend fun updateAll(threadIds: Collection<Long>, transform: (ConversationStateEntity) -> ConversationStateEntity) {
        lock.withLock { threadIds.forEach { id -> dao.upsert(transform(get(id))) } }
    }
}

/** Applies Winnow-only state; pinned conversations sort first, then by recency. */
fun List<ConversationSummary>.withState(states: Map<Long, ConversationStateEntity>): List<ConversationSummary> =
    map { c ->
        val s = states[c.threadId] ?: return@map c
        c.copy(pinned = s.pinned, archived = s.archived, muted = s.muted, draft = s.draft)
    }.sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.timestamp })
