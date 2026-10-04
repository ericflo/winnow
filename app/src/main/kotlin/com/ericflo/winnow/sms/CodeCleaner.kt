package com.ericflo.winnow.sms

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony.Sms
import android.util.Log
import com.ericflo.winnow.classifier.message.SenderKind
import com.ericflo.winnow.classifier.message.VerificationCodes
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.db.StarredDao
import com.ericflo.winnow.data.db.VerdictDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opt-in: deletes one-time codes a day after they arrive (Settings → Messages). Only codes
 * from services, meaning short codes and named senders, never a person's number, so a
 * friend's "the door code is 4821" stays. Starred codes are kept.
 */
class CodeCleaner(
    private val context: Context,
    private val verdicts: VerdictDao,
    private val starred: StarredDao,
    /** Messages with a reminder set: kept, like starred ones, until it's done. */
    private val reminded: suspend () -> Set<String> = { emptySet() },
    /** Deleting needs the SMS role, and the user's say-so. */
    private val enabled: suspend () -> Boolean,
) {
    suspend fun clean(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext 0
        val keep = starred.all().mapTo(HashSet()) { it.messageKey } + reminded()
        val doomed = mutableListOf<Long>()
        context.contentResolver.query(
            Sms.CONTENT_URI, arrayOf(Sms._ID, Sms.ADDRESS, Sms.BODY),
            "${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX} AND ${Sms.DATE} < ?", arrayOf((now - MAX_AGE_MILLIS).toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (ChatMessage.messageKey(ChatMessage.Kind.SMS, id) in keep) continue
                if (isDisposable(c.getString(1).orEmpty(), c.getString(2).orEmpty())) doomed += id
            }
        }
        doomed.forEach { id ->
            runCatching { context.contentResolver.delete(ContentUris.withAppendedId(Sms.CONTENT_URI, id), null, null) }
            verdicts.deleteForMessage(ChatMessage.messageKey(ChatMessage.Kind.SMS, id))
        }
        if (doomed.isNotEmpty()) Log.i(TAG, "Deleted ${doomed.size} old verification codes")
        doomed.size
    }

    companion object {
        private const val TAG = "WinnowCodes"
        const val MAX_AGE_MILLIS = 24 * 60 * 60_000L

        /** A one-time code from a service: the only kind of message this ever deletes. */
        fun isDisposable(sender: String, body: String): Boolean =
            SenderKind.of(sender) in setOf(SenderKind.SHORT_CODE, SenderKind.ALPHANUMERIC) && VerificationCodes.find(body) != null
    }
}
