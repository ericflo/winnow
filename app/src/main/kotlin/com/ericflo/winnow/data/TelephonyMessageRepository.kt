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
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classifier.message.SenderRule
import com.ericflo.winnow.data.ChatMessage.Kind
import com.ericflo.winnow.data.db.SenderRuleEntity
import com.ericflo.winnow.data.db.StarredDao
import com.ericflo.winnow.data.db.VerdictDao
import com.ericflo.winnow.data.db.VerdictEntity
import com.ericflo.winnow.mms.MmsCharsets
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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

/**
 * Reads and writes the system SMS/MMS store. Writes only succeed while Winnow is the
 * default SMS app. MMS dates are stored in seconds, SMS dates in milliseconds.
 */
class TelephonyMessageRepository(
    private val context: Context,
    private val dao: VerdictDao,
    private val starred: StarredDao,
    private val contacts: ContactLookup,
    private val sms: SmsSender,
    private val mms: MmsSender,
    /** Re-requests a failed MMS download. */
    private val retryDownload: (mmsId: Long) -> Unit,
    /** The user overrode Winnow's call on the thread's newest incoming message, for the on-device model to learn from. */
    private val onCorrected: suspend (threadId: Long, message: InboundMessage, action: Action) -> Unit = { _, _, _ -> },
    private val onUncorrected: suspend (threadId: Long) -> Unit = {},
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
        /** An MMS announced but not downloaded: it has no verdict of its own. */
        val placeholder: Boolean = false,
        /** One of the user's that didn't go out. */
        val failed: Boolean = false,
    ) {
        val key: String get() = ChatMessage.messageKey(kind, id)
    }

    override fun conversations(): Flow<List<ConversationSummary>> {
        // Contacts too: the list carries each conversation's name and photo.
        val threads = merge(changes(), contacts.changes()).conflate().map {
            val started = System.nanoTime()
            queryConversations().also { Log.d(TAG, "Loaded ${it.size} conversations in ${(System.nanoTime() - started) / 1_000_000} ms") }
        }.flowOn(Dispatchers.IO)
        return combine(threads, verdictsForList()) { list, verdicts ->
            list.map { (summary, incomingKey) ->
                // The newest message's verdict when it's incoming; after a reply, the thread's latest verdict.
                summary.copy(verdict = incomingKey?.let(verdicts.byKey::get) ?: verdicts.latestByThread[summary.threadId].takeIf { incomingKey == null })
            }
        }
    }

    override fun messages(threadId: Long): Flow<List<ChatMessage>> {
        val rows = changes().map { queryThread(threadId) }.flowOn(Dispatchers.IO)
        return combine(rows, verdictsByKey()) { list, verdicts -> list.map { it.copy(verdict = verdicts[it.key]) } }
    }

    override fun displayName(address: String): String =
        contacts.displayName(address) ?: ContactLookup.formatAddress(address)

    override fun photoUri(address: String): String? = contacts.photoUri(address)

    override fun contactName(address: String): String? = contacts.displayName(address)

    override fun contactChanges(): Flow<Unit> = contacts.changes()

    override suspend fun threadIdFor(recipients: List<String>): Long = withContext(Dispatchers.IO) {
        Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())
    }

    override suspend fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment>, subscriptionId: Int?, subject: String?) {
        withContext(Dispatchers.IO) {
            // One person, nothing attached, no subject: a text, unless the carrier wants one this long as an MMS.
            if (recipients.size == 1 && attachments.isEmpty() && subject == null && !mms.textNeedsMms(body, subscriptionId)) {
                sms.send(recipients.single(), body, subscriptionId)
            } else {
                mms.send(recipients, body, attachments, subscriptionId, subject)
            }
        }
    }

    override suspend fun messagesByKey(keys: Collection<String>): List<StarredMessage> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptyList()
        val threadIds = HashSet<Long>()
        keys.forEach { key ->
            val (kind, id) = key.split(':').let { it.getOrNull(0) to it.getOrNull(1)?.toLongOrNull() }
            id ?: return@forEach
            val (table, column) = if (kind == "sms") Telephony.Sms.CONTENT_URI to Telephony.Sms.THREAD_ID else Telephony.Mms.CONTENT_URI to Telephony.Mms.THREAD_ID
            resolver.query(ContentUris.withAppendedId(table, id), arrayOf(column), null, null, null)?.use { c -> if (c.moveToFirst()) threadIds += c.getLong(0) }
        }
        // Reading whole threads keeps one code path for SMS, MMS and their parts; starred lists are short.
        val recipients = resolver.threadRecipients()
        val wanted = keys.toSet()
        threadIds.flatMap { threadId ->
            val people = recipients[threadId].orEmpty()
            val name = displayNameFor(people, ::displayName)
            queryThread(threadId).filter { it.key in wanted }.map { StarredMessage(it.copy(starred = true), people, name) }
        }
    }

    override suspend fun lastIncomingSubscription(threadId: Long): Int? = withContext(Dispatchers.IO) {
        val newest = heads("${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString())).firstOrNull { !it.outgoing } ?: return@withContext null
        val (table, column) = if (newest.kind == Kind.SMS) Telephony.Sms.CONTENT_URI to Telephony.Sms.SUBSCRIPTION_ID else Telephony.Mms.CONTENT_URI to Telephony.Mms.SUBSCRIPTION_ID
        resolver.query(ContentUris.withAppendedId(table, newest.id), arrayOf(column), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0).takeIf { it >= 0 } else null }
    }

    override suspend fun retry(message: ChatMessage) {
        withContext(Dispatchers.IO) {
            when (message.kind) {
                Kind.SMS -> {
                    val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, message.id)
                    val (address, sub) = resolver.query(uri, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.SUBSCRIPTION_ID), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) to (if (c.isNull(1)) null else c.getInt(1).takeIf { it >= 0 }) else null
                    } ?: return@withContext
                    // Retry on the SIM it was first sent from.
                    sms.retry(uri, address ?: return@withContext, message.body, sub)
                }
                Kind.MMS -> if (message.status == ChatMessage.Status.DOWNLOAD_FAILED || message.status == ChatMessage.Status.NOT_DOWNLOADED) {
                    retryDownload(message.id)
                } else {
                    mms.retry(message.id)
                }
            }
        }
    }

    override suspend fun unreadIncoming(threadId: Long): List<String> = withContext(Dispatchers.IO) {
        val args = arrayOf(threadId.toString())
        val unread = mutableListOf<Pair<Long, String>>()
        resolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.DATE),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}", args, null,
        )?.use { c -> while (c.moveToNext()) unread += c.getLong(1) to ChatMessage.messageKey(Kind.SMS, c.getLong(0)) }
        resolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE),
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0 AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX}", args, null,
        )?.use { c -> while (c.moveToNext()) unread += c.getLong(1) * 1000 to ChatMessage.messageKey(Kind.MMS, c.getLong(0)) }
        unread.sortedBy { it.first }.map { it.second }
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
        starred.deleteForThreads(threadIds)
    }

    override suspend fun deleteThreadUpTo(threadId: Long, smsUpTo: Long, mmsUpTo: Long): Boolean {
        val gone = withContext(Dispatchers.IO) {
            fun upTo(id: Long) = arrayOf(threadId.toString(), id.toString())
            runCatching { resolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms._ID} <= ?", upTo(smsUpTo)) }
            runCatching { resolver.delete(Telephony.Mms.CONTENT_URI, "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms._ID} <= ?", upTo(mmsUpTo)) }
            // Deletes nothing, but afterwards Android drops the thread if it's empty, in one step:
            // a message that has just arrived keeps it.
            runCatching { resolver.delete(ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, threadId), "0 = 1", null) }
            heads("${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString())).isEmpty()
        }
        fun gone(key: String): Boolean {
            val id = key.substringAfter(':').toLongOrNull() ?: return false
            return when (key.substringBefore(':')) {
                Kind.SMS.name.lowercase() -> id <= smsUpTo
                Kind.MMS.name.lowercase() -> id <= mmsUpTo
                else -> false
            }
        }
        dao.keysForThread(threadId).filter(::gone).forEach { dao.deleteForMessage(it) }
        starred.keysForThread(threadId).filter(::gone).forEach { starred.unstar(it) }
        return gone
    }

    override suspend fun deleteMessage(message: ChatMessage) {
        withContext(Dispatchers.IO) {
            val table = if (message.kind == Kind.SMS) Telephony.Sms.CONTENT_URI else Telephony.Mms.CONTENT_URI
            runCatching { resolver.delete(ContentUris.withAppendedId(table, message.id), null, null) }
        }
        dao.deleteForMessage(message.key)
        starred.unstar(message.key)
    }

    override suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        fun likeOf(s: String) = "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val like = likeOf(query)
        val recipients = resolver.threadRecipients()
        val hits = mutableListOf<SearchHit>()
        resolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms._ID),
            "${Telephony.Sms.BODY} LIKE ? ESCAPE '\\'", arrayOf(like), "${Telephony.Sms.DATE} DESC LIMIT 50",
        )?.use { c ->
            while (c.moveToNext()) hits += hit(c.getLong(0), recipients, c.getString(1).orEmpty(), c.getLong(2), ChatMessage.messageKey(Kind.SMS, c.getLong(3)))
        }
        val mmsText = HashMap<Long, String>()
        resolver.query(
            Telephony.Mms.Part.CONTENT_URI, arrayOf(Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.TEXT),
            "${Telephony.Mms.Part.CONTENT_TYPE} = 'text/plain' AND ${Telephony.Mms.Part.TEXT} LIKE ? ESCAPE '\\'", arrayOf(like), null,
        )?.use { c -> while (c.moveToNext()) mmsText[c.getLong(0)] = c.getString(1).orEmpty() }
        // Subjects too, looked for both as typed and as Android's MMS code stores them (see MmsCharsets.forStore).
        val mmsSubjects = HashMap<Long, String>()
        resolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.SUBJECT, Telephony.Mms.SUBJECT_CHARSET),
            "(${Telephony.Mms.SUBJECT} LIKE ? ESCAPE '\\' OR ${Telephony.Mms.SUBJECT} LIKE ? ESCAPE '\\')",
            arrayOf(like, likeOf(MmsCharsets.forStore(query))), "${Telephony.Mms.DATE} DESC LIMIT 50",
        )?.use { c ->
            while (c.moveToNext()) {
                // Placeholders ("NoSubject") match nothing anyone looked for.
                val subject = meaningfulSubject(MmsStore.subjectAt(c, 1, 2)) ?: continue
                if (subject.contains(query.trim(), ignoreCase = true)) mmsSubjects[c.getLong(0)] = subject
            }
        }
        val mmsIds = mmsText.keys + mmsSubjects.keys
        if (mmsIds.isNotEmpty()) {
            resolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE),
                "${Telephony.Mms._ID} IN (${mmsIds.joinToString(",")}) AND ${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val words = subjectAndText(mmsSubjects[id], mmsText[id].orEmpty())
                    hits += hit(c.getLong(1), recipients, words, c.getLong(2) * 1000, ChatMessage.messageKey(Kind.MMS, id))
                }
            }
        }
        hits.sortedByDescending { it.timestamp }.take(50)
    }

    override suspend fun recipientsFor(threadId: Long): List<String> = withContext(Dispatchers.IO) {
        resolver.threadRecipients()[threadId].orEmpty()
    }

    override suspend fun recentMedia(limit: Int): List<MediaHit> = withContext(Dispatchers.IO) {
        data class Part(val id: Long, val mmsId: Long, val type: String, val name: String?)
        val parts = mutableListOf<Part>()
        resolver.query(
            Telephony.Mms.Part.CONTENT_URI,
            arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.NAME, Telephony.Mms.Part.FILENAME),
            "${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'image/%' OR ${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'video/%'", null,
            "${Telephony.Mms.Part._ID} DESC LIMIT $limit",
        )?.use { c -> while (c.moveToNext()) parts += Part(c.getLong(0), c.getLong(1), c.getString(2).orEmpty().lowercase(), c.getString(3) ?: c.getString(4)) }
        if (parts.isEmpty()) return@withContext emptyList()
        val messages = HashMap<Long, Pair<Long, Long>>()
        resolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE),
            "${Telephony.Mms._ID} IN (${parts.map { it.mmsId }.distinct().joinToString(",")}) AND ${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}", null, null,
        )?.use { c -> while (c.moveToNext()) messages[c.getLong(0)] = c.getLong(1) to c.getLong(2) * 1000 }
        val recipients = resolver.threadRecipients()
        parts.mapNotNull { part ->
            val (threadId, date) = messages[part.mmsId] ?: return@mapNotNull null
            val people = recipients[threadId].orEmpty()
            MediaHit(
                Attachment(ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI, part.id).toString(), part.type, part.name),
                threadId, people, displayNameFor(people, ::displayName), date, ChatMessage.messageKey(Kind.MMS, part.mmsId),
            )
        }.sortedByDescending { it.timestamp }
    }

    override suspend fun textsWithLinks(limit: Int): List<SearchHit> = withContext(Dispatchers.IO) {
        // A rough cut in SQL; the caller picks the actual links out.
        // The same endings MessageText's link finder knows, so a text it would link isn't missed here.
        val looksLinked = listOf("%http%", "%www.%") + LINK_TLDS.map { "%.$it%" }
        val recipients = resolver.threadRecipients()
        val hits = mutableListOf<SearchHit>()
        resolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms._ID),
            "(" + looksLinked.joinToString(" OR ") { "${Telephony.Sms.BODY} LIKE ?" } + ") AND $NOT_SMS_DRAFT",
            looksLinked.toTypedArray(), "${Telephony.Sms.DATE} DESC LIMIT $limit",
        )?.use { c ->
            while (c.moveToNext()) hits += hit(c.getLong(0), recipients, c.getString(1).orEmpty(), c.getLong(2), ChatMessage.messageKey(Kind.SMS, c.getLong(3)))
        }
        val mmsText = HashMap<Long, String>()
        resolver.query(
            Telephony.Mms.Part.CONTENT_URI, arrayOf(Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.TEXT),
            "${Telephony.Mms.Part.CONTENT_TYPE} = 'text/plain' AND (" + looksLinked.joinToString(" OR ") { "${Telephony.Mms.Part.TEXT} LIKE ?" } + ")",
            looksLinked.toTypedArray(), "${Telephony.Mms.Part._ID} DESC LIMIT $limit",
        )?.use { c -> while (c.moveToNext()) mmsText[c.getLong(0)] = c.getString(1).orEmpty() }
        if (mmsText.isNotEmpty()) {
            resolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE),
                "${Telephony.Mms._ID} IN (${mmsText.keys.joinToString(",")}) AND ${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}", null, null,
            )?.use { c ->
                while (c.moveToNext()) hits += hit(c.getLong(1), recipients, mmsText[c.getLong(0)].orEmpty(), c.getLong(2) * 1000, ChatMessage.messageKey(Kind.MMS, c.getLong(0)))
            }
        }
        hits.sortedByDescending { it.timestamp }.take(limit)
    }

    private fun hit(threadId: Long, recipients: Map<Long, List<String>>, body: String, date: Long, key: String): SearchHit {
        val people = recipients[threadId].orEmpty()
        return SearchHit(
            threadId, people, displayNameFor(people, ::displayName), body, date, key,
            photoUri = people.singleOrNull()?.let(contacts::photoUri),
            members = if (people.size > 1) groupFaces(people.take(GROUP_FACE_CANDIDATES).map { Member(it, displayName(it), contacts.photoUri(it)) }) else emptyList(),
        )
    }

    override suspend fun overrideVerdict(threadId: Long, address: String, action: Action): PreviousVerdict {
        // The inbox goes by the newest incoming text's verdict. One Winnow never classified (from
        // before it was the SMS app, or a classifier that timed out) has none to correct, so the
        // correction gets one of its own: "Filter sender" then really moves the conversation.
        val unclassified = newestIncomingKey(threadId)?.takeIf { dao.existingKeys(listOf(it)).isEmpty() }
        val previous = PreviousVerdict(
            threadId,
            address,
            userAction = dao.userAction(threadId)?.let { runCatching { Action.valueOf(it) }.getOrNull() },
            senderRule = dao.senderRule(normalizeAddress(address)),
            insertedKey = unclassified,
        )
        dao.setUserAction(threadId, action.name)
        unclassified?.let { key ->
            dao.upsert(
                VerdictEntity(
                    messageKey = key, threadId = threadId, address = address, category = null, confidence = 1.0,
                    action = Action.ALLOW.name, sourceKind = "rule", sourceDetail = NOT_CLASSIFIED, model = null,
                    costUsd = 0.0, decidedAt = System.currentTimeMillis(), userAction = action.name,
                    // The user's own decision, not something Winnow did: never news for a daily summary.
                    summarized = true,
                ),
            )
        }
        val rule = if (action == Action.ALLOW) SenderRule.ALWAYS_ALLOW else SenderRule.ALWAYS_FILTER
        dao.upsertSenderRule(SenderRuleEntity(normalizeAddress(address), rule.name, System.currentTimeMillis()))
        // Learning is a bonus; a failure there mustn't undo the user's correction.
        newestIncoming(threadId)?.let { runCatching { onCorrected(threadId, it, action) } }
        return previous
    }

    override suspend fun restoreVerdict(previous: PreviousVerdict) {
        val threadId = previous.threadId
        // Only if it's still the one the correction added: classification may have caught up since.
        previous.insertedKey?.let { key -> if (dao.forKey(key)?.sourceDetail == NOT_CLASSIFIED) dao.deleteForMessage(key) }
        dao.setUserAction(threadId, previous.userAction?.name)
        val address = normalizeAddress(previous.address)
        if (previous.senderRule == null) dao.deleteSenderRule(address)
        else dao.upsertSenderRule(SenderRuleEntity(address, previous.senderRule, System.currentTimeMillis()))
        runCatching {
            val action = previous.userAction
            if (action == null) onUncorrected(threadId) else newestIncoming(threadId)?.let { onCorrected(threadId, it, action) }
        }
    }

    private suspend fun newestIncomingKey(threadId: Long): String? = withContext(Dispatchers.IO) {
        heads("${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString())).firstOrNull { !it.outgoing }?.key
    }

    /** The thread's newest incoming message, as the classifier saw it. */
    private suspend fun newestIncoming(threadId: Long): InboundMessage? = withContext(Dispatchers.IO) {
        val newest = heads("${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString())).firstOrNull { !it.outgoing } ?: return@withContext null
        when (newest.kind) {
            Kind.SMS -> resolver.query(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, newest.id), arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY), null, null, null,
            )?.use { c -> if (c.moveToFirst()) InboundMessage(c.getString(0).orEmpty(), c.getString(1).orEmpty()) else null }
            Kind.MMS -> {
                val words = mmsParts(listOf(newest.id))[newest.id].orEmpty().filter { it.contentType == "text/plain" }.joinToString("\n") { it.text.orEmpty() }
                val text = subjectAndText(mmsSubjects(listOf(newest.id))[newest.id], words)
                InboundMessage(mmsSender(newest.id).orEmpty(), text).takeIf { text.isNotBlank() }
            }
        }
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

    private class ListVerdicts(val byKey: Map<String, StoredVerdict>, val latestByThread: Map<Long, StoredVerdict>)

    private fun verdictsForList(): Flow<ListVerdicts> = dao.observeAll().map { rows ->
        ListVerdicts(
            byKey = rows.associate { it.messageKey to it.toStored(ProviderKind::labelFor) },
            latestByThread = rows.groupBy { it.threadId }.mapValues { (_, r) -> r.maxBy { it.decidedAt }.toStored(ProviderKind::labelFor) },
        )
    }

    // --- Conversation list ---------------------------------------------------------------

    /**
     * Each thread's summary, paired with the key of its newest message when that message is
     * incoming (for its verdict). Reads one row per thread and the unread rows, not every
     * message: with tens of thousands of texts, reading them all took most of a second.
     */
    private fun queryConversations(): List<Pair<ConversationSummary, String?>> {
        val recipients = resolver.threadRecipients()
        val newestByThread = newestPerThread()
        val unread = unreadCounts()
        val snippets = HashMap<Long, String>()
        resolver.query(THREADS_SIMPLE, arrayOf(Telephony.Threads._ID, Telephony.Threads.SNIPPET), null, null, null)?.use { c ->
            while (c.moveToNext()) snippets[c.getLong(0)] = c.getString(1).orEmpty()
        }
        val mmsText = mmsSnippets(newestByThread.values.filter { it.kind == Kind.MMS }.map { it.id })

        return newestByThread.mapNotNull { (threadId, newest) ->
            val people = recipients[threadId]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
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
                unreadCount = unread[threadId] ?: 0,
                verdict = null,
                photoUri = people.singleOrNull()?.let(contacts::photoUri),
                notSent = newest.failed,
                members = if (people.size > 1) groupFaces(people.take(GROUP_FACE_CANDIDATES).map { Member(it, displayName(it), contacts.photoUri(it)) }) else emptyList(),
            )
            // A placeholder has no verdict yet, so the thread keeps its latest one instead of losing it.
            summary to newest.key.takeIf { !newest.outgoing && !newest.placeholder }
        }.sortedByDescending { it.first.timestamp }
    }

    /** The newest SMS or MMS of every thread, drafts aside, from the provider's own per-thread query. */
    private fun newestPerThread(): Map<Long, Head> {
        val newest = HashMap<Long, Head>()
        resolver.query(
            MMS_SMS_CONVERSATIONS,
            // No transport_type here: some providers lack the column. An SMS row has a type, an MMS row a msg_box.
            arrayOf("_id", "thread_id", "normalized_date", Telephony.Sms.TYPE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val threadId = c.getLong(1)
                val isSms = !c.isNull(3)
                val incoming = if (isSms) c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX else c.getInt(4) == Telephony.Mms.MESSAGE_BOX_INBOX
                val placeholder = !isSms && !c.isNull(5) && c.getInt(5) == MESSAGE_TYPE_NOTIFICATION_IND
                val failed = if (isSms) c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_FAILED else c.getInt(4) == Telephony.Mms.MESSAGE_BOX_FAILED
                val head = Head(
                    if (isSms) Kind.SMS else Kind.MMS, c.getLong(0), threadId, c.getLong(2), outgoing = !incoming, unread = false,
                    placeholder = placeholder, failed = failed,
                )
                // Two messages can share a thread's newest timestamp; keep one.
                if ((newest[threadId]?.date ?: Long.MIN_VALUE) < head.date) newest[threadId] = head
            }
        }
        return newest
    }

    /** Unread incoming messages per thread. */
    private fun unreadCounts(): Map<Long, Int> {
        val counts = HashMap<Long, Int>()
        fun count(uri: Uri, selection: String) {
            resolver.query(uri, arrayOf("thread_id"), selection, null, null)?.use { c ->
                while (c.moveToNext()) counts.merge(c.getLong(0), 1, Int::plus)
            }
        }
        count(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}")
        count(Telephony.Mms.CONTENT_URI, "${Telephony.Mms.READ} = 0 AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX}")
        return counts
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

    // --- One conversation ----------------------------------------------------------------

    private fun queryThread(threadId: Long): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.STATUS, Telephony.Sms.SUBSCRIPTION_ID),
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
                    subscriptionId = if (c.isNull(6)) null else c.getInt(6).takeIf { it >= 0 },
                )
            }
        }
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(
                Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.SUBJECT,
                Telephony.Mms.STATUS, Telephony.Mms.SUBSCRIPTION_ID, Telephony.Mms.MESSAGE_SIZE, Telephony.Mms.SUBJECT_CHARSET,
            ),
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
                        // A sent one a delivery report says arrived.
                        c.getInt(3) != MESSAGE_TYPE_NOTIFICATION_IND && box == Telephony.Mms.MESSAGE_BOX_SENT && c.getInt(5) == MmsStore.DELIVERED ->
                            ChatMessage.Status.DELIVERED
                        c.getInt(3) != MESSAGE_TYPE_NOTIFICATION_IND -> mmsStatus(box)
                        c.getInt(5) == MmsStore.STATUS_DOWNLOAD_FAILED -> ChatMessage.Status.DOWNLOAD_FAILED
                        c.getInt(5) == MmsStore.STATUS_DEFERRED -> ChatMessage.Status.NOT_DOWNLOADED
                        else -> ChatMessage.Status.DOWNLOADING
                    },
                    downloadSize = c.getLong(7),
                    verdict = null,
                    kind = Kind.MMS,
                    subject = meaningfulSubject(MmsStore.subjectAt(c, 4, 8)),
                    subscriptionId = if (c.isNull(6)) null else c.getInt(6).takeIf { it >= 0 },
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

    /** A one-line preview per MMS: its text, else its subject, else what kind of attachment it carries. */
    private fun mmsSnippets(mmsIds: List<Long>): Map<Long, String> {
        if (mmsIds.isEmpty()) return emptyMap()
        val parts = mmsParts(mmsIds)
        val wordless = parts.filterValues { p -> p.none { it.contentType == "text/plain" && !it.text.isNullOrBlank() } }.keys
        val subjects = mmsSubjects(wordless.toList())
        return parts.mapValues { (id, parts) ->
            parts.firstOrNull { it.contentType == "text/plain" }?.text?.takeIf { it.isNotBlank() }
                ?: subjects[id]
                ?: attachmentSummary(parts.map { it.contentType }.filter { it != "text/plain" && it != "application/smil" })
        }
    }

    /** The real subjects (see meaningfulSubject) of [mmsIds] that have one. */
    private fun mmsSubjects(mmsIds: List<Long>): Map<Long, String> {
        if (mmsIds.isEmpty()) return emptyMap()
        val subjects = HashMap<Long, String>()
        mmsIds.chunked(500).forEach { chunk ->
            resolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.SUBJECT, Telephony.Mms.SUBJECT_CHARSET),
                "${Telephony.Mms._ID} IN (${chunk.joinToString(",")})", null, null,
            )?.use { c -> while (c.moveToNext()) meaningfulSubject(MmsStore.subjectAt(c, 1, 2))?.let { subjects[c.getLong(0)] = it } }
        }
        return subjects
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

        /** PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND: an MMS announced but not yet downloaded. */
        const val MESSAGE_TYPE_NOTIFICATION_IND = 0x82
        /** The reason on a verdict a correction had to add for a text never classified. */
        private const val NOT_CLASSIFIED = "Not classified"

        /** PduHeaders.FROM, as stored in the MMS addr table. */
        const val ADDR_TYPE_FROM = 0x89

        private const val TAG = "WinnowStore"

        /** Drafts other SMS apps left in the store aren't messages. */
        /** Endings a bare domain in a text can have; matches MessageText's link finder. */
        private val LINK_TLDS = listOf("com", "net", "org", "io", "co", "me", "us", "app", "dev", "info", "biz", "top", "vip", "xyz", "ly", "gl", "gov", "edu", "shop", "click", "link")
        private const val NOT_SMS_DRAFT = "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}"
    }
}
