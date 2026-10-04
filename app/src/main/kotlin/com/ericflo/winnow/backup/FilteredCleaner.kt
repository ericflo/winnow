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
    /** When this process last looked: once a day is plenty for a month-old cutoff. */
    @Volatile private var lastRun = 0L

    suspend fun clean(now: Long = System.currentTimeMillis(), force: Boolean = false): Int {
        if (!enabled()) return 0
        if (!force && now - lastRun < DAY_MILLIS) return 0
        lastRun = now
        val candidates = repo.conversations().first()
            .withState(states.all().associateBy { it.threadId })
            .filter { it.isFiltered && !it.pinned && it.timestamp < now - MAX_AGE_MILLIS }
            .map { it.threadId }
        if (candidates.isEmpty()) return 0
        val stale = withContext(Dispatchers.IO) {
            // The user's own words in it: kept, whatever the newest message is. Only these few
            // conversations are looked at, not every message on the phone.
            candidates.filter { !hasOutgoing(it) && starred.keysForThread(it).isEmpty() }.toSet()
        }
        if (stale.isEmpty()) return 0
        val deleted = trash.delete(stale)
        Log.i(TAG, "Moved ${stale.size} old filtered conversations to Recently deleted${deleted.problem?.let { " ($it)" }.orEmpty()}")
        return stale.size
    }

    /** Whether the user has sent (or tried to send, or drafted) something in [threadId]. */
    private fun hasOutgoing(threadId: Long): Boolean {
        val args = arrayOf(threadId.toString())
        val sms = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_INBOX}", args, "${Telephony.Sms._ID} LIMIT 1",
        )?.use { it.count > 0 } ?: true
        return sms || context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_INBOX}", args, "${Telephony.Mms._ID} LIMIT 1",
        )?.use { it.count > 0 } ?: true
    }

    private companion object {
        const val TAG = "WinnowFiltered"
        const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60_000
        const val DAY_MILLIS = 24L * 60 * 60_000
    }
}
