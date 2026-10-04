package com.ericflo.winnow.backup

import android.util.Log
import com.ericflo.winnow.data.ConversationStateStore
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.db.StarredDao
import com.ericflo.winnow.data.withState
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Opt-in (Settings → Filtering): filtered conversations nobody has touched in a month go to
 * Recently deleted, where they can still be restored for 30 days. Never one that's pinned, has a
 * starred message, or that the user has written in (a correction to make there, not clutter).
 */
class FilteredCleaner(
    private val context: android.content.Context,
    private val repo: MessageRepository,
    private val states: ConversationStateStore,
    private val starred: StarredDao,
    private val trash: Trash,
    /** Deleting needs the SMS role, and the user's say-so. */
    private val enabled: suspend () -> Boolean,
) {
    suspend fun clean(now: Long = System.currentTimeMillis()): Int {
        if (!enabled()) return 0
        val written = withContext(Dispatchers.IO) { threadsWithOutgoing() }
        val stale = repo.conversations().first()
            .withState(states.all().associateBy { it.threadId })
            .filter { it.isFiltered && !it.pinned && it.timestamp < now - MAX_AGE_MILLIS }
            .map { it.threadId }
            // The user's own words in it: kept, whatever the newest message is.
            .filter { it !in written && starred.keysForThread(it).isEmpty() }
            .toSet()
        if (stale.isEmpty()) return 0
        val deleted = trash.delete(stale)
        Log.i(TAG, "Moved ${stale.size} old filtered conversations to Recently deleted${deleted.problem?.let { " ($it)" }.orEmpty()}")
        return stale.size
    }

    /** Conversations the user has sent something in. */
    private fun threadsWithOutgoing(): Set<Long> {
        val threads = HashSet<Long>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_INBOX}", null, null,
        )?.use { c -> while (c.moveToNext()) threads += c.getLong(0) }
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.THREAD_ID), "${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_INBOX}", null, null,
        )?.use { c -> while (c.moveToNext()) threads += c.getLong(0) }
        return threads
    }

    private companion object {
        const val TAG = "WinnowFiltered"
        const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60_000
    }
}
