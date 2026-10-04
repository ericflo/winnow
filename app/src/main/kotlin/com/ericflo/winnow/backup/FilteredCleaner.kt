package com.ericflo.winnow.backup

import android.util.Log
import com.ericflo.winnow.data.ChatMessage
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
    private val verdicts: com.ericflo.winnow.data.db.VerdictDao,
    private val trash: Trash,
    /** Conversations with something still to come: a reminder, a scheduled text. Kept. */
    private val busyThreads: suspend () -> Set<Long> = { emptySet() },
    /** Deleting needs the SMS role, and the user's say-so. */
    private val enabled: suspend () -> Boolean,
) {
    /** When this process last looked: once a day is plenty for a month-old cutoff. */
    @Volatile private var lastRun = 0L

    suspend fun clean(now: Long = System.currentTimeMillis(), force: Boolean = false): Int {
        if (!enabled()) return 0
        if (!force && now - lastRun < DAY_MILLIS) return 0
        lastRun = now
        val stateById = states.all().associateBy { it.threadId }
        val busy = busyThreads()
        val candidates = repo.conversations().first()
            .withState(stateById)
            .filter { it.isFiltered && !it.pinned && it.timestamp < now - MAX_AGE_MILLIS }
            // A draft is the user writing in it; a reminder or scheduled text is something to come.
            .filter { c -> c.threadId !in busy && stateById[c.threadId].let { it?.draft == null && it?.draftAttachments == null && it?.draftSubject == null } }
            .map { it.threadId }
        if (candidates.isEmpty()) return 0
        val stale = withContext(Dispatchers.IO) {
            candidates.filter { threadId ->
                // The user's own words in it, or a star: kept, whatever the newest message is. And
                // every text in it filtered, not just the newest: a pharmacy's years of reminders
                // don't go because its latest promotion was filtered.
                !hasOutgoing(threadId) && starred.keysForThread(threadId).isEmpty() &&
                    incomingKeys(threadId).let { keys -> keys.isNotEmpty() && verdicts.filteredKeysForThread(threadId).toSet().containsAll(keys) }
            }.toSet()
        }
        if (stale.isEmpty()) return 0
        // Anything that arrived since it was looked at keeps its conversation where it is.
        val deleted = trash.delete(stale, unlessNewerThan = now - MAX_AGE_MILLIS)
        Log.i(TAG, "Moved ${deleted.items.size} old filtered conversations to Recently deleted${deleted.problem?.let { " ($it)" }.orEmpty()}")
        return deleted.items.size
    }

    /** The keys of [threadId]'s received messages (downloaded ones; an MMS notice has no verdict). */
    private fun incomingKeys(threadId: Long): Set<String> {
        val args = arrayOf(threadId.toString())
        val keys = HashSet<String>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}", args, null,
        )?.use { c -> while (c.moveToNext()) keys += ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0)) }
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX} AND ${Telephony.Mms.MESSAGE_TYPE} != ${com.ericflo.winnow.sms.MmsStore.MESSAGE_TYPE_NOTIFICATION_IND}", args, null,
        )?.use { c -> while (c.moveToNext()) keys += ChatMessage.messageKey(ChatMessage.Kind.MMS, c.getLong(0)) }
        return keys
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
