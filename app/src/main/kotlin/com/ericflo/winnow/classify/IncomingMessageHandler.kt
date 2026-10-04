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
import com.ericflo.winnow.classifier.message.VerificationCodes
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.ConversationStateStore
import com.ericflo.winnow.data.Tapback
import com.ericflo.winnow.data.displayNameFor
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.db.VerdictDao
import com.ericflo.winnow.data.db.VerdictEntity
import com.ericflo.winnow.data.normalizeAddress
import com.ericflo.winnow.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import com.ericflo.winnow.data.attachmentSummary

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
    private val states: ConversationStateStore,
    private val visibleThread: StateFlow<Long?>,
) {

    suspend fun onSmsDelivered(address: String, body: String, sentAt: Long, subscriptionId: Int) {
        val stored = withContext(Dispatchers.IO) { store(address, body, sentAt, subscriptionId) }
        if (stored == null) {
            Log.e(TAG, "Could not store incoming SMS; is Winnow the default SMS app?")
            notifier.showMessage(
                -1, listOf(address), displayName(address), displayName(address), body,
                hideOnLockScreen = runCatching { settings.current().hideOnLockScreen }.getOrDefault(false),
            )
            return
        }
        val (uri, threadId) = stored
        route(uri, ChatMessage.Kind.SMS, threadId, address, listOf(address), body, Tapback.summarize(body))
    }

    /** A downloaded MMS, already stored by [com.ericflo.winnow.sms.MmsReceiver]. */
    suspend fun onMmsStored(uri: Uri, threadId: Long, sender: String, recipients: List<String>, text: String, mediaTypes: List<String>) {
        val preview = text.ifBlank { attachmentSummary(mediaTypes) }
        // A media-only message still gets classified, on what little it says.
        route(uri, ChatMessage.Kind.MMS, threadId, sender, recipients, text.ifBlank { "[photo]" }, preview)
    }

    /**
     * An MMS left on the carrier's server for the user to fetch. With no content there's
     * nothing to classify, so only sender rules, mute and the open conversation decide whether
     * it notifies. Once downloaded, it goes through [onMmsStored] like any other.
     */
    suspend fun onMmsDeferred(threadId: Long, sender: String, sizeBytes: Long) {
        if (dao.senderRule(normalizeAddress(sender)) == SenderRule.ALWAYS_FILTER.name) return
        states.unarchive(threadId)
        if (visibleThread.value == threadId || states.get(threadId).muted) return
        notifier.showMessage(
            threadId = threadId,
            recipients = listOf(sender),
            conversationTitle = states.get(threadId).title ?: displayName(sender),
            senderName = displayName(sender),
            body = deferredPreview(sizeBytes),
            senderPhotoUri = contacts.photoUri(sender),
            hideOnLockScreen = settings.current().hideOnLockScreen,
        )
    }

    private suspend fun route(
        uri: Uri,
        kind: ChatMessage.Kind,
        threadId: Long,
        sender: String,
        recipients: List<String>,
        text: String,
        preview: String,
    ) {
        val verdict = try {
            withTimeout(BUDGET_MILLIS) { classify(sender, text, threadId) }
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
            val key = ChatMessage.messageKey(kind, ContentUris.parseId(uri))
            dao.upsert(VerdictEntity.from(key, threadId, sender, verdict, System.currentTimeMillis()))
        }
        val action = verdict?.action ?: Action.ALLOW
        // A new message brings an archived conversation back, unless it's being filtered.
        if (action != Action.FILTER) states.unarchive(threadId)
        if (action != Action.FILTER && visibleThread.value == threadId) {
            // The user is looking at this conversation: no heads-up, and it's already read.
            withContext(Dispatchers.IO) { markRead(uri) }
            return
        }
        when (action) {
            Action.ALLOW -> if (!states.get(threadId).muted) {
                notifier.showMessage(
                    threadId = threadId,
                    recipients = recipients,
                    conversationTitle = states.get(threadId).title ?: displayNameFor(recipients, ::displayName),
                    senderName = displayName(sender),
                    body = preview,
                    code = VerificationCodes.find(text),
                    senderPhotoUri = contacts.photoUri(sender),
                    hideOnLockScreen = settings.current().hideOnLockScreen,
                )
            }
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

    private fun hasOutgoing(threadId: Long): Boolean {
        val args = arrayOf(threadId.toString())
        val sms = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_SENT}", args, null,
        )?.use { it.count > 0 } ?: false
        return sms || context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_SENT}", args, null,
        )?.use { it.count > 0 } ?: false
    }

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

/** "Picture message (48 KB) · tap to download". */
fun deferredPreview(sizeBytes: Long): String {
    val size = when {
        sizeBytes <= 0 -> ""
        sizeBytes < 1_000_000 -> " (${(sizeBytes + 999) / 1000} KB)"
        else -> " (${"%.1f".format(sizeBytes / 1_000_000.0)} MB)"
    }
    return "Picture message$size · tap to download"
}
