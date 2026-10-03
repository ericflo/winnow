package com.ericflo.winnow.classify

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classifier.message.SenderRule
import com.ericflo.winnow.classifier.message.Verdict
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.TelephonyMessageRepository
import com.ericflo.winnow.data.db.VerdictDao
import com.ericflo.winnow.data.db.VerdictEntity
import com.ericflo.winnow.data.normalizeAddress
import com.ericflo.winnow.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * Store → classify → act, for each incoming SMS.
 *
 * The message is stored before classification, so nothing is lost if classification fails.
 * Failure or a blown time budget fails open: the message is delivered with a notification.
 */
class IncomingMessageHandler(
    private val context: Context,
    private val dao: VerdictDao,
    private val contacts: ContactLookup,
    private val settings: SettingsRepository,
    private val classifiers: ClassifierFactory,
    private val notifier: Notifier,
) {

    suspend fun onSmsDelivered(address: String, body: String, sentAt: Long, subscriptionId: Int) {
        val stored = withContext(Dispatchers.IO) { store(address, body, sentAt, subscriptionId) }
        if (stored == null) {
            Log.e(TAG, "Could not store incoming SMS; is Winnow the default SMS app?")
            notifier.showMessage(-1, address, displayName(address), body)
            return
        }
        val (uri, threadId) = stored

        val verdict = try {
            withTimeout(BUDGET_MILLIS) { classify(address, body, threadId) }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Classification over budget; delivering normally")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed; delivering normally", e)
            null
        }

        if (verdict != null) {
            val key = TelephonyMessageRepository.messageKey(ContentUris.parseId(uri))
            dao.upsert(VerdictEntity.from(key, threadId, address, verdict, System.currentTimeMillis()))
        }
        when (verdict?.action ?: Action.ALLOW) {
            Action.ALLOW -> notifier.showMessage(threadId, address, displayName(address), body)
            Action.SILENCE -> Unit
            Action.FILTER -> withContext(Dispatchers.IO) { markRead(uri) }
        }
    }

    private suspend fun classify(address: String, body: String, threadId: Long): Verdict {
        val current = settings.current()
        val message = InboundMessage(
            sender = address,
            body = body,
            senderInContacts = contacts.isContact(address),
            userHasMessagedSender = withContext(Dispatchers.IO) { hasOutgoing(threadId) },
            senderRule = dao.senderRule(normalizeAddress(address))?.let { runCatching { SenderRule.valueOf(it) }.getOrNull() },
        )
        return classifiers.create(current, timeoutMillis = PROVIDER_TIMEOUT_MILLIS).classify(message)
    }

    private fun store(address: String, body: String, sentAt: Long, subscriptionId: Int): Pair<Uri, Long>? {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, sentAt)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        }
        val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) ?: return null
        val threadId = context.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: return null
        return uri to threadId
    }

    private fun hasOutgoing(threadId: Long): Boolean =
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ?",
            arrayOf(threadId.toString(), Telephony.Sms.MESSAGE_TYPE_SENT.toString()), null,
        )?.use { it.count > 0 } ?: false

    private fun markRead(uri: Uri) {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        context.contentResolver.update(uri, values, null, null)
    }

    private fun displayName(address: String) = contacts.displayName(address) ?: ContactLookup.formatAddress(address)

    private companion object {
        const val TAG = "WinnowIncoming"

        // goAsync() allows about 10 s; leave room to store, write the verdict and notify.
        const val BUDGET_MILLIS = 7_000L
        const val PROVIDER_TIMEOUT_MILLIS = 5_000L
    }
}
