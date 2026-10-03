package com.ericflo.winnow.data

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.SenderRule
import com.ericflo.winnow.data.db.SenderRuleEntity
import com.ericflo.winnow.data.db.VerdictDao
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
 * Reads and writes the system SMS store. Writes only succeed while Winnow is the default
 * SMS app. MMS is not read yet.
 */
class TelephonyMessageRepository(
    private val context: Context,
    private val dao: VerdictDao,
    private val contacts: ContactLookup,
    private val sender: SmsSender,
) : MessageRepository {
    private val resolver = context.contentResolver

    private data class Row(
        val id: Long,
        val threadId: Long,
        val address: String,
        val body: String,
        val date: Long,
        val type: Int,
        val read: Boolean,
    )

    override fun conversations(): Flow<List<ConversationSummary>> {
        val threads = changes().map { queryThreads() }.flowOn(Dispatchers.IO)
        return combine(threads, verdictsByKey()) { list, verdicts ->
            list.map { (summary, incomingKey) -> summary.copy(verdict = incomingKey?.let(verdicts::get)) }
        }
    }

    override fun messages(threadId: Long): Flow<List<ChatMessage>> {
        val rows = changes()
            .map { query("${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} ASC") }
            .flowOn(Dispatchers.IO)
        return combine(rows, verdictsByKey()) { list, verdicts ->
            list.map { r ->
                ChatMessage(
                    id = r.id,
                    threadId = r.threadId,
                    body = r.body,
                    timestamp = r.date,
                    outgoing = r.type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                    status = statusOf(r.type),
                    verdict = verdicts[messageKey(r.id)],
                )
            }
        }
    }

    override fun displayName(address: String): String =
        contacts.displayName(address) ?: ContactLookup.formatAddress(address)

    override suspend fun threadIdFor(address: String): Long = withContext(Dispatchers.IO) {
        Telephony.Threads.getOrCreateThreadId(context, address)
    }

    override suspend fun send(address: String, body: String) {
        withContext(Dispatchers.IO) { sender.send(address, body) }
    }

    override suspend fun markRead(threadId: Long) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            runCatching {
                resolver.update(
                    Telephony.Sms.CONTENT_URI, values,
                    "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0", arrayOf(threadId.toString()),
                )
            }
        }
    }

    override suspend fun overrideVerdict(threadId: Long, address: String, action: Action) {
        dao.setUserAction(threadId, action.name)
        val rule = if (action == Action.ALLOW) SenderRule.ALWAYS_ALLOW else SenderRule.ALWAYS_FILTER
        dao.upsertSenderRule(SenderRuleEntity(normalizeAddress(address), rule.name, System.currentTimeMillis()))
    }

    /** Emits once immediately, then whenever the SMS store changes. */
    private fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        resolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    private fun verdictsByKey(): Flow<Map<String, StoredVerdict>> =
        dao.observeAll().map { rows -> rows.associate { it.messageKey to it.toStored(ProviderKind::labelFor) } }

    /** One pass over the SMS table, newest first: each thread's summary and its newest incoming message key. */
    private fun queryThreads(): List<Pair<ConversationSummary, String?>> {
        val latest = LinkedHashMap<Long, Row>()
        val unread = HashSet<Long>()
        val latestIncoming = HashMap<Long, Long>()
        for (r in query(null, null, "${Telephony.Sms.DATE} DESC")) {
            latest.putIfAbsent(r.threadId, r)
            if (r.type == Telephony.Sms.MESSAGE_TYPE_INBOX) {
                if (!r.read) unread += r.threadId
                latestIncoming.putIfAbsent(r.threadId, r.id)
            }
        }
        return latest.values.map { r ->
            val summary = ConversationSummary(
                threadId = r.threadId,
                address = r.address,
                displayName = displayName(r.address),
                snippet = r.body,
                timestamp = r.date,
                unread = r.threadId in unread,
                verdict = null,
            )
            summary to latestIncoming[r.threadId]?.let(::messageKey)
        }
    }

    private fun query(selection: String?, args: Array<String>?, order: String): List<Row> =
        resolver.query(Telephony.Sms.CONTENT_URI, PROJECTION, selection, args, order)?.use { c ->
            val id = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val thread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val type = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val read = c.getColumnIndexOrThrow(Telephony.Sms.READ)
            buildList {
                while (c.moveToNext()) {
                    add(
                        Row(
                            id = c.getLong(id),
                            threadId = c.getLong(thread),
                            address = c.getString(address).orEmpty(),
                            body = c.getString(body).orEmpty(),
                            date = c.getLong(date),
                            type = c.getInt(type),
                            read = c.getInt(read) != 0,
                        ),
                    )
                }
            }
        }.orEmpty()

    private fun statusOf(type: Int) = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> ChatMessage.Status.RECEIVED
        Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> ChatMessage.Status.SENDING
        Telephony.Sms.MESSAGE_TYPE_FAILED -> ChatMessage.Status.FAILED
        else -> ChatMessage.Status.SENT
    }

    companion object {
        fun messageKey(smsId: Long) = "sms:$smsId"

        private val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
        )
    }
}
