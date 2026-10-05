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
import com.ericflo.winnow.data.Attachment
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import com.ericflo.winnow.data.attachmentSummary
import com.ericflo.winnow.data.subjectAndText

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
    /** Saves an attachment to the phone's gallery (see "Save received photos and videos"). */
    private val saveToPhone: (Attachment) -> String? = { null },
    /** Teaches the on-device model a classifier service's answer (see Learner.learnFromAnswer). */
    private val learnFromAnswer: suspend (threadId: Long, key: String, message: InboundMessage, category: com.ericflo.winnow.classifier.message.Category) -> Boolean = { _, _, _, _ -> false },
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
    suspend fun onMmsStored(uri: Uri, threadId: Long, sender: String, recipients: List<String>, text: String, mediaTypes: List<String>, subject: String? = null) {
        // Classified and shown with its subject: a message can be carried in the subject line.
        val words = subjectAndText(subject, text)
        val preview = words.ifBlank { attachmentSummary(mediaTypes) }
        // A media-only message still gets classified, on what little it says.
        val action = route(
            uri, ChatMessage.Kind.MMS, threadId, sender, recipients, words.ifBlank { "[photo]" }, preview, caption = words,
            // A code is looked for in the text before the subject (an order number there isn't it).
            codeIn = listOfNotNull(text, subject),
        )
        // Into the gallery if the user asked: only what reached the inbox (never a filtered or
        // silenced one's), and only from people they know. A classifier that timed out lets a
        // stranger's message through too, and the gallery may back up to the cloud. In a group,
        // having texted the group doesn't vouch for everyone in it.
        if (action == Action.ALLOW && settings.current().autoSaveMedia && knows(sender, recipients, threadId)) {
            withContext(Dispatchers.IO) { saveMedia(uri) }
        }
    }

    /**
     * An MMS left on the carrier's server for the user to fetch. With no content there's
     * nothing to classify, so the sender's rule and the conversation's last verdict stand in:
     * a filtered conversation stays quietly filtered, a silenced one stays silent. Once
     * downloaded, it goes through [onMmsStored] like any other.
     */
    suspend fun onMmsDeferred(uri: Uri, threadId: Long, sender: String, sizeBytes: Long) {
        val last = dao.latestForThread(threadId)?.let { it.userAction ?: it.action }
        if (dao.senderRule(normalizeAddress(sender)) == SenderRule.ALWAYS_FILTER.name || last == Action.FILTER.name) {
            withContext(Dispatchers.IO) { markRead(uri) }
            return
        }
        states.unarchive(threadId)
        if (visibleThread.value == threadId) {
            withContext(Dispatchers.IO) { markRead(uri) }
            return
        }
        if (last == Action.SILENCE.name || states.get(threadId).isMuted()) return
        notifier.showMessage(
            threadId = threadId,
            recipients = listOf(sender),
            conversationTitle = states.get(threadId).title ?: displayName(sender),
            senderName = displayName(sender),
            body = deferredPreview(sizeBytes),
            senderPhotoUri = contacts.photoUri(sender),
            hideOnLockScreen = settings.current().hideOnLockScreen,
            quickReplies = settings.current().quickReplies,
            // Nothing to suggest a reply to until it's downloaded: its notice is all there is.
            suggestReplies = false,
        )
    }

    /**
     * Conversations with a text being classified right now: not yet filtered or let through, so
     * nothing outside the app (the home-screen widget) should show it yet.
     */
    val classifying: kotlinx.coroutines.flow.StateFlow<Set<Long>> get() = _classifying
    private val _classifying = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())

    private suspend fun route(
        uri: Uri,
        kind: ChatMessage.Kind,
        threadId: Long,
        sender: String,
        recipients: List<String>,
        text: String,
        preview: String,
        caption: String? = null,
        codeIn: List<String> = listOf(text),
    ): Action {
        _classifying.update { it + threadId }
        return try {
            routeNow(uri, kind, threadId, sender, recipients, text, preview, caption, codeIn)
        } finally {
            _classifying.update { it - threadId }
        }
    }

    private suspend fun routeNow(
        uri: Uri,
        kind: ChatMessage.Kind,
        threadId: Long,
        sender: String,
        recipients: List<String>,
        text: String,
        preview: String,
        /** What an MMS said in words, if anything: shown under its photo in the notification. */
        caption: String? = null,
        /** Where to look for a verification code, in order. */
        codeIn: List<String> = listOf(text),
    ): Action {
        val key = ChatMessage.messageKey(kind, ContentUris.parseId(uri))
        // The store reuses a deleted message's id, and a deletion Winnow didn't make (another app's,
        // a restore elsewhere) leaves its verdict behind: that one isn't this message's.
        dao.deleteForMessage(key)
        var asked: InboundMessage? = null
        val verdict = try {
            withTimeout(BUDGET_MILLIS) { classify(sender, text, threadId) { asked = it } }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Classification over budget; delivering normally")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed; delivering normally", e)
            null
        }

        // A correction the user made while this was being classified (Filter sender from the
        // conversation, say) stands: any row for it now was written since.
        val existing = dao.forKey(key)?.takeIf { it.threadId == threadId }
        val corrected = existing?.userAction?.let { runCatching { Action.valueOf(it) }.getOrNull() }
        if (verdict != null) {
            // The correction's own row is never news for a daily summary; keeping it keeps that too.
            dao.upsert(
                VerdictEntity.from(key, threadId, sender, verdict, System.currentTimeMillis()).copy(atArrival = true)
                    .copy(userAction = corrected?.name, userCategory = existing?.userCategory, summarized = existing?.summarized ?: false),
            )
        }
        // The service's answer goes on teaching the on-device model, unless the user has had their say.
        val message = asked
        if (verdict != null && message != null && corrected == null && existing?.userCategory == null && teaches(verdict, settings.current().learnFromProvider)) {
            runCatching { learnFromAnswer(threadId, key, message, verdict.category!!) }.onFailure { Log.w(TAG, "Couldn't learn from an answer", it) }
        }
        val action = corrected ?: verdict?.action ?: Action.ALLOW
        // A new message brings an archived conversation back, unless it's being filtered.
        if (action != Action.FILTER) states.unarchive(threadId)
        if (action != Action.FILTER && visibleThread.value == threadId) {
            // The user is looking at this conversation: no heads-up, and it's already read.
            withContext(Dispatchers.IO) { markRead(uri) }
            return action
        }
        when (action) {
            Action.ALLOW -> if (!states.get(threadId).isMuted()) {
                notifier.showMessage(
                    threadId = threadId,
                    recipients = recipients,
                    conversationTitle = states.get(threadId).title ?: displayNameFor(recipients, ::displayName),
                    senderName = displayName(sender),
                    body = preview,
                    code = codeIn.firstNotNullOfOrNull(VerificationCodes::find),
                    senderPhotoUri = contacts.photoUri(sender),
                    hideOnLockScreen = settings.current().hideOnLockScreen,
                    offerSpam = recipients.size == 1 && !contacts.isContact(sender),
                    quickReplies = settings.current().quickReplies,
                    suggestReplies = settings.current().suggestedReplies,
                    // The picture itself, from people the user knows: a stranger's never pops up on screen.
                    image = if (kind == ChatMessage.Kind.MMS && knows(sender, recipients, threadId)) withContext(Dispatchers.IO) { firstPhoto(uri) } else null,
                    caption = caption,
                )
            }
            Action.SILENCE -> Unit
            Action.FILTER -> withContext(Dispatchers.IO) { markRead(uri) }
        }
        return action
    }

    /** A contact, or someone the user has texted one to one: whose photos may show and be saved. */
    private suspend fun knows(sender: String, recipients: List<String>, threadId: Long): Boolean =
        contacts.isContact(sender) || (recipients.size == 1 && withContext(Dispatchers.IO) { hasOutgoing(threadId) })

    /** The first still photo in the MMS at [uri], if any. */
    private fun firstPhoto(uri: Uri): Uri? = context.contentResolver.query(
        Telephony.Mms.Part.CONTENT_URI,
        arrayOf(Telephony.Mms.Part._ID),
        "${Telephony.Mms.Part.MSG_ID} = ? AND ${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'image/%'",
        arrayOf(ContentUris.parseId(uri).toString()), "${Telephony.Mms.Part._ID} ASC",
    )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI, c.getLong(0)) else null }

    /** The photos and videos of the MMS at [uri], each saved to the phone. */
    private fun saveMedia(uri: Uri) {
        val id = ContentUris.parseId(uri)
        context.contentResolver.query(
            Telephony.Mms.Part.CONTENT_URI,
            arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.NAME),
            "${Telephony.Mms.Part.MSG_ID} = ? AND (${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'image/%' OR ${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'video/%')",
            arrayOf(id.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val part = Attachment(ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI, c.getLong(0)).toString(), c.getString(1).orEmpty(), c.getString(2))
                runCatching { saveToPhone(part) }.onFailure { Log.w(TAG, "Couldn't save a received photo", it) }
            }
        }
    }

    private suspend fun classify(address: String, body: String, threadId: Long, onMessage: (InboundMessage) -> Unit): Verdict {
        val current = settings.current()
        val message = InboundMessage(
            sender = address,
            body = body,
            senderInContacts = contacts.isContact(address),
            userHasMessagedSender = withContext(Dispatchers.IO) { hasOutgoing(threadId) },
            senderRule = dao.senderRule(normalizeAddress(address))?.let { runCatching { SenderRule.valueOf(it) }.getOrNull() },
        )
        onMessage(message)
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

/**
 * Whether [verdict] should teach the on-device model as its text arrives: a classifier service
 * answered, sure enough not to be guessing, and the user wants the model taught. Pure, so it's
 * unit-tested.
 */
fun teaches(verdict: Verdict, learnFromProvider: Boolean): Boolean =
    learnFromProvider && verdict.source is com.ericflo.winnow.classifier.message.VerdictSource.Provider &&
        verdict.category != null && verdict.confidence >= Learner.MIN_TEACH_CONFIDENCE

/** "Picture message (48 KB) · tap to download". */
fun deferredPreview(sizeBytes: Long): String {
    val size = when {
        sizeBytes <= 0 -> ""
        sizeBytes < 1_000_000 -> " (${(sizeBytes + 999) / 1000} KB)"
        else -> " (${"%.1f".format(sizeBytes / 1_000_000.0)} MB)"
    }
    return "Picture message$size · tap to download"
}
