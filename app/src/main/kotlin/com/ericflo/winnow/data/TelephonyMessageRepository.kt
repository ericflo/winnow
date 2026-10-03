package com.ericflo.winnow.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.SenderRule
import com.ericflo.winnow.data.ChatMessage.Kind
import com.ericflo.winnow.data.db.SenderRuleEntity
import com.ericflo.winnow.data.db.VerdictDao
import com.ericflo.winnow.sms.MmsSender
import com.ericflo.winnow.sms.MmsStore
import com.ericflo.winnow.sms.SmsSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Reads and writes the system SMS/MMS store. Writes only succeed while Winnow is the
 * default SMS app. MMS dates are stored in seconds, SMS dates in milliseconds.
 */
class TelephonyMessageRepository(
    private val context: Context,
    private val dao: VerdictDao,
    private val contacts: ContactLookup,
    private val sms: SmsSender,
    private val mms: MmsSender,
    /** Re-requests a failed MMS download. */
    private val retryDownload: (mmsId: Long) -> Unit,
) : MessageRepository {
    private val resolver = context.contentResolver

    /** Facts about one message, enough to summarize its thread. */
    private data class Head(
        val kind: Kind,
        val id: Long,
        val threadId: Long,
        val date: Long,
        val outgoing: Boolean,
        val unread: Boolean,
    ) {
        val key: String get() = ChatMessage.messageKey(kind, id)
    }

    override fun conversations(): Flow<List<ConversationSummary>> {
        val threads = changes().map {
            val started = System.nanoTime()
            queryConversations().also { Log.d(TAG, "Loaded ${it.size} conversations in ${(System.nanoTime() - started) / 1_000_000} ms") }
        }.flowOn(Dispatchers.IO)
        return combine(threads, verdictsByKey()) { list, verdicts ->
            list.map { (summary, incomingKey) -> summary.copy(verdict = incomingKey?.let(verdicts::get)) }
        }
    }

    override fun messages(threadId: Long): Flow<List<ChatMessage>> {
        val rows = changes().map { queryThread(threadId) }.flowOn(Dispatchers.IO)
        return combine(rows, verdictsByKey()) { list, verdicts -> list.map { it.copy(verdict = verdicts[it.key]) } }
    }

    override fun displayName(address: String): String =
        contacts.displayName(address) ?: ContactLookup.formatAddress(address)

    override fun photoUri(address: String): String? = contacts.photoUri(address)

    override suspend fun threadIdFor(recipients: List<String>): Long = withContext(Dispatchers.IO) {
        Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())
    }

    override suspend fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment>) {
        withContext(Dispatchers.IO) {
            if (recipients.size == 1 && attachments.isEmpty()) sms.send(recipients.single(), body)
            else mms.send(recipients, body, attachments)
        }
    }

    override suspend fun retry(message: ChatMessage) {
        withContext(Dispatchers.IO) {
            when (message.kind) {
                Kind.SMS -> {
                    val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, message.id)
                    val address = resolver.query(uri, arrayOf(Telephony.Sms.ADDRESS), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: return@withContext
                    sms.retry(uri, address, message.body)
                }
                Kind.MMS -> if (message.status == ChatMessage.Status.DOWNLOAD_FAILED) retryDownload(message.id) else mms.retry(message.id)
            }
        }
    }

    override suspend fun markRead(threadId: Long) =
        setRead("${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0", arrayOf(threadId.toString()))

    override suspend fun markAllRead() = setRead("${Telephony.Sms.READ} = 0", null)

    private suspend fun setRead(selection: String, args: Array<String>?) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            runCatching { resolver.update(Telephony.Sms.CONTENT_URI, values, selection, args) }
            runCatching { resolver.update(Telephony.Mms.CONTENT_URI, values, selection, args) }
        }
    }

    /** Marks the newest incoming message unread, as Messages does. */
    override suspend fun markUnread(threadId: Long) {
        withContext(Dispatchers.IO) {
            val newest = heads("${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()))
                .firstOrNull { !it.outgoing } ?: return@withContext
            val table = if (newest.kind == Kind.SMS) Telephony.Sms.CONTENT_URI else Telephony.Mms.CONTENT_URI
            runCatching {
                resolver.update(ContentUris.withAppendedId(table, newest.id), ContentValues().apply { put(Telephony.Sms.READ, 0) }, null, null)
            }
        }
    }

    override suspend fun deleteThreads(threadIds: Collection<Long>) {
        withContext(Dispatchers.IO) {
            threadIds.forEach { id ->
                runCatching { resolver.delete(ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, id), null, null) }
            }
        }
        dao.deleteForThreads(threadIds)
    }

    override suspend fun deleteMessage(message: ChatMessage) {
        withContext(Dispatchers.IO) {
            val table = if (message.kind == Kind.SMS) Telephony.Sms.CONTENT_URI else Telephony.Mms.CONTENT_URI
            runCatching { resolver.delete(ContentUris.withAppendedId(table, message.id), null, null) }
        }
        dao.deleteForMessage(message.key)
    }

    override suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val recipients = threadRecipients()
        val hits = mutableListOf<SearchHit>()
        resolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.BODY} LIKE ? ESCAPE '\\'", arrayOf(like), "${Telephony.Sms.DATE} DESC LIMIT 50",
        )?.use { c ->
            while (c.moveToNext()) hits += hit(c.getLong(0), recipients, c.getString(1).orEmpty(), c.getLong(2))
        }
        val mmsText = HashMap<Long, String>()
        resolver.query(
            Telephony.Mms.Part.CONTENT_URI, arrayOf(Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.TEXT),
            "${Telephony.Mms.Part.CONTENT_TYPE} = 'text/plain' AND ${Telephony.Mms.Part.TEXT} LIKE ? ESCAPE '\\'", arrayOf(like), null,
        )?.use { c -> while (c.moveToNext()) mmsText[c.getLong(0)] = c.getString(1).orEmpty() }
        if (mmsText.isNotEmpty()) {
            resolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE),
                "${Telephony.Mms._ID} IN (${mmsText.keys.joinToString(",")})", null, null,
            )?.use { c ->
                while (c.moveToNext()) hits += hit(c.getLong(1), recipients, mmsText[c.getLong(0)].orEmpty(), c.getLong(2) * 1000)
            }
        }
        hits.sortedByDescending { it.timestamp }.take(50)
    }

    private fun hit(threadId: Long, recipients: Map<Long, List<String>>, body: String, date: Long): SearchHit {
        val people = recipients[threadId].orEmpty()
        return SearchHit(threadId, people, displayNameFor(people, ::displayName), body, date)
    }

    override suspend fun overrideVerdict(threadId: Long, address: String, action: Action) {
        dao.setUserAction(threadId, action.name)
        val rule = if (action == Action.ALLOW) SenderRule.ALWAYS_ALLOW else SenderRule.ALWAYS_FILTER
        dao.upsertSenderRule(SenderRuleEntity(normalizeAddress(address), rule.name, System.currentTimeMillis()))
    }

    override fun verdictRecords(): Flow<List<VerdictRecord>> = dao.observeAll().map { rows ->
        rows.map { row ->
            VerdictRecord(
                category = row.category?.let(Category::fromKey),
                action = (row.userAction ?: row.action).let(Action::valueOf),
                byProvider = row.sourceKind == "provider",
                decidedAt = row.decidedAt,
                costUsd = row.costUsd,
                sender = row.address,
            )
        }
    }

    /** Emits once immediately, then whenever the SMS or MMS store changes. */
    private fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        listOf(Telephony.MmsSms.CONTENT_URI, Telephony.Sms.CONTENT_URI, Telephony.Mms.CONTENT_URI).forEach {
            resolver.registerContentObserver(it, true, observer)
        }
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    private fun verdictsByKey(): Flow<Map<String, StoredVerdict>> =
        dao.observeAll().map { rows -> rows.associate { it.messageKey to it.toStored(ProviderKind::labelFor) } }

    // --- Conversation list ---------------------------------------------------------------

    /** Each thread's summary paired with the key of its newest incoming message (for its verdict). */
    private fun queryConversations(): List<Pair<ConversationSummary, String?>> {
        val recipients = threadRecipients()
        val byThread = heads(null, null).groupBy { it.threadId }
        val snippets = HashMap<Long, String>()
        resolver.query(THREADS_SIMPLE, arrayOf(Telephony.Threads._ID, Telephony.Threads.SNIPPET), null, null, null)?.use { c ->
            while (c.moveToNext()) snippets[c.getLong(0)] = c.getString(1).orEmpty()
        }
        val mmsText = mmsSnippets(byThread.values.mapNotNull { list -> list.first().takeIf { it.kind == Kind.MMS }?.id })

        return byThread.mapNotNull { (threadId, list) ->
            val people = recipients[threadId]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val newest = list.first()
            val text = when (newest.kind) {
                Kind.SMS -> Tapback.summarize(snippets[threadId].orEmpty())
                // No parts yet means an announced message still waiting to download.
                Kind.MMS -> mmsText[newest.id] ?: "MMS message"
            }
            val summary = ConversationSummary(
                threadId = threadId,
                recipients = people,
                displayName = displayNameFor(people, ::displayName),
                snippet = if (newest.outgoing) "You: $text" else text,
                timestamp = newest.date,
                unreadCount = list.count { it.unread },
                verdict = null,
                photoUri = people.singleOrNull()?.let(contacts::photoUri),
            )
            summary to list.firstOrNull { !it.outgoing }?.key
        }.sortedByDescending { it.first.timestamp }
    }

    /** SMS and MMS rows matching [selection] (which may only use thread_id and read), newest first. */
    private fun heads(selection: String?, args: Array<String>?): List<Head> {
        val heads = mutableListOf<Head>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
            if (selection == null) NOT_SMS_DRAFT else "($selection) AND $NOT_SMS_DRAFT", args, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val incoming = c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX
                heads += Head(Kind.SMS, c.getLong(0), c.getLong(1), c.getLong(2), !incoming, incoming && c.getInt(4) == 0)
            }
        }
        val notDraft = "${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}"
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ),
            if (selection == null) notDraft else "($selection) AND $notDraft", args, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val incoming = c.getInt(3) == Telephony.Mms.MESSAGE_BOX_INBOX
                heads += Head(Kind.MMS, c.getLong(0), c.getLong(1), c.getLong(2) * 1000, !incoming, incoming && c.getInt(4) == 0)
            }
        }
        return heads.sortedByDescending { it.date }
    }

    /** thread id → participant addresses, from the threads table and canonical addresses. */
    private fun threadRecipients(): Map<Long, List<String>> {
        val canonical = HashMap<Long, String>()
        resolver.query(CANONICAL_ADDRESSES, arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) canonical[c.getLong(0)] = c.getString(1).orEmpty()
        }
        val result = HashMap<Long, List<String>>()
        resolver.query(THREADS_SIMPLE, arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val ids = c.getString(1).orEmpty().split(' ').mapNotNull { it.toLongOrNull() }
                result[c.getLong(0)] = ids.mapNotNull(canonical::get).filter { it.isNotBlank() }
            }
        }
        return result
    }

    // --- One conversation ----------------------------------------------------------------

    private fun queryThread(threadId: Long): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.STATUS),
            "${Telephony.Sms.THREAD_ID} = ? AND $NOT_SMS_DRAFT", arrayOf(threadId.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getInt(3)
                val incoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                messages += ChatMessage(
                    id = c.getLong(0),
                    threadId = threadId,
                    body = c.getString(1).orEmpty(),
                    timestamp = c.getLong(2),
                    outgoing = !incoming,
                    status = smsStatus(type, c.getInt(5)),
                    verdict = null,
                    sender = if (incoming) c.getString(4) else null,
                )
            }
        }
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.SUBJECT, Telephony.Mms.STATUS),
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}",
            arrayOf(threadId.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val box = c.getInt(2)
                messages += ChatMessage(
                    id = c.getLong(0),
                    threadId = threadId,
                    body = "",
                    timestamp = c.getLong(1) * 1000,
                    outgoing = box != Telephony.Mms.MESSAGE_BOX_INBOX,
                    status = when {
                        c.getInt(3) != MESSAGE_TYPE_NOTIFICATION_IND -> mmsStatus(box)
                        c.getInt(5) == MmsStore.STATUS_DOWNLOAD_FAILED -> ChatMessage.Status.DOWNLOAD_FAILED
                        else -> ChatMessage.Status.DOWNLOADING
                    },
                    verdict = null,
                    kind = Kind.MMS,
                    subject = c.getString(4)?.takeIf { it.isNotBlank() },
                )
            }
        }

        val mmsIds = messages.filter { it.kind == Kind.MMS }.map { it.id }
        val parts = if (mmsIds.isEmpty()) emptyMap() else mmsParts(mmsIds)
        return messages.map { m ->
            if (m.kind != Kind.MMS) return@map m
            val own = parts[m.id].orEmpty()
            m.copy(
                body = own.filter { it.contentType == "text/plain" }.joinToString("\n") { it.text.orEmpty() },
                attachments = own.filter { it.contentType != "text/plain" && it.contentType != "application/smil" }.map {
                    Attachment(ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI, it.partId).toString(), it.contentType, it.name)
                },
                sender = if (m.outgoing) null else mmsSender(m.id),
            )
        }.sortedBy { it.timestamp }
    }

    private data class PartRow(val partId: Long, val contentType: String, val text: String?, val name: String?)

    private fun mmsParts(mmsIds: List<Long>): Map<Long, List<PartRow>> {
        val parts = HashMap<Long, MutableList<PartRow>>()
        resolver.query(
            Telephony.Mms.Part.CONTENT_URI,
            arrayOf(
                Telephony.Mms.Part._ID, Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.CONTENT_TYPE,
                Telephony.Mms.Part.TEXT, Telephony.Mms.Part.NAME, Telephony.Mms.Part.FILENAME,
            ),
            "${Telephony.Mms.Part.MSG_ID} IN (${mmsIds.joinToString(",")})", null, "${Telephony.Mms.Part.SEQ} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                parts.getOrPut(c.getLong(1)) { mutableListOf() } +=
                    PartRow(c.getLong(0), c.getString(2).orEmpty().lowercase(), c.getString(3), c.getString(4) ?: c.getString(5))
            }
        }
        return parts
    }

    /** A one-line preview per MMS: its text, or what kind of attachment it carries. */
    private fun mmsSnippets(mmsIds: List<Long>): Map<Long, String> =
        if (mmsIds.isEmpty()) emptyMap() else mmsParts(mmsIds).mapValues { (_, parts) ->
            parts.firstOrNull { it.contentType == "text/plain" }?.text?.takeIf { it.isNotBlank() }
                ?: if (parts.any { it.contentType.startsWith("image/") }) "Photo" else "Attachment"
        }

    private fun mmsSender(mmsId: Long): String? =
        resolver.query(
            Telephony.Mms.Addr.getAddrUriForMessage(mmsId.toString()), arrayOf(Telephony.Mms.Addr.ADDRESS),
            "${Telephony.Mms.Addr.TYPE} = $ADDR_TYPE_FROM", null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun smsStatus(type: Int, deliveryStatus: Int) = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> ChatMessage.Status.RECEIVED
        Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> ChatMessage.Status.SENDING
        Telephony.Sms.MESSAGE_TYPE_FAILED -> ChatMessage.Status.FAILED
        else -> if (deliveryStatus == Telephony.Sms.STATUS_COMPLETE) ChatMessage.Status.DELIVERED else ChatMessage.Status.SENT
    }

    private fun mmsStatus(box: Int) = when (box) {
        Telephony.Mms.MESSAGE_BOX_INBOX -> ChatMessage.Status.RECEIVED
        Telephony.Mms.MESSAGE_BOX_OUTBOX -> ChatMessage.Status.SENDING
        Telephony.Mms.MESSAGE_BOX_FAILED -> ChatMessage.Status.FAILED
        else -> ChatMessage.Status.SENT
    }

    companion object {
        private val THREADS_SIMPLE: Uri = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        private val CANONICAL_ADDRESSES: Uri = Uri.parse("content://mms-sms/canonical-addresses")

        /** PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND: an MMS announced but not yet downloaded. */
        const val MESSAGE_TYPE_NOTIFICATION_IND = 0x82

        /** PduHeaders.FROM, as stored in the MMS addr table. */
        const val ADDR_TYPE_FROM = 0x89

        private const val TAG = "WinnowStore"

        /** Drafts other SMS apps left in the store aren't messages. */
        private const val NOT_SMS_DRAFT = "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}"
    }
}
