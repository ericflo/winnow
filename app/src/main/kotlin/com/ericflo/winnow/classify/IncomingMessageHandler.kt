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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.ericflo.winnow.data.threadRecipients
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
        storeSms(address, body, sentAt, subscriptionId)?.let { handleStored(it) }
    }

    /** An incoming SMS once it's in the store: what's left is classifying it and saying so. */
    data class StoredSms(val uri: Uri, val threadId: Long, val address: String, val body: String)

    /**
     * Writes an incoming SMS to the store: the one thing Android waits on before it delivers the
     * next text (see SmsDeliverReceiver). Null if it couldn't be stored, after telling the user
     * it arrived anyway.
     */
    suspend fun storeSms(address: String, body: String, sentAt: Long, subscriptionId: Int): StoredSms? {
        val stored = withContext(Dispatchers.IO) { store(address, body, sentAt, subscriptionId)?.also { markUnfinished(it.first) } }
        if (stored == null) {
            Log.e(TAG, "Could not store incoming SMS; is Winnow the default SMS app?")
            notifier.showMessage(
                -1, listOf(address), displayName(address), displayName(address), body,
                hideOnLockScreen = runCatching { settings.current().hideOnLockScreen }.getOrDefault(false),
            )
            return null
        }
        return StoredSms(stored.first, stored.second, address, body)
    }

    /**
     * Classifies a stored SMS and notifies. Texts are classified as they come, all at once, but
     * each is acted on (notified, filtered) only after the text before it in its conversation, so
     * a conversation's notification shows its texts in the order they came.
     */
    suspend fun handleStored(sms: StoredSms) {
        try {
            inLine(sms.threadId) { beforeActing ->
                route(sms.uri, ChatMessage.Kind.SMS, sms.threadId, sms.address, listOf(sms.address), sms.body, Tapback.summarize(sms.body), beforeActing = beforeActing)
            }
        } finally {
            markFinished(sms.uri)
        }
    }

    /**
     * Messages stored but not yet classified and said, kept on disk from the moment they're
     * stored: if Winnow is ended between the two (Android reclaiming memory, say), the message
     * is in the store but no one was told. [recoverUnfinished] finishes them at the next start.
     */
    private val unfinished by lazy { context.getSharedPreferences("incoming_unfinished", Context.MODE_PRIVATE) }
    /** When this run began: messages marked before it were left by an earlier one. */
    private val startedAt = System.currentTimeMillis()

    /** [uri] is stored; what's left is classifying it and saying so. Written through at once, off the main thread. */
    fun markUnfinished(uri: Uri) {
        runCatching { unfinished.edit().putLong(uri.toString(), System.currentTimeMillis()).commit() }
    }

    private fun markFinished(uri: Uri) {
        runCatching { unfinished.edit().remove(uri.toString()).apply() }
    }

    /** What [recoverUnfinished] needs of a stored MMS. */
    data class StoredMms(val sender: String, val text: String, val mediaTypes: List<String>, val subject: String?)

    /**
     * Classifies and tells the user about messages an earlier run stored and didn't finish (see
     * [markUnfinished]): still unread, still undecided, and from the last day. Any read or decided
     * meanwhile, gone, or older only come off the list. [mms] reads a stored MMS back.
     */
    suspend fun recoverUnfinished(mms: (Long) -> StoredMms?) {
        val left = withContext(Dispatchers.IO) { runCatching { unfinished.all }.getOrDefault(emptyMap()) }
            .mapNotNull { (key, at) -> (at as? Long)?.takeIf { it < startedAt }?.let { key to it } }
        if (left.isEmpty()) return
        val participants by lazy { context.contentResolver.threadRecipients() }
        for ((key, at) in left.sortedBy { it.second }) {
            val uri = Uri.parse(key)
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull()
            val isMms = uri.authority == "mms"
            val row = id?.let { withContext(Dispatchers.IO) { unreadRow(uri, isMms) } }
            val messageKey = id?.let { ChatMessage.messageKey(if (isMms) ChatMessage.Kind.MMS else ChatMessage.Kind.SMS, it) }
            if (row == null || messageKey == null || System.currentTimeMillis() - at > RECOVER_WITHIN_MILLIS || dao.forKey(messageKey) != null) {
                markFinished(uri)
                continue
            }
            Log.i(TAG, "Finishing a message an earlier run stored but didn't tell anyone about")
            runCatching {
                if (!isMms) {
                    handleStored(StoredSms(uri, row.threadId, row.sms!!.first, row.sms.second))
                } else {
                    val m = withContext(Dispatchers.IO) { mms(id) }
                    val people = withContext(Dispatchers.IO) { participants[row.threadId] }.orEmpty().ifEmpty { listOfNotNull(m?.sender) }
                    if (m == null) markFinished(uri) else onMmsStored(uri, row.threadId, m.sender, people, m.text, m.mediaTypes, m.subject)
                }
            }.onFailure { Log.w(TAG, "Couldn't finish an earlier message", it); markFinished(uri) }
        }
    }

    /** A stored message's thread, and for an SMS its sender and text; null once it's read or gone. */
    private class UnreadRow(val threadId: Long, val sms: Pair<String, String>?)

    private fun unreadRow(uri: Uri, isMms: Boolean): UnreadRow? {
        val columns = if (isMms) arrayOf(Telephony.Mms.THREAD_ID, Telephony.Mms.READ) else arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.READ, Telephony.Sms.ADDRESS, Telephony.Sms.BODY)
        return context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getInt(1) != 0) null
            else UnreadRow(c.getLong(0), if (isMms) null else (c.getString(2).orEmpty() to c.getString(3).orEmpty()))
        }
    }

    /**
     * Runs [block] with a place in [threadId]'s line, taken at once (messages take theirs in the
     * order they were stored); what it's given waits for the message before it to be acted on.
     */
    private suspend fun <T> inLine(threadId: Long, block: suspend (beforeActing: suspend () -> Unit) -> T): T {
        val mine = kotlinx.coroutines.CompletableDeferred<Unit>()
        val before = synchronized(lastInLine) { lastInLine.put(threadId, mine) }
        try {
            return block { before?.await() }
        } finally {
            mine.complete(Unit)
            synchronized(lastInLine) { if (lastInLine[threadId] === mine) lastInLine.remove(threadId) }
        }
    }

    /** Texts being classified at once (see [routeNow]). */
    private val classifyGate = kotlinx.coroutines.sync.Semaphore(CLASSIFY_AT_ONCE)

    /** The last text in line in each conversation (see [handleStored]). */
    private val lastInLine = HashMap<Long, kotlinx.coroutines.CompletableDeferred<Unit>>()

    /** A downloaded MMS, already stored by [com.ericflo.winnow.sms.MmsReceiver]. */
    suspend fun onMmsStored(uri: Uri, threadId: Long, sender: String, recipients: List<String>, text: String, mediaTypes: List<String>, subject: String? = null) {
        try {
            // Classified and shown with its subject: a message can be carried in the subject line.
            val words = subjectAndText(subject, text)
            val preview = words.ifBlank { attachmentSummary(mediaTypes) }
            // A media-only message still gets classified, on what little it says.
            // In line with the conversation's other messages (see handleStored): a group gets texts and pictures.
            val action = inLine(threadId) { beforeActing ->
                route(
                    uri, ChatMessage.Kind.MMS, threadId, sender, recipients, words.ifBlank { "[photo]" }, preview, caption = words,
                    // A code is looked for in the text before the subject (an order number there isn't it).
                    codeIn = listOfNotNull(text, subject), beforeActing = beforeActing,
                )
            }
            // Into the gallery if the user asked: only what reached the inbox (never a filtered or
            // silenced one's), and only from people they know. A classifier that timed out lets a
            // stranger's message through too, and the gallery may back up to the cloud. In a group,
            // having texted the group doesn't vouch for everyone in it.
            if (action == Action.ALLOW && settings.current().autoSaveMedia && knows(sender, recipients, threadId)) {
                withContext(Dispatchers.IO) { saveMedia(uri) }
            }
        } finally {
            markFinished(uri)
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
    /** How many texts each conversation has being classified: two at once mustn't clear each other. */
    private val classifyingCounts = HashMap<Long, Int>()

    private fun classifyingChanged(threadId: Long, by: Int) = synchronized(classifyingCounts) {
        val n = (classifyingCounts[threadId] ?: 0) + by
        if (n > 0) classifyingCounts[threadId] = n else classifyingCounts.remove(threadId)
        _classifying.value = classifyingCounts.keys.toSet()
    }

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
        /** Waited on once it's classified, before it's acted on (see [handleStored]). */
        beforeActing: suspend () -> Unit = {},
    ): Action {
        classifyingChanged(threadId, +1)
        return try {
            routeNow(uri, kind, threadId, sender, recipients, text, preview, caption, codeIn, beforeActing)
        } finally {
            classifyingChanged(threadId, -1)
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
        beforeActing: suspend () -> Unit = {},
    ): Action {
        val key = ChatMessage.messageKey(kind, ContentUris.parseId(uri))
        // The store reuses a deleted message's id, and a deletion Winnow didn't make (another app's,
        // a restore elsewhere) leaves its verdict behind: that one isn't this message's.
        dao.deleteForMessage(key)
        var asked: InboundMessage? = null
        val verdict = try {
            // A few at a time, as a burst of texts would otherwise ask the service all at once; the
            // wait for a turn isn't counted against the text's budget.
            classifyGate.withPermit { withTimeout(BUDGET_MILLIS) { classify(sender, text, threadId) { asked = it } } }
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
        // The text before it in its conversation is acted on first.
        beforeActing()
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
        /** Texts classified at once in a burst (coming back into signal, say). */
        const val CLASSIFY_AT_ONCE = 4
        /** An unfinished message older than this is old news: it comes off the list unannounced. */
        const val RECOVER_WITHIN_MILLIS = 24 * 60 * 60_000L
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
