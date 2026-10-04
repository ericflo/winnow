package com.ericflo.winnow.backup

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Log
import android.webkit.MimeTypeMap
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.classify.Learner
import com.ericflo.winnow.data.db.ConversationStateDao
import com.ericflo.winnow.data.db.CorrectionDao
import com.ericflo.winnow.data.db.CorrectionEntity
import com.ericflo.winnow.data.db.ConversationStateEntity
import com.ericflo.winnow.data.db.ScheduledMessageDao
import com.ericflo.winnow.data.db.SenderRuleEntity
import com.ericflo.winnow.data.db.VerdictDao
import com.ericflo.winnow.data.splitAddresses
import com.ericflo.winnow.data.threadRecipients
import com.ericflo.winnow.mms.ContentTypes
import com.ericflo.winnow.mms.MmsPart
import com.ericflo.winnow.mms.Smil
import com.ericflo.winnow.sms.MessageScheduler
import com.ericflo.winnow.sms.MmsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import kotlin.coroutines.cancellation.CancellationException

sealed interface BackupStatus {
    data object Idle : BackupStatus

    /** [total] is 0 until the size of the job is known. */
    data class Working(val label: String, val done: Int = 0, val total: Int = 0) : BackupStatus

    /** A backup file was opened and is waiting for the user to confirm the restore. */
    data class Ready(val uri: Uri, val summary: BackupSummary) : BackupStatus

    data class Done(val message: String) : BackupStatus
    data class Failed(val message: String) : BackupStatus
}

data class BackupSummary(
    val createdAt: Long,
    val conversations: Int,
    val messages: Int,
    val senderRules: Int,
    val hasSettings: Boolean,
)

/**
 * Backs up to, and restores from, a file the user picks. Restoring only adds: messages already
 * on the phone are recognized and skipped, and Winnow's state for a conversation or sender is
 * only filled in where the phone has none. Jobs run in the app scope, so they finish even if
 * the user leaves Settings.
 */
class BackupManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val verdicts: VerdictDao,
    private val states: ConversationStateDao,
    private val scheduledDao: ScheduledMessageDao,
    private val settings: SettingsRepository,
    private val mmsStore: MmsStore,
    private val scheduler: MessageScheduler,
    private val corrections: CorrectionDao,
    private val learner: Learner,
    /** Restoring messages writes the SMS store, which only the default SMS app may do. */
    private val canWriteMessages: () -> Boolean,
) {
    private val resolver = context.contentResolver
    private val _status = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val status: StateFlow<BackupStatus> = _status.asStateFlow()
    private var job: Job? = null

    fun canReadMessages(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun export(uri: Uri) = start("Couldn't back up") { exportTo(uri) }

    /** Reads a backup's summary so the user can confirm before anything is written. */
    fun open(uri: Uri) = start("Couldn't read that file") {
        _status.value = BackupStatus.Working("Reading backup")
        val backup = (resolver.openInputStream(uri) ?: error("The file couldn't be opened")).use(BackupArchive::peek)
        _status.value = BackupStatus.Ready(
            uri,
            BackupSummary(backup.createdAt, backup.conversations.size, backup.messageCount, backup.senderRules.size, backup.settings != null),
        )
    }

    fun restore(uri: Uri, includeSettings: Boolean) = start("Couldn't restore") { restoreFrom(uri, includeSettings) }

    /** Clears a finished, failed or unconfirmed job's status. */
    fun dismiss() {
        if (job?.isActive != true) _status.value = BackupStatus.Idle
    }

    private fun start(failure: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, failure, e)
                _status.value = BackupStatus.Failed(listOfNotNull(failure, e.message).joinToString(": "))
            }
        }
    }

    // --- Export ----------------------------------------------------------------------------

    private suspend fun exportTo(uri: Uri) {
        _status.value = BackupStatus.Working("Gathering messages")
        val media = HashMap<String, Long>()
        val backup = WinnowBackup(
            createdAt = System.currentTimeMillis(),
            settings = settings.current().toBackup(),
            senderRules = verdicts.allSenderRules().map { SenderRuleBackup(it.address, it.rule, it.createdAt) },
            conversations = if (canReadMessages()) readConversations(media) else emptyList(),
            scheduled = scheduledDao.all().map { ScheduledBackup(splitAddresses(it.recipients), it.body, it.sendAt) },
            corrections = corrections.all().map { c ->
                CorrectionBackup(c.buckets.split(',').mapNotNull(String::toIntOrNull), c.label, c.featurizerVersion, c.createdAt)
            },
        )
        var saved = 0
        _status.value = BackupStatus.Working("Saving the backup", 0, media.size)
        (resolver.openOutputStream(uri, "wt") ?: error("The file couldn't be opened")).use { output ->
            BackupArchive.write(output, backup) { part ->
                val partId = media[part.file] ?: return@write null
                _status.value = BackupStatus.Working("Saving photos and videos", ++saved, media.size)
                resolver.openInputStream(ContentUris.withAppendedId(Mms.Part.CONTENT_URI, partId))
            }
        }
        _status.value = BackupStatus.Done(
            if (backup.conversations.isEmpty()) {
                "Backed up settings and ${plural(backup.senderRules.size, "sender rule")}. Messages weren't included because Winnow can't read them yet."
            } else {
                "Backed up ${plural(backup.messageCount, "message")} in ${plural(backup.conversations.size, "conversation")}."
            },
        )
    }

    private suspend fun readConversations(media: MutableMap<String, Long>): List<ConversationBackup> {
        val recipients = resolver.threadRecipients()
        val verdictsByKey = verdicts.all().associateBy { it.messageKey }
        val byThread = HashMap<Long, MutableList<MessageBackup>>()

        resolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.STATUS, Sms.READ),
            "${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val threadId = c.getLong(1)
                val type = c.getInt(5)
                val incoming = type == Sms.MESSAGE_TYPE_INBOX
                val address = c.getString(2)
                byThread.getOrPut(threadId, ::mutableListOf) += MessageBackup(
                    kind = KIND_SMS,
                    date = c.getLong(4),
                    outgoing = !incoming,
                    sender = address.takeIf { incoming },
                    body = c.getString(3).orEmpty(),
                    status = when {
                        incoming -> null
                        type == Sms.MESSAGE_TYPE_SENT -> "delivered".takeIf { c.getInt(6) == Sms.STATUS_COMPLETE }
                        // Outbox and queued can't resume on another phone; they restore as failed, ready to retry.
                        else -> STATUS_FAILED
                    },
                    read = !incoming || c.getInt(7) != 0,
                    to = address.takeIf { !incoming && recipients[threadId].orEmpty().size > 1 },
                    verdict = verdictsByKey[ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0))]?.toBackup(),
                )
            }
        }

        // Downloaded and sent MMS. One that was announced but never downloaded has nothing to keep.
        data class MmsRow(val id: Long, val threadId: Long, val date: Long, val box: Int, val subject: String?, val read: Boolean)
        val rows = mutableListOf<MmsRow>()
        resolver.query(
            Mms.CONTENT_URI,
            arrayOf(Mms._ID, Mms.THREAD_ID, Mms.DATE, Mms.MESSAGE_BOX, Mms.SUBJECT, Mms.READ),
            "${Mms.MESSAGE_BOX} != ${Mms.MESSAGE_BOX_DRAFTS} AND ${Mms.MESSAGE_TYPE} != ${MmsStore.MESSAGE_TYPE_NOTIFICATION_IND}", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                rows += MmsRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getString(4)?.takeIf { it.isNotBlank() }, c.getInt(5) != 0)
            }
        }
        val parts = if (rows.isEmpty()) emptyMap() else mmsParts(null)
        for (row in rows) {
            val own = parts[row.id].orEmpty()
            val incoming = row.box == Mms.MESSAGE_BOX_INBOX
            byThread.getOrPut(row.threadId, ::mutableListOf) += MessageBackup(
                kind = KIND_MMS,
                date = row.date * 1000,
                outgoing = !incoming,
                sender = if (incoming) mmsStore.sender(row.id) else null,
                body = own.textBody(),
                subject = row.subject,
                status = STATUS_FAILED.takeIf { row.box == Mms.MESSAGE_BOX_FAILED || row.box == Mms.MESSAGE_BOX_OUTBOX },
                read = !incoming || row.read,
                parts = own.media().map { part ->
                    val file = "${part.id}.${MimeTypeMap.getSingleton().getExtensionFromMimeType(part.contentType) ?: "bin"}"
                    media[file] = part.id
                    PartBackup(part.contentType, part.name, file)
                },
                verdict = verdictsByKey[ChatMessage.messageKey(ChatMessage.Kind.MMS, row.id)]?.toBackup(),
            )
        }

        val statesByThread = states.all().associateBy { it.threadId }
        return byThread.mapNotNull { (threadId, messages) ->
            val people = recipients[threadId]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val state = statesByThread[threadId]
            ConversationBackup(
                recipients = people,
                pinned = state?.pinned == true,
                archived = state?.archived == true,
                muted = state?.muted == true,
                draft = state?.draft,
                messages = messages.sortedBy { it.date },
            )
        }
    }

    // --- Restore ---------------------------------------------------------------------------

    private suspend fun restoreFrom(uri: Uri, includeSettings: Boolean) {
        _status.value = BackupStatus.Working("Reading backup")
        val spool = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            val backup = (resolver.openInputStream(uri) ?: error("The file couldn't be opened")).use { input ->
                BackupArchive.read(input) { name, stream -> File(spool, name).outputStream().use { stream.copyTo(it) } }
            }
            val restored = mutableListOf<String>()

            if (includeSettings && backup.settings != null) {
                settings.update { it.restoring(backup.settings) }
                restored += "your settings"
            }

            val known = verdicts.allSenderRules().mapTo(HashSet()) { it.address }
            val rules = backup.senderRules.filter { it.address !in known }
            rules.forEach { verdicts.upsertSenderRule(SenderRuleEntity(it.address, it.rule, it.createdAt)) }
            if (rules.isNotEmpty()) restored += plural(rules.size, "sender rule")

            val learned = corrections.all().mapTo(HashSet()) { it.buckets to it.label }
            val lessons = backup.corrections.filter { (it.buckets.joinToString(",") to it.label) !in learned }
            lessons.forEach { corrections.insert(CorrectionEntity(threadId = null, buckets = it.buckets.joinToString(","), label = it.label, featurizerVersion = it.featurizerVersion, createdAt = it.createdAt)) }
            if (lessons.isNotEmpty()) {
                learner.reload()
                restored += plural(lessons.size, "correction")
            }

            val message = if (!canWriteMessages()) {
                "Messages weren't restored because Winnow isn't your SMS app." + also(restored)
            } else {
                val (added, present) = restoreMessages(backup, spool)
                val scheduled = restoreScheduled(backup.scheduled)
                if (scheduled > 0) restored += plural(scheduled, "scheduled message")
                when {
                    added == 0 && present == 0 -> "The backup had no messages."
                    added == 0 -> if (present == 1) "Its one message was already on this phone." else "All ${format(present)} messages were already on this phone."
                    present == 0 -> "Restored ${plural(added, "message")}."
                    else -> "Restored ${plural(added, "message")} (${format(present)} were already here)."
                } + also(restored)
            }
            _status.value = BackupStatus.Done(message)
        } finally {
            spool.deleteRecursively()
        }
    }

    /**
     * Adds the messages this phone doesn't have yet, and Winnow's verdicts for messages it has
     * but never classified (after a reinstall, say). Returns (added, already present).
     */
    private suspend fun restoreMessages(backup: WinnowBackup, spool: File): Pair<Int, Int> {
        val total = backup.messageCount
        val classified = verdicts.all().mapTo(HashSet()) { it.messageKey }
        var done = 0
        var added = 0
        var present = 0
        for (conversation in backup.conversations) {
            val threadId = Telephony.Threads.getOrCreateThreadId(context, conversation.recipients.toSet())
            val existing = existingMessages(threadId)
            val (here, missing) = conversation.messages.partition { it.fingerprint in existing }

            here.forEach { m ->
                val key = existing.getValue(m.fingerprint)
                if (key !in classified) restoreVerdict(m, conversation, threadId, key)
            }
            present += here.size
            done += here.size

            missing.filter { it.kind == KIND_SMS }.chunked(SMS_BATCH).forEach { chunk ->
                val ops = chunk.map { m -> ContentProviderOperation.newInsert(Sms.CONTENT_URI).withValues(smsValues(threadId, conversation, m)).build() }
                val results = resolver.applyBatch(Sms.CONTENT_URI.authority!!, ArrayList(ops))
                chunk.zip(results).forEach { (m, result) ->
                    val id = result.uri?.let(ContentUris::parseId) ?: return@forEach
                    added++
                    restoreVerdict(m, conversation, threadId, ChatMessage.messageKey(ChatMessage.Kind.SMS, id))
                }
                done += chunk.size
                _status.value = BackupStatus.Working("Restoring messages", done, total)
            }

            missing.filter { it.kind == KIND_MMS }.forEach { m ->
                val uri = insertMms(threadId, conversation, m, spool)
                if (uri != null) {
                    added++
                    restoreVerdict(m, conversation, threadId, ChatMessage.messageKey(ChatMessage.Kind.MMS, ContentUris.parseId(uri)))
                }
                _status.value = BackupStatus.Working("Restoring messages", ++done, total)
            }
            _status.value = BackupStatus.Working("Restoring messages", done, total)

            val state = ConversationStateEntity(threadId, conversation.pinned, conversation.archived, conversation.muted, conversation.draft)
            if (state != ConversationStateEntity(threadId) && states.get(threadId) == null) states.upsert(state)
        }
        return added to present
    }

    private fun smsValues(threadId: Long, conversation: ConversationBackup, m: MessageBackup) = ContentValues().apply {
        put(Sms.THREAD_ID, threadId)
        put(Sms.ADDRESS, m.sender ?: m.to ?: conversation.recipients.first())
        put(Sms.BODY, m.body)
        put(Sms.DATE, m.date)
        put(
            Sms.TYPE,
            when {
                !m.outgoing -> Sms.MESSAGE_TYPE_INBOX
                m.status == STATUS_FAILED -> Sms.MESSAGE_TYPE_FAILED
                else -> Sms.MESSAGE_TYPE_SENT
            },
        )
        put(Sms.STATUS, if (m.status == "delivered") Sms.STATUS_COMPLETE else Sms.STATUS_NONE)
        put(Sms.READ, if (m.read) 1 else 0)
        // Seen, so restored messages don't announce themselves as new.
        put(Sms.SEEN, 1)
    }

    private fun insertMms(threadId: Long, conversation: ConversationBackup, m: MessageBackup, spool: File): Uri? {
        val parts = buildList {
            if (m.body.isNotEmpty()) add(MmsPart.plainText(m.body))
            m.parts.forEach { p ->
                val file = File(spool, p.file).takeIf { it.isFile } ?: return@forEach
                add(MmsPart(contentType = p.contentType, data = file.readBytes(), name = p.name ?: p.file, contentLocation = p.name ?: p.file))
            }
        }
        if (parts.isEmpty()) return null
        val box = when {
            !m.outgoing -> Mms.MESSAGE_BOX_INBOX
            m.status == STATUS_FAILED -> Mms.MESSAGE_BOX_FAILED
            else -> Mms.MESSAGE_BOX_SENT
        }
        // Received group messages went to everyone else in the conversation; sent ones to everyone.
        val to = if (m.outgoing) conversation.recipients else conversation.recipients.filter { it != m.sender }
        return mmsStore.insertRestored(threadId, box, m.date / 1000, m.read, m.subject, m.sender, to, listOf(Smil.forParts(parts)) + parts)
    }

    private suspend fun restoreVerdict(m: MessageBackup, conversation: ConversationBackup, threadId: Long, key: String) {
        val entity = m.verdict?.toEntity(key, threadId, m.sender ?: m.to ?: conversation.recipients.first()) ?: return
        verdicts.upsert(entity)
    }

    /** Scheduled texts still in the future, and not already waiting here. */
    private suspend fun restoreScheduled(scheduled: List<ScheduledBackup>): Int {
        val now = System.currentTimeMillis()
        val waiting = scheduledDao.all().mapTo(HashSet()) { Triple(splitAddresses(it.recipients).toSet(), it.body, it.sendAt) }
        val fresh = scheduled.filter { it.sendAt > now && it.recipients.isNotEmpty() && Triple(it.recipients.toSet(), it.body, it.sendAt) !in waiting }
        fresh.forEach { s ->
            scheduler.schedule(Telephony.Threads.getOrCreateThreadId(context, s.recipients.toSet()), s.recipients, s.body, s.sendAt)
        }
        return fresh.size
    }

    /** What's already in a thread: [MessageBackup.fingerprint] → message key. */
    private fun existingMessages(threadId: Long): Map<String, String> {
        val found = HashMap<String, String>()
        val args = arrayOf(threadId.toString())
        resolver.query(
            Sms.CONTENT_URI, arrayOf(Sms._ID, Sms.DATE, Sms.TYPE, Sms.BODY),
            "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}", args, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val print = MessageBackup.fingerprint(KIND_SMS, c.getLong(1), c.getInt(2) != Sms.MESSAGE_TYPE_INBOX, c.getString(3).orEmpty(), 0)
                found[print] = ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0))
            }
        }
        val mms = mutableListOf<Triple<Long, Long, Boolean>>()
        resolver.query(
            Mms.CONTENT_URI, arrayOf(Mms._ID, Mms.DATE, Mms.MESSAGE_BOX),
            "${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_BOX} != ${Mms.MESSAGE_BOX_DRAFTS}", args, null,
        )?.use { c -> while (c.moveToNext()) mms += Triple(c.getLong(0), c.getLong(1) * 1000, c.getInt(2) != Mms.MESSAGE_BOX_INBOX) }
        if (mms.isNotEmpty()) {
            val parts = mmsParts("${Mms.Part.MSG_ID} IN (${mms.joinToString(",") { it.first.toString() }})")
            mms.forEach { (id, date, outgoing) ->
                val own = parts[id].orEmpty()
                found[MessageBackup.fingerprint(KIND_MMS, date, outgoing, own.textBody(), own.media().size)] = ChatMessage.messageKey(ChatMessage.Kind.MMS, id)
            }
        }
        return found
    }

    private data class PartRow(val id: Long, val contentType: String, val text: String?, val name: String?)

    private fun mmsParts(selection: String?): Map<Long, List<PartRow>> {
        val parts = HashMap<Long, MutableList<PartRow>>()
        resolver.query(
            Mms.Part.CONTENT_URI,
            arrayOf(Mms.Part._ID, Mms.Part.MSG_ID, Mms.Part.CONTENT_TYPE, Mms.Part.TEXT, Mms.Part.NAME, Mms.Part.FILENAME),
            selection, null, "${Mms.Part.SEQ} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                parts.getOrPut(c.getLong(1), ::mutableListOf) +=
                    PartRow(c.getLong(0), c.getString(2).orEmpty().lowercase(), c.getString(3), c.getString(4) ?: c.getString(5))
            }
        }
        return parts
    }

    private fun List<PartRow>.textBody() = filter { it.contentType == ContentTypes.TEXT_PLAIN }.joinToString("\n") { it.text.orEmpty() }

    private fun List<PartRow>.media() = filter { it.contentType != ContentTypes.TEXT_PLAIN && it.contentType != ContentTypes.SMIL }

    private companion object {
        const val TAG = "WinnowBackup"
        const val KIND_SMS = "sms"
        const val KIND_MMS = "mms"
        const val STATUS_FAILED = "failed"
        const val SMS_BATCH = 250

        fun format(n: Int): String = NumberFormat.getIntegerInstance().format(n)

        fun plural(n: Int, noun: String) = "${format(n)} $noun${if (n == 1) "" else "s"}"

        fun also(restored: List<String>) = if (restored.isEmpty()) "" else " Also restored ${restored.joinToString(" and ")}."
    }
}
