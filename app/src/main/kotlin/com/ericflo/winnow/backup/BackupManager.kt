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
import android.util.Xml
import com.ericflo.winnow.data.normalizeAddress
import android.provider.DocumentsContract
import org.xmlpull.v1.XmlPullParser
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
import com.ericflo.winnow.data.db.StarredDao
import com.ericflo.winnow.data.db.StarredEntity
import com.ericflo.winnow.data.db.VerdictDao
import com.ericflo.winnow.data.splitAddresses
import com.ericflo.winnow.data.threadRecipients
import com.ericflo.winnow.data.DraftAttachments
import com.ericflo.winnow.data.OutgoingAttachment
import com.ericflo.winnow.data.db.ReminderEntity
import com.ericflo.winnow.data.subjectAndText
import com.ericflo.winnow.data.attachmentSummary
import com.ericflo.winnow.data.joinAddresses
import com.ericflo.winnow.mms.ContentTypes
import com.ericflo.winnow.mms.MmsCharsets
import com.ericflo.winnow.mms.MmsPart
import com.ericflo.winnow.mms.Smil
import com.ericflo.winnow.sms.MessageScheduler
import com.ericflo.winnow.sms.MmsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

sealed interface BackupStatus {
    data object Idle : BackupStatus

    /** [total] is 0 until the size of the job is known. */
    data class Working(val label: String, val done: Int = 0, val total: Int = 0) : BackupStatus

    /** A backup file was opened and is waiting for the user to confirm the restore. */
    data class Ready(val uri: Uri, val summary: BackupSummary) : BackupStatus

    /** A password-protected backup, made with another password (or on another phone): what's its password? [wrong] after a try that didn't open it. */
    data class NeedsPassword(val uri: Uri, val wrong: Boolean = false) : BackupStatus

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
    private val starred: StarredDao,
    private val learner: Learner,
    /** Restoring messages writes the SMS store, which only the default SMS app may do. */
    private val canWriteMessages: () -> Boolean,
    /** This phone's numbers, kept out of group conversations read from other apps' backups. */
    private val ownNumbers: () -> Set<String> = { emptySet() },
    /** Where drafts keep their attachments; null leaves them out. */
    private val drafts: DraftAttachments? = null,
    /** "Remind me"s, kept with their messages; null leaves them out. */
    private val reminders: com.ericflo.winnow.notify.Reminders? = null,
    /** Settings → Backup password: when set, new backups are protected with it. */
    private val password: BackupPassword? = null,
) {
    /** Keys for protected files the user gave the password of, while they're being restored. */
    private val unlocked = java.util.concurrent.ConcurrentHashMap<Uri, BackupCrypto.Key>()

    /** A protected backup with no key at hand (see [BackupStatus.NeedsPassword]). */
    private class PasswordNeeded : java.io.IOException("This backup needs its password")

    /**
     * [uri]'s backup to read: the zip itself, or (password-protected) what it opens to, with this
     * phone's password if that's what it was made with, else the one the user gave for it.
     */
    private fun openArchive(uri: Uri): java.io.InputStream {
        val raw = (resolver.openInputStream(uri) ?: error("The file couldn't be opened")).buffered()
        try {
            raw.mark(BackupCrypto.HEAD_BYTES)
            val head = ByteArray(BackupCrypto.HEAD_BYTES).also { buf -> var n = 0; while (n < buf.size) { val r = raw.read(buf, n, buf.size - n); if (r < 0) break; n += r } }
            raw.reset()
            if (!BackupCrypto.isProtected(head)) return raw
            val header = BackupCrypto.readHeader(raw)
            // A key given for this file earlier counts only if the file is still the one it was for.
            val key = unlocked[uri]?.takeIf { it.params.sameAs(header.params) } ?: password?.keyFor(header.params) ?: throw PasswordNeeded()
            return BackupCrypto.decrypting(raw, header, key)
        } catch (e: Throwable) {
            raw.close()
            throw e
        }
    }
    private val resolver = context.contentResolver
    private val _status = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val status: StateFlow<BackupStatus> = _status.asStateFlow()
    private var job: Job? = null

    fun canReadMessages(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun export(uri: Uri) = start("Couldn't back up") { exportTo(uri) }

    /** Whether backups made now are password-protected (see BackupPassword). */
    fun protectsBackups(): Boolean = password?.isSet?.value == true

    /**
     * A backup written without touching [status], for automatic backups that run while nobody's
     * watching. Returns the same summary a manual backup shows.
     */
    suspend fun exportQuietly(uri: Uri): String {
        var summary = ""
        exportTo(uri) { if (it is BackupStatus.Done) summary = it.message }
        return summary
    }

    /** Reads a backup's summary so the user can confirm before anything is written. */
    fun open(uri: Uri) = start("Couldn't read that file") {
        _status.value = BackupStatus.Working("Reading backup")
        val backup = try {
            openArchive(uri).use(BackupArchive::peek)
        } catch (_: PasswordNeeded) {
            _status.value = BackupStatus.NeedsPassword(uri)
            return@start
        } catch (_: BackupCrypto.WrongPasswordException) {
            // A key on hand that isn't this file's after all: ask.
            _status.value = BackupStatus.NeedsPassword(uri)
            return@start
        }
        _status.value = BackupStatus.Ready(
            uri,
            BackupSummary(backup.createdAt, backup.conversations.size, backup.messageCount, backup.senderRules.size, backup.settings != null),
        )
    }

    /** The password for a protected backup [open] asked about: on to the summary, or asked again. */
    fun unlock(uri: Uri, typed: CharArray) {
        val started = start("Couldn't read that file") {
            try {
                _status.value = BackupStatus.Working("Checking the password")
                val header = (resolver.openInputStream(uri) ?: error("The file couldn't be opened")).buffered().use(BackupCrypto::readHeader)
                val key = BackupCrypto.deriveKey(typed, header.params)
                if (!BackupCrypto.opens(header, key)) {
                    _status.value = BackupStatus.NeedsPassword(uri, wrong = true)
                    return@start
                }
                unlocked[uri] = key
            } finally {
                typed.fill(' ')
            }
            val backup = openArchive(uri).use(BackupArchive::peek)
            _status.value = BackupStatus.Ready(
                uri,
                BackupSummary(backup.createdAt, backup.conversations.size, backup.messageCount, backup.senderRules.size, backup.settings != null),
            )
        }
        if (!started) typed.fill(' ')
    }

    fun restore(uri: Uri, includeSettings: Boolean) = start("Couldn't restore") { restoreFrom(uri, includeSettings) }

    /** Adds the messages from an SMS Backup & Restore XML file that this phone doesn't have yet. */
    fun importSmsBackupRestore(uri: Uri) = start("Couldn't import that file") {
        if (!canWriteMessages()) {
            _status.value = BackupStatus.Done("Messages can only be imported once Winnow is your SMS app.")
            return@start
        }
        _status.value = BackupStatus.Working("Reading messages")
        val spool = File(context.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        try {
            val (backup, skipped) = (resolver.openInputStream(uri) ?: error("The file couldn't be opened")).use { input ->
                val parser = Xml.newPullParser().apply {
                    // Real backups have no DTD; not processing one means no entity expansion ("billion laughs").
                    runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
                    setInput(input, null)
                }
                SmsBackupRestoreXml.read(parser, spool, ownNumbers()) { done, total ->
                    if (done % 200 == 0) _status.value = BackupStatus.Working("Reading messages", done, maxOf(total, done))
                }
            }
            if (backup.messageCount == 0) {
                _status.value = BackupStatus.Done("That file had no messages Winnow could read. Is it an SMS Backup & Restore backup?")
                return@start
            }
            val (added, present) = restoreMessages(backup, spool)
            val note = if (skipped > 0) " ${plural(skipped, "draft or unreadable message")} skipped." else ""
            _status.value = BackupStatus.Done(
                when {
                    added == 0 -> allPresent(present) + note
                    present == 0 -> "Imported ${plural(added, "message")}.$note"
                    else -> "Imported ${plural(added, "message")} (${alreadyHere(present)}).$note"
                },
            )
        } finally {
            spool.deleteRecursively()
        }
    }

    /** Clears a finished, failed or unconfirmed job's status. */
    /** Writes every text and picture message as an SMS Backup & Restore file, for other apps (or Winnow) to import. */
    fun exportSmsBackupRestore(uri: Uri) = start("Couldn't export") {
        if (!canReadMessages()) {
            _status.value = BackupStatus.Done("Winnow can't read your messages yet, so there's nothing to export.")
            return@start
        }
        val job = coroutineContext
        _status.value = BackupStatus.Working("Gathering messages")
        val media = HashMap<String, Uri>()
        val conversations = readConversations(media)
        val total = conversations.sumOf { it.messages.size }
        try {
            writeSmsBackupRestore(uri, conversations, media, job)
        } catch (e: Throwable) {
            // No half-written file left behind looking like a backup.
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw e
        }
        _status.value = BackupStatus.Done(
            "Exported ${plural(total, "message")} in ${plural(conversations.size, "conversation")}. " +
                "SMS Backup & Restore and most texting apps can import the file.",
        )
    }

    private fun writeSmsBackupRestore(uri: Uri, conversations: List<ConversationBackup>, media: Map<String, Uri>, job: kotlin.coroutines.CoroutineContext) {
        (resolver.openOutputStream(uri, "wt") ?: error("The file couldn't be opened")).buffered().use { output ->
            val serializer = Xml.newSerializer().apply { setOutput(output, "UTF-8") }
            SmsBackupRestoreXml.write(
                serializer,
                conversations,
                ownNumber = ownNumbers().firstOrNull(),
                media = { part ->
                    job.ensureActive()
                    media[part.file]?.let { from ->
                        runCatching { resolver.openInputStream(from)?.use { it.readBytes() } }.getOrNull()
                    }
                },
            ) { done, all ->
                if (done % 100 == 0) _status.value = BackupStatus.Working("Exporting messages", done, all)
            }
        }
    }

    fun dismiss() {
        // Mid-job the key may still be needed; the restore drops it when it's done.
        if (job?.isActive != true) {
            unlocked.clear()
            _status.value = BackupStatus.Idle
        }
    }

    /** Runs [block] unless something's running already; whether it started. */
    private fun start(failure: String, block: suspend () -> Unit): Boolean {
        if (job?.isActive == true) return false
        job = scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // A huge attachment or a hostile file; the screen mustn't stay stuck on "Working".
                Log.w(TAG, failure, e)
                _status.value = BackupStatus.Failed("$failure: it needs more memory than this phone can give it")
            } catch (e: Exception) {
                Log.w(TAG, failure, e)
                _status.value = BackupStatus.Failed(listOfNotNull(failure, e.message).joinToString(": "))
            }
        }
        return true
    }

    // --- Export ----------------------------------------------------------------------------

    private suspend fun exportTo(uri: Uri, report: (BackupStatus) -> Unit = { _status.value = it }) {
        // The archive is written with blocking calls; checking this between files lets a stopped
        // automatic backup actually stop.
        val job = coroutineContext
        // With a backup password, everything in the file is sealed with it (see BackupCrypto).
        // Known first, so a key that can't be read fails before any work, and before the file is touched.
        val key = password?.takeIf { it.isSet.value }?.let { it.key() ?: error("The backup password can't be read on this phone; set it again in Settings") }
        report(BackupStatus.Working("Gathering messages"))
        val media = HashMap<String, Uri>()
        val backup = WinnowBackup(
            createdAt = System.currentTimeMillis(),
            settings = settings.current().toBackup(),
            senderRules = verdicts.allSenderRules().map { SenderRuleBackup(it.address, it.rule, it.createdAt) },
            conversations = if (canReadMessages()) readConversations(media) else emptyList(),
            scheduled = scheduledDao.all().map { ScheduledBackup(splitAddresses(it.recipients), it.body, it.sendAt) },
            corrections = corrections.all().map { c ->
                CorrectionBackup(c.buckets.split(',').mapNotNull(String::toIntOrNull), c.label, c.featurizerVersion, c.createdAt, c.messageKey, c.source)
            },
        )
        var saved = 0
        report(BackupStatus.Working("Saving the backup", 0, media.size))
        val file = resolver.openOutputStream(uri, "wt") ?: error("The file couldn't be opened")
        try {
            file.use { raw ->
                val sealed = key?.let { BackupCrypto.encrypting(raw, it) }
                try {
                    BackupArchive.write(sealed ?: raw, backup) { part ->
                        job.ensureActive()
                        val from = media[part.file] ?: return@write null
                        report(BackupStatus.Working("Saving photos and videos", ++saved, media.size))
                        runCatching { resolver.openInputStream(from) }.getOrNull()
                    }
                    sealed?.finish()
                } finally {
                    sealed?.close()
                }
            }
        } catch (e: Throwable) {
            // Nothing half-written left behind looking like a backup (a protected one wouldn't
            // open anyway: it's only whole once finished).
            withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
            throw e
        }
        report(BackupStatus.Done(
            if (backup.conversations.isEmpty()) {
                "Backed up settings and ${plural(backup.senderRules.size, "sender rule")}. Messages weren't included because Winnow can't read them yet."
            } else {
                "Backed up ${plural(backup.messageCount, "message")} in ${plural(backup.conversations.size, "conversation")}."
            } + if (key != null) " It's protected with your backup password." else "",
        ))
    }

    /**
     * [only], when given, limits it to those conversations (Recently deleted keeps one at a time),
     * and [messages] to those messages, by [ChatMessage.key] (Recently deleted keeping some).
     */
    internal suspend fun readConversations(
        media: MutableMap<String, Uri>,
        only: Set<Long>? = null,
        messages: Set<String>? = null,
        /** Recently deleted: each message carries its own key ([MessageBackup.was]). Never for a backup, which may go to another phone. */
        keepIdentity: Boolean = false,
    ): List<ConversationBackup> {
        // The rows of one table [messages] names: "AND 0" for none of them.
        fun picked(kind: ChatMessage.Kind, column: String): String = messages?.let { keys ->
            val ids = keys.mapNotNull { ChatMessage.idIn(kind, it) }
            if (ids.isEmpty()) " AND 0" else " AND $column IN (${ids.joinToString(",")})"
        }.orEmpty()
        val inThreads = only?.let { ids -> " AND ${Sms.THREAD_ID} IN (${ids.joinToString(",")})" }.orEmpty() + picked(ChatMessage.Kind.SMS, Sms._ID)
        val recipients = resolver.threadRecipients()
        val verdictsByKey = verdicts.all().associateBy { it.messageKey }
        // Messages the user labeled: their key goes with them, for their label to follow.
        val labeled = corrections.all().mapNotNullTo(HashSet()) { it.messageKey }
        val stars = starred.all().mapTo(HashSet()) { it.messageKey }
        // Reminders still to come, for the message they were set on (not another that took its id).
        val reminded = reminders?.all().orEmpty()
        fun remindAt(key: String, date: Long) = reminded[key]?.takeIf { it.messageAt == 0L || it.messageAt == date }?.remindAt
        val byThread = HashMap<Long, MutableList<MessageBackup>>()

        resolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.STATUS, Sms.READ),
            "${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}$inThreads", null, null,
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
                    starred = ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0)) in stars,
                    remindAt = remindAt(ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0)), c.getLong(4)),
                    was = ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0)).takeIf { keepIdentity },
                    labelKey = ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0)).takeIf { it in labeled },
                )
            }
        }

        // Downloaded and sent MMS. One that was announced but never downloaded has nothing to keep.
        data class MmsRow(val id: Long, val threadId: Long, val date: Long, val box: Int, val subject: String?, val read: Boolean)
        val rows = mutableListOf<MmsRow>()
        resolver.query(
            Mms.CONTENT_URI,
            arrayOf(Mms._ID, Mms.THREAD_ID, Mms.DATE, Mms.MESSAGE_BOX, Mms.SUBJECT, Mms.READ, Mms.SUBJECT_CHARSET),
            "${Mms.MESSAGE_BOX} != ${Mms.MESSAGE_BOX_DRAFTS} AND ${Mms.MESSAGE_TYPE} != ${MmsStore.MESSAGE_TYPE_NOTIFICATION_IND}" +
                only?.let { ids -> " AND ${Mms.THREAD_ID} IN (${ids.joinToString(",")})" }.orEmpty() + picked(ChatMessage.Kind.MMS, Mms._ID),
            null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                rows += MmsRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), MmsStore.subjectAt(c, 4, 6), c.getInt(5) != 0)
            }
        }
        val parts = when {
            rows.isEmpty() -> emptyMap()
            only == null && messages == null -> mmsParts(null)
            else -> mmsParts("${Mms.Part.MSG_ID} IN (${rows.joinToString(",") { it.id.toString() }})")
        }
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
                // A part whose data can't be opened (written by another app, or its file is gone) is
                // left out of the manifest too, so its media count stays honest for later restores.
                parts = own.media().filter { readable(it.id) }.map { part ->
                    val file = "${part.id}.${MimeTypeMap.getSingleton().getExtensionFromMimeType(part.contentType) ?: "bin"}"
                    media[file] = ContentUris.withAppendedId(Mms.Part.CONTENT_URI, part.id)
                    PartBackup(part.contentType, part.name, file)
                },
                verdict = verdictsByKey[ChatMessage.messageKey(ChatMessage.Kind.MMS, row.id)]?.toBackup(),
                starred = ChatMessage.messageKey(ChatMessage.Kind.MMS, row.id) in stars,
                remindAt = remindAt(ChatMessage.messageKey(ChatMessage.Kind.MMS, row.id), row.date * 1000),
                was = ChatMessage.messageKey(ChatMessage.Kind.MMS, row.id).takeIf { keepIdentity },
                labelKey = ChatMessage.messageKey(ChatMessage.Kind.MMS, row.id).takeIf { it in labeled },
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
                // A timed mute that has run out isn't carried over.
                muted = state?.isMuted() == true,
                mutedUntil = state?.takeIf { it.isMuted() }?.mutedUntil,
                draft = state?.draft,
                draftSubject = state?.draftSubject,
                // Kept files of their own (see DraftAttachments), named so they can't meet a part's.
                draftAttachments = drafts?.decode(state?.draftAttachments).orEmpty().mapIndexed { i, a ->
                    val file = "draft-$threadId-$i.${MimeTypeMap.getSingleton().getExtensionFromMimeType(a.contentType) ?: "bin"}"
                    media[file] = Uri.parse(a.uri)
                    PartBackup(a.contentType, a.name, file)
                },
                title = state?.title,
                messages = messages.sortedBy { it.date },
            )
        }
    }

    // --- Restore ---------------------------------------------------------------------------

    private suspend fun restoreFrom(uri: Uri, includeSettings: Boolean) {
        _status.value = BackupStatus.Working("Reading backup")
        val spool = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            val restored = mutableListOf<String>()
            // Settings first, from the manifest alone: anything the user changes while the
            // media is copied (picking a classifier during onboarding, say) then wins.
            val manifest = openArchive(uri).use(BackupArchive::peek)
            if (includeSettings && manifest.settings != null) {
                settings.update { it.restoring(manifest.settings) }
                restored += "your settings"
            }
            val backup = openArchive(uri).use { input ->
                BackupArchive.read(input) { name, stream -> File(spool, name).outputStream().use { stream.copyTo(it) } }
            }

            val known = verdicts.allSenderRules().mapTo(HashSet()) { it.address }
            val rules = backup.senderRules.filter { it.address !in known }
            rules.forEach { verdicts.upsertSenderRule(SenderRuleEntity(it.address, it.rule, it.createdAt)) }
            if (rules.isNotEmpty()) restored += plural(rules.size, "sender rule")

            // A label waits under a placeholder key until its message is back (see relinkLabel).
            val learned = corrections.all().mapTo(HashSet()) { it.buckets to it.label }
            val lessons = backup.corrections.filter { (it.buckets.joinToString(",") to it.label) !in learned }
            lessons.forEach {
                corrections.insert(
                    CorrectionEntity(
                        threadId = null, buckets = it.buckets.joinToString(","), label = it.label, featurizerVersion = it.featurizerVersion,
                        createdAt = it.createdAt, messageKey = it.messageKey?.let { key -> RESTORED_LABEL + key },
                        source = if (it.source == CorrectionEntity.SOURCE_PROVIDER) CorrectionEntity.SOURCE_PROVIDER else CorrectionEntity.SOURCE_USER,
                    ),
                )
            }
            if (lessons.isNotEmpty()) {
                learner.reload()
                val labels = lessons.count { it.messageKey != null }
                if (labels > 0) restored += plural(labels, "label")
                if (lessons.size > labels) restored += plural(lessons.size - labels, "correction")
            }

            val message = if (!canWriteMessages()) {
                "Messages weren't restored because Winnow isn't your SMS app." + also(restored)
            } else {
                val (added, present) = restoreMessages(backup, spool)
                val scheduled = restoreScheduled(backup.scheduled)
                if (scheduled > 0) restored += plural(scheduled, "scheduled message")
                when {
                    added == 0 && present == 0 -> "The backup had no messages."
                    added == 0 -> allPresent(present)
                    present == 0 -> "Restored ${plural(added, "message")}."
                    else -> "Restored ${plural(added, "message")} (${alreadyHere(present)})."
                } + also(restored)
            }
            _status.value = BackupStatus.Done(message)
        } catch (_: PasswordNeeded) {
            // The key it was opened with is gone (the password changed meanwhile): ask for it.
            _status.value = BackupStatus.NeedsPassword(uri)
        } finally {
            // Labels whose messages didn't come back (Winnow isn't the SMS app yet, say) keep teaching, unlinked.
            runCatching { corrections.unlinkPrefixed(RESTORED_LABEL) }
            spool.deleteRecursively()
            unlocked.remove(uri)
        }
    }

    /** What [restoreMessages] did: messages [added], [present] already, and [empty] ones with nothing to put back. */
    internal data class RestoreCount(val added: Int, val present: Int, val empty: Int) {
        /** Every message in the backup is accounted for. */
        fun covers(count: Int) = added + present + empty >= count
    }

    /**
     * Adds the messages this phone doesn't have yet, and Winnow's verdicts for messages it has
     * but never classified (after a reinstall, say). Progress goes to [report]: the Backup
     * settings' status unless the caller says otherwise.
     */
    internal suspend fun restoreMessages(
        backup: WinnowBackup,
        spool: File,
        report: (BackupStatus) -> Unit = { _status.value = it },
        /**
         * Recently deleted: a conversation's draft goes back into state made since (opening it
         * again, picking a SIM) if that has none. Not for a backup restore, where a draft that's
         * gone was most likely sent, and anything already here is kept.
         */
        draftsIntoExisting: Boolean = false,
        /** Each message that's on the phone now (added, or there already) or has nothing to put back. */
        settled: (MessageBackup) -> Unit = {},
    ): RestoreCount {
        val total = backup.messageCount
        val classified = verdicts.all().mapTo(HashSet()) { it.messageKey }
        var done = 0
        var added = 0
        var present = 0
        var empty = 0
        // Texts anywhere on the phone, not just in the matching conversation: a text sent to a
        // group one person at a time sits in the group's thread here but one person's elsewhere.
        val textsEverywhere = existingTextsEverywhere()
        for (conversation in backup.conversations) {
            fun textKey(m: MessageBackup) = if (m.kind == KIND_SMS) textKey(m.fingerprint, (if (m.outgoing) m.to else m.sender) ?: conversation.recipients.singleOrNull()) else null
            // One kept by Recently deleted is known by its own row, not by a lookup (see matchExisting).
            val knownTexts = conversation.messages.filter { m -> m.was == null && textKey(m)?.let { it in textsEverywhere } == true }
            if (knownTexts.size == conversation.messages.size) {
                // All already here: nothing to add, and no empty conversation to create for them.
                knownTexts.forEach { m ->
                    val (key, inThread) = textsEverywhere.getValue(textKey(m)!!)
                    if (key !in classified) restoreVerdict(m, conversation, inThread, key)
                    relinkLabel(m, key, inThread)
                    if (m.starred) starred.star(StarredEntity(key, inThread, System.currentTimeMillis()))
                    restoreReminder(m, key, inThread, conversation)
                }
                present += knownTexts.size
                done += knownTexts.size
                knownTexts.forEach(settled)
                // Its pin, mute, name and draft still come back (a reinstall leaves every text on
                // the phone and none of Winnow's state), on the conversation those texts are in.
                knownTexts.map { textsEverywhere.getValue(textKey(it)!!).second }.distinct().singleOrNull()?.let { restoreState(it, conversation, spool, draftsIntoExisting) }
                continue
            }
            val threadId = Telephony.Threads.getOrCreateThreadId(context, conversation.recipients.toSet())
            val existing = existingMessages(threadId)
            val (here, missing) = matchExisting(conversation.messages, threadId, existing, identity = ::stillHere) { m -> textKey(m)?.let { textsEverywhere[it] } }

            here.forEach { (m, at) ->
                val (key, inThread) = at
                if (key !in classified) restoreVerdict(m, conversation, inThread, key)
                relinkLabel(m, key, inThread)
                if (m.starred) starred.star(StarredEntity(key, inThread, System.currentTimeMillis()))
                restoreReminder(m, key, inThread, conversation)
            }
            present += here.size
            done += here.size
            here.forEach { (m, _) -> settled(m) }

            missing.filter { it.kind == KIND_SMS }.chunked(SMS_BATCH).forEach { chunk ->
                val ops = chunk.map { m -> ContentProviderOperation.newInsert(Sms.CONTENT_URI).withValues(smsValues(threadId, conversation, m)).build() }
                val results = resolver.applyBatch(Sms.CONTENT_URI.authority!!, ArrayList(ops))
                chunk.zip(results).forEach { (m, result) ->
                    val id = result.uri?.let(ContentUris::parseId) ?: return@forEach
                    added++
                    settled(m)
                    restoreVerdict(m, conversation, threadId, ChatMessage.messageKey(ChatMessage.Kind.SMS, id))
                    relinkLabel(m, ChatMessage.messageKey(ChatMessage.Kind.SMS, id), threadId)
                    if (m.starred) starred.star(StarredEntity(ChatMessage.messageKey(ChatMessage.Kind.SMS, id), threadId, System.currentTimeMillis()))
                    restoreReminder(m, ChatMessage.messageKey(ChatMessage.Kind.SMS, id), threadId, conversation)
                }
                done += chunk.size
                report(BackupStatus.Working("Restoring messages", done, total))
            }

            missing.filter { it.kind == KIND_MMS }.forEach { m ->
                // No text, no subject and none of its media in the file (say, a part no app could
                // read): there's nothing to put back, and no point trying again later.
                if (m.body.isEmpty() && m.subject.isNullOrBlank() && m.parts.none { spooled(spool, it.file) != null }) {
                    empty++
                    settled(m)
                    report(BackupStatus.Working("Restoring messages", ++done, total))
                    return@forEach
                }
                val uri = insertMms(threadId, conversation, m, spool)
                if (uri != null) {
                    added++
                    settled(m)
                    restoreVerdict(m, conversation, threadId, ChatMessage.messageKey(ChatMessage.Kind.MMS, ContentUris.parseId(uri)))
                    relinkLabel(m, ChatMessage.messageKey(ChatMessage.Kind.MMS, ContentUris.parseId(uri)), threadId)
                    if (m.starred) starred.star(StarredEntity(ChatMessage.messageKey(ChatMessage.Kind.MMS, ContentUris.parseId(uri)), threadId, System.currentTimeMillis()))
                    restoreReminder(m, ChatMessage.messageKey(ChatMessage.Kind.MMS, ContentUris.parseId(uri)), threadId, conversation)
                }
                report(BackupStatus.Working("Restoring messages", ++done, total))
            }
            report(BackupStatus.Working("Restoring messages", done, total))

            restoreState(threadId, conversation, spool, draftsIntoExisting)
        }
        return RestoreCount(added, present, empty)
    }

    /**
     * A conversation's pin, archive, mute, name and draft (its subject and attachments too),
     * unless Winnow already has state for it; then, with [draftsIntoExisting], just the draft.
     */
    private suspend fun restoreState(threadId: Long, conversation: ConversationBackup, spool: File, draftsIntoExisting: Boolean) {
        // The draft's attachments, copied out of the spool (which goes) to where drafts keep theirs.
        fun attached() = drafts?.let { d ->
            conversation.draftAttachments.mapNotNull { p ->
                val file = spooled(spool, p.file) ?: return@mapNotNull null
                d.keep(OutgoingAttachment(Uri.fromFile(file).toString(), p.contentType, p.name))
            }.let(d::encode)
        }
        val existing = states.get(threadId)
        if (existing != null) {
            if (!draftsIntoExisting) return
            // State made since (the conversation opened again, a SIM picked) stands. Its draft is
            // only taken if it has none at all: a conversation back from Recently deleted mustn't
            // lose its draft, photos and all, to a row that only says which SIM it uses.
            val backedUp = conversation.draft != null || conversation.draftSubject != null || conversation.draftAttachments.isNotEmpty()
            val hasDraft = existing.draft != null || existing.draftSubject != null || existing.draftAttachments != null
            if (backedUp && !hasDraft) {
                states.upsert(existing.copy(draft = conversation.draft, draftSubject = conversation.draftSubject, draftAttachments = attached()))
            }
            return
        }
        val attached = attached()
        val state = ConversationStateEntity(
            threadId, conversation.pinned, conversation.archived, conversation.muted, conversation.draft, title = conversation.title,
            mutedUntil = conversation.mutedUntil.takeIf { conversation.muted },
            draftSubject = conversation.draftSubject,
            draftAttachments = attached,
        )
        if (state != ConversationStateEntity(threadId)) states.upsert(state)
    }

    /** [name]'s file in [spool], only if it's really in there (see BackupArchive.safeName) and exists. */
    private fun spooled(spool: File, name: String): File? {
        if (BackupArchive.safeName(name) == null) return null
        val file = File(spool, name)
        // A path that can't be resolved is one this restore skips, not one that ends it.
        return file.takeIf { runCatching { it.canonicalFile.parentFile == spool.canonicalFile }.getOrDefault(false) && it.isFile }
    }

    /** A text's identity across threads: its fingerprint and the other person's number. */
    private fun textKey(fingerprint: String, address: String?) = "$fingerprint|${address?.let(::normalizeAddress).orEmpty()}"

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
                val file = spooled(spool, p.file) ?: return@forEach
                add(MmsPart(contentType = p.contentType, data = file.readBytes(), name = p.name ?: p.file, contentLocation = p.name ?: p.file))
            }
            // A subject alone is carried by an empty text, as it was sent (see MmsSender).
            if (isEmpty() && !m.subject.isNullOrBlank()) add(MmsPart.plainText(""))
        }
        if (parts.isEmpty()) return null
        val box = when {
            !m.outgoing -> Mms.MESSAGE_BOX_INBOX
            m.status == STATUS_FAILED -> Mms.MESSAGE_BOX_FAILED
            else -> Mms.MESSAGE_BOX_SENT
        }
        // Received group messages went to everyone else in the conversation; sent ones to everyone.
        val to = if (m.outgoing) conversation.recipients else conversation.recipients.filter { it != m.sender }
        // Backups made before subjects were decoded on the way out hold the store's own form
        // (UTF-8 bytes as characters); decoded text comes through this unchanged.
        val subject = m.subject?.let { MmsCharsets.fromStore(it, MmsCharsets.UTF_8) }
        return mmsStore.insertRestored(threadId, box, m.date / 1000, m.read, subject, m.sender, to, listOf(Smil.forParts(parts)) + parts)
    }

    /** A reminder on [m], under the key it has on this phone now; only one still to come. */
    private suspend fun restoreReminder(m: MessageBackup, key: String, threadId: Long, conversation: ConversationBackup) {
        val at = m.remindAt ?: return
        val words = subjectAndText(m.subject, m.body)
        reminders?.restore(
            ReminderEntity(
                messageKey = key,
                threadId = threadId,
                recipients = joinAddresses(conversation.recipients),
                remindAt = at,
                preview = words.ifBlank { attachmentSummary(m.parts.map { it.contentType }) },
                sender = if (m.outgoing) null else m.sender ?: conversation.recipients.singleOrNull(),
                messageAt = m.date,
                fromMe = m.outgoing,
            ),
        )
    }

    /**
     * [m]'s label follows it to [key] in conversation [threadId]: one from a backup (waiting under
     * its placeholder key), or one kept while it sat in Recently deleted (under the key it had).
     * A label this message already has here wins, and the stale copy goes: it would teach the
     * same thing twice.
     */
    private suspend fun relinkLabel(m: MessageBackup, key: String, threadId: Long) {
        val from = listOfNotNull(m.labelKey?.let { RESTORED_LABEL + it }, m.was?.takeIf { it != key })
        for (old in from) {
            if (corrections.forMessages(listOf(key)).isNotEmpty()) corrections.deleteForMessages(listOf(old))
            else corrections.relink(old, key, threadId)
        }
    }

    private suspend fun restoreVerdict(m: MessageBackup, conversation: ConversationBackup, threadId: Long, key: String) {
        // Restored, not received: never news for a daily summary.
        val entity = m.verdict?.toEntity(key, threadId, m.sender ?: m.to ?: conversation.recipients.first())?.copy(summarized = true) ?: return
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
    /** Every text on the phone by [textKey]: its message key and thread. */
    private fun existingTextsEverywhere(): Map<String, Pair<String, Long>> {
        val found = HashMap<String, Pair<String, Long>>()
        resolver.query(
            Sms.CONTENT_URI, arrayOf(Sms._ID, Sms.DATE, Sms.TYPE, Sms.BODY, Sms.THREAD_ID, Sms.ADDRESS),
            "${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val print = MessageBackup.fingerprint(KIND_SMS, c.getLong(1), c.getInt(2) != Sms.MESSAGE_TYPE_INBOX, c.getString(3).orEmpty(), 0)
                found[textKey(print, c.getString(5))] = ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0)) to c.getLong(4)
            }
        }
        return found
    }

    /** [threadId]'s messages by [MessageBackup.fingerprint]: lookalikes (same second, same text) share one, in a list. */
    private fun existingMessages(threadId: Long): Map<String, List<String>> {
        val found = HashMap<String, MutableList<String>>()
        val args = arrayOf(threadId.toString())
        resolver.query(
            Sms.CONTENT_URI, arrayOf(Sms._ID, Sms.DATE, Sms.TYPE, Sms.BODY),
            "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}", args, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val print = MessageBackup.fingerprint(KIND_SMS, c.getLong(1), c.getInt(2) != Sms.MESSAGE_TYPE_INBOX, c.getString(3).orEmpty(), 0)
                found.getOrPut(print, ::mutableListOf) += ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0))
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
                found.getOrPut(MessageBackup.fingerprint(KIND_MMS, date, outgoing, own.textBody(), own.media().size), ::mutableListOf) += ChatMessage.messageKey(ChatMessage.Kind.MMS, id)
            }
        }
        return found
    }

    /** [m]'s own row ([MessageBackup.was]) if it's still on the phone, as that message (same time); else null. */
    private fun stillHere(m: MessageBackup): String? {
        val key = m.was ?: return null
        // Same time, direction and (a text) words: an id the store reused for another message isn't it.
        ChatMessage.idIn(ChatMessage.Kind.SMS, key)?.let { id ->
            return resolver.query(ContentUris.withAppendedId(Sms.CONTENT_URI, id), arrayOf(Sms.DATE, Sms.TYPE, Sms.BODY), null, null, null)?.use { c ->
                key.takeIf { c.moveToFirst() && c.getLong(0) == m.date && (c.getInt(1) != Sms.MESSAGE_TYPE_INBOX) == m.outgoing && c.getString(2).orEmpty() == m.body }
            }
        }
        val id = ChatMessage.idIn(ChatMessage.Kind.MMS, key) ?: return null
        return resolver.query(ContentUris.withAppendedId(Mms.CONTENT_URI, id), arrayOf(Mms.DATE, Mms.MESSAGE_BOX), null, null, null)?.use { c ->
            key.takeIf { c.moveToFirst() && c.getLong(0) * 1000 == m.date && (c.getInt(1) != Mms.MESSAGE_BOX_INBOX) == m.outgoing }
        }
    }

    private fun readable(partId: Long): Boolean =
        runCatching { resolver.openInputStream(ContentUris.withAppendedId(Mms.Part.CONTENT_URI, partId))?.use { true } ?: false }.getOrDefault(false)

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
        const val SMS_BATCH = 250

        /** A restored label's key until its message is back: never a real message key, so it can't collide with one. */
        const val RESTORED_LABEL = "restored:"

        fun format(n: Int): String = NumberFormat.getIntegerInstance().format(n)

        fun plural(n: Int, noun: String) = "${format(n)} $noun${if (n == 1) "" else "s"}"

        private fun alreadyHere(n: Int) = if (n == 1) "1 was already here" else "${format(n)} were already here"

        private fun allPresent(n: Int) =
            if (n == 1) "Its one message was already on this phone." else "All ${format(n)} messages were already on this phone."

        fun also(restored: List<String>) = when (restored.size) {
            0 -> ""
            1 -> " Also restored ${restored.single()}."
            else -> " Also restored ${restored.dropLast(1).joinToString(", ")} and ${restored.last()}."
        }
    }
}
