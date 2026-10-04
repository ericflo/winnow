package com.ericflo.winnow.backup

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.ConversationStateStore
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.displayNameFor
import com.ericflo.winnow.notify.Notifier
import com.ericflo.winnow.sms.MmsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Recently deleted: a conversation the user deletes is kept for [KEEP_DAYS] days first, as a
 * one-conversation Winnow backup (messages, photos, what Winnow decided, stars, pin and draft) in
 * the app's own storage, and can be put back. After that it's gone for good. Messages deleted
 * from a conversation are kept the same way, as a backup of just them.
 */
class Trash(
    private val context: Context,
    private val backups: BackupManager,
    private val repo: MessageRepository,
    private val states: ConversationStateStore,
    private val notifier: Notifier,
    /** Deleting or putting back messages needs Winnow to be the SMS app. */
    private val canWrite: () -> Boolean,
    /** Conversations deleted for real: what else is kept about them goes (reminders). */
    private val onGone: suspend (Collection<Long>) -> Unit = {},
    /** The same for messages, by key: their reminders, which are kept with them here. */
    private val onMessagesGone: suspend (Collection<String>) -> Unit = {},
) {
    data class Item(
        val file: File,
        val title: String,
        val recipients: List<String>,
        val messages: Int,
        val deletedAt: Long,
        val snippet: String,
        /** Some messages deleted from a conversation, not the conversation. */
        val someMessages: Boolean = false,
    ) {
        val expiresAt: Long get() = deletedAt + KEEP_DAYS * DAY_MILLIS
    }

    private val dir get() = File(context.filesDir, "trash").apply { mkdirs() }
    private val lock = Mutex()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    /** Newest first. */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    /** What [delete] did: [problem] says why some conversation wasn't deleted (it couldn't be kept). */
    data class Deleted(
        val problem: String?,
        val items: List<Item>,
        /** Left alone: something newer than the caller's `unlessNewerThan` is in it. */
        val skipped: Set<Long> = emptySet(),
    ) {
        val ok: Boolean get() = problem == null
    }

    /**
     * Keeps [threadIds] in Recently deleted, deleting each from the phone as soon as it's kept. A
     * conversation that couldn't be kept (say, the storage is full) isn't deleted.
     */
    suspend fun delete(threadIds: Set<Long>, unlessNewerThan: Long? = null): Deleted = lock.withLock {
        // Not the SMS app: Android would quietly refuse the delete, leaving a copy here too.
        if (!canWrite()) return@withLock Deleted("Make Winnow your SMS app to delete conversations", emptyList())
        withContext(Dispatchers.IO) {
            val made = mutableSetOf<File>()
            val kept = mutableSetOf<Long>()
            // Kept and deleted, and no new text arrived meanwhile to keep the conversation going.
            val gone = mutableSetOf<Long>()
            // Left alone: something newer than [unlessNewerThan] is in it (a clean-up's decision is stale).
            val skipped = mutableSetOf<Long>()
            try {
                threadIds.forEach { threadId ->
                    val result = keep(threadId) ?: return@forEach
                    if (unlessNewerThan != null && newestDate(threadId) > unlessNewerThan) {
                        result.file?.delete()
                        skipped += threadId
                        return@forEach
                    }
                    result.file?.let(made::add)
                    try {
                        // Exactly what was kept: a text that arrived since has a newer id, and stays.
                        if (repo.deleteThreadUpTo(threadId, result.snapshot.newestSms, result.snapshot.newestMms)) gone += threadId
                        kept += threadId
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Kept but maybe not deleted: restoring it adds only what's missing, so no harm.
                        Log.w(TAG, "Couldn't delete a kept conversation", e)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    // Its pin, mute and notifications go with it; not if a new text kept it here.
                    if (gone.isNotEmpty()) {
                        states.forget(gone)
                        notifier.forget(gone)
                        runCatching { onGone(gone) }
                    }
                    reload()
                }
            }
            Deleted(
                "Couldn't keep a conversation in Recently deleted, so it wasn't deleted".takeIf { kept.size + skipped.size != threadIds.size },
                _items.value.filter { it.file in made },
                skipped,
            )
        }
    }

    /**
     * Keeps [messages] (from conversation [threadId]) in Recently deleted, then deletes them from
     * the phone. None are deleted if they couldn't be kept. One that was never downloaded has
     * nothing to keep, and is just deleted; one that's gone already is left alone.
     */
    suspend fun deleteMessages(threadId: Long, messages: List<ChatMessage>): Deleted = lock.withLock {
        if (!canWrite()) return@withLock Deleted("Make Winnow your SMS app to delete messages", emptyList())
        withContext(Dispatchers.IO) {
            val file = File(dir, "${System.currentTimeMillis()}-$threadId$SOME_MESSAGES")
            val partial = File(dir, "${file.name}.part")
            val doomed: List<ChatMessage>
            try {
                val media = HashMap<String, android.net.Uri>()
                // Just these messages: the conversation's draft stays in the conversation.
                val read = backups.readConversations(media, only = setOf(threadId), messages = messages.mapTo(HashSet()) { it.key }, keepIdentity = true)
                    .singleOrNull()
                // Each message as it was read, by its key and time: a key whose row is now another
                // message (the SMS table reuses ids) doesn't match, and is neither kept nor deleted.
                val wanted = messages.associateBy { it.key }
                val matched = read?.messages.orEmpty().filter { m -> wanted[m.was]?.timestamp == m.date }
                val keptKeys = matched.mapTo(HashSet()) { it.was }
                doomed = messages.filter { it.isPlaceholder || it.key in keptKeys }
                if (messages.any { !it.isPlaceholder && it.key !in keptKeys && stillThere(it) }) {
                    return@withContext Deleted("Couldn't keep those messages in Recently deleted, so they weren't deleted", emptyList())
                }
                val conversation = read?.takeIf { matched.isNotEmpty() }?.copy(draft = null, draftSubject = null, draftAttachments = emptyList(), messages = matched)
                if (conversation != null) {
                    partial.outputStream().use { output ->
                        BackupArchive.write(output, WinnowBackup(createdAt = System.currentTimeMillis(), conversations = listOf(conversation))) { part ->
                            media[part.file]?.let { from -> runCatching { context.contentResolver.openInputStream(from) }.getOrNull() }
                        }
                    }
                    FileOutputStream(partial, true).use { it.fd.sync() }
                    if (!partial.renameTo(file)) error("Couldn't name the kept file")
                }
            } catch (e: CancellationException) {
                partial.delete()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't keep messages in Recently deleted", e)
                partial.delete()
                return@withContext Deleted("Couldn't keep those messages in Recently deleted, so they weren't deleted", emptyList())
            }
            try {
                withContext(NonCancellable) {
                    doomed.forEach { repo.deleteMessage(it) }
                    runCatching { onMessagesGone(doomed.map { it.key }) }
                }
            } finally {
                withContext(NonCancellable) { reload() }
            }
            Deleted(null, _items.value.filter { it.file == file })
        }
    }

    /** Whether [message]'s row is still in the store (as that message: same time). */
    private fun stillThere(message: ChatMessage): Boolean {
        val (table, column) = if (message.kind == ChatMessage.Kind.SMS) Telephony.Sms.CONTENT_URI to Telephony.Sms.DATE else Telephony.Mms.CONTENT_URI to Telephony.Mms.DATE
        return context.contentResolver.query(android.content.ContentUris.withAppendedId(table, message.id), arrayOf(column), null, null, null)?.use { c ->
            c.moveToFirst() && (if (message.kind == ChatMessage.Kind.SMS) c.getLong(0) else c.getLong(0) * 1000) == message.timestamp
        } == true
    }

    /** A conversation kept (in [file], or nothing worth keeping), and its messages as they were. */
    private class Kept(val file: File?, val snapshot: Snapshot)

    /**
     * Writes [threadId] to a file here; null if it couldn't be. It's read again if a message
     * arrives (or goes) while it's being written. MMS that were never downloaded have nothing to
     * keep, and aren't.
     */
    private suspend fun keep(threadId: Long): Kept? {
        repeat(ATTEMPTS) {
            val now = System.currentTimeMillis()
            val file = File(dir, "$now-$threadId.zip")
            val partial = File(dir, "${file.name}.part")
            try {
                val before = snapshot(threadId)
                val media = HashMap<String, android.net.Uri>()
                val conversation = backups.readConversations(media, only = setOf(threadId), keepIdentity = true).singleOrNull()
                if (conversation == null) {
                    // Nothing in it worth keeping (a new conversation, or only undownloaded MMS): fine
                    // to delete. Messages with no one to put them back with can't be kept, so they stay.
                    if (before.keepable != 0) Log.w(TAG, "A conversation with no recipients can't be kept")
                    return if (before.keepable == 0) Kept(null, before) else null
                }
                partial.outputStream().use { output ->
                    BackupArchive.write(output, WinnowBackup(createdAt = now, conversations = listOf(conversation))) { part ->
                        media[part.file]?.let { from -> runCatching { context.contentResolver.openInputStream(from) }.getOrNull() }
                    }
                }
                // On the disk before the messages are deleted: a crash or power cut can't leave neither.
                FileOutputStream(partial, true).use { it.fd.sync() }
                if (snapshot(threadId) == before && partial.renameTo(file)) return Kept(file, before)
            } catch (e: CancellationException) {
                partial.delete()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't keep a conversation in Recently deleted", e)
                partial.delete()
                return null
            }
            partial.delete()
        }
        Log.w(TAG, "A conversation kept changing while it was being kept")
        return null
    }

    /**
     * [threadId]'s messages as they stand: every row's id (drafts and undownloaded MMS too, which
     * a delete takes with it), and how many are worth keeping. Enough to notice any change.
     */
    private data class Snapshot(val sms: List<Long>, val mms: List<Long>, val keepable: Int) {
        val newestSms: Long get() = sms.maxOrNull() ?: 0
        val newestMms: Long get() = mms.maxOrNull() ?: 0
    }

    /** When [threadId]'s newest message (of any kind) was sent or received; 0 for none. */
    private fun newestDate(threadId: Long): Long {
        val resolver = context.contentResolver
        val args = arrayOf(threadId.toString())
        // Drafts aside (one left by another app, say): they're never what arrived since.
        val sms = resolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.DATE),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}", args, "${Telephony.Sms.DATE} DESC LIMIT 1",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0 } ?: 0
        val mms = resolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.DATE),
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}", args, "${Telephony.Mms.DATE} DESC LIMIT 1",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) * 1000 else 0 } ?: 0
        return maxOf(sms, mms)
    }

    private fun snapshot(threadId: Long): Snapshot {
        val resolver = context.contentResolver
        var keepable = 0
        val sms = resolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.TYPE), "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), null,
        )?.use { c ->
            buildList { while (c.moveToNext()) { add(c.getLong(0)); if (c.getInt(1) != Telephony.Sms.MESSAGE_TYPE_DRAFT) keepable++ } }
        }.orEmpty()
        val mms = resolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_TYPE),
            "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString()), null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(c.getLong(0))
                    if (c.getInt(1) != Telephony.Mms.MESSAGE_BOX_DRAFTS && c.getInt(2) != MmsStore.MESSAGE_TYPE_NOTIFICATION_IND) keepable++
                }
            }
        }.orEmpty()
        return Snapshot(sms.sorted(), mms.sorted(), keepable)
    }

    /** What [restore] did: how many messages came back, and whether all of them are on the phone now. */
    data class Restored(val added: Int, val complete: Boolean)

    /**
     * Puts [item] back: whatever of it isn't on the phone again is added, then it leaves Recently
     * deleted. If some of it couldn't be added it stays, so it can be tried again. Null if it was
     * already restored (or deleted for good) by the time this ran.
     */
    suspend fun restore(item: Item): Restored? = lock.withLock {
        if (!item.file.exists()) return@withLock null
        withContext(Dispatchers.IO) {
            val spool = File(context.cacheDir, "trash-restore").apply { deleteRecursively(); mkdirs() }
            // Each message put back is noted here as it goes, so a restore that's cut short (storage
            // full, the app killed) and tried again doesn't add those twice.
            val journal = journalFor(item.file)
            try {
                val whole = item.file.inputStream().use { input ->
                    BackupArchive.read(input) { name, stream -> File(spool, name).outputStream().use { stream.copyTo(it) } }
                }
                // Back already, by an attempt that didn't finish.
                val done = runCatching { journal.readLines().toSet() }.getOrDefault(emptySet())
                val backup = if (done.isEmpty()) whole else whole.copy(conversations = whole.conversations.map { c -> c.copy(messages = c.messages.filter { it.was !in done }) })
                val already = whole.messageCount - backup.messageCount
                // Not reported to the Backup settings, which may be busy with a backup of their own.
                val settled = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<MessageBackup, Boolean>())
                val restored = journal.appendingWriter().use { notes ->
                    backups.restoreMessages(backup, spool, report = {}, draftsIntoExisting = true, settled = { m ->
                        settled += m
                        m.was?.let { notes.write(it); notes.newLine(); notes.flush() }
                    })
                }
                val complete = restored.covers(backup.messageCount)
                if (complete) {
                    item.file.delete()
                    journal.delete()
                } else if (settled.isNotEmpty() || already > 0) {
                    // What's back stays back: the file keeps only the rest, and the notes have done their job.
                    if (keepOnly(item.file, backup, spool) { it !in settled }) journal.delete()
                }
                Restored(restored.added, complete)
            } finally {
                spool.deleteRecursively()
                reload()
            }
        }
    }

    /** Where [file]'s restore notes which messages are back (see [restore]). */
    private fun journalFor(file: File) = File(file.parentFile, "${file.name}$JOURNAL")

    private fun File.appendingWriter() = java.io.BufferedWriter(java.io.FileWriter(this, true))

    /** Rewrites [file] (a [backup] whose media is in [spool]) with just the messages [keep] says. Whether it did. */
    private fun keepOnly(file: File, backup: WinnowBackup, spool: File, keep: (MessageBackup) -> Boolean): Boolean {
        val rest = backup.copy(conversations = backup.conversations.map { c -> c.copy(messages = c.messages.filter(keep)) })
        val partial = File(file.parentFile, "${file.name}.part")
        return runCatching {
            partial.outputStream().use { output ->
                BackupArchive.write(output, rest) { part ->
                    File(spool, part.file).takeIf { BackupArchive.safeName(part.file) != null && it.isFile }?.inputStream()
                }
            }
            FileOutputStream(partial, true).use { it.fd.sync() }
            if (!partial.renameTo(file)) error("Couldn't replace the kept file")
            // Same name, new contents: read it again.
            read.remove(file.name)
        }.onFailure {
            partial.delete()
            Log.w(TAG, "Couldn't trim a partly restored item", it)
        }.isSuccess
    }

    /** Gone for good, now rather than after [KEEP_DAYS] days. */
    suspend fun forget(items: Collection<Item>) = lock.withLock {
        withContext(Dispatchers.IO) {
            items.forEach { it.file.delete(); journalFor(it.file).delete() }
            reload()
        }
    }

    /** At start-up: drops what's been here longer than [KEEP_DAYS] days (and anything half-written), and lists the rest. */
    suspend fun purgeExpired() = lock.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { file ->
                // A restore's notes stay as long as what they're about does.
                val about = if (file.name.endsWith(JOURNAL)) File(file.parentFile, file.name.removeSuffix(JOURNAL)) else file
                if (deletedAt(about)?.let { now - it > KEEP_DAYS * DAY_MILLIS } != false || !about.exists()) file.delete()
            }
            reload()
        }
    }

    /** Each file's [Item], by name: the files never change, so each is read once. */
    private val read = HashMap<String, Item>()

    private fun reload() {
        val files = dir.listFiles().orEmpty().filter { deletedAt(it) != null }
        read.keys.retainAll(files.mapTo(HashSet()) { it.name })
        _items.value = files.mapNotNull { file -> read[file.name] ?: item(file)?.also { read[file.name] = it } }
            .sortedByDescending { it.deletedAt }
    }

    private fun item(file: File): Item? {
        val at = deletedAt(file) ?: return null
        val conversation = runCatching { file.inputStream().use(BackupArchive::peek).conversations.singleOrNull() }.getOrNull() ?: return null
        val last = conversation.messages.lastOrNull()
        return Item(
            file = file,
            title = conversation.title ?: displayNameFor(conversation.recipients, repo::displayName),
            recipients = conversation.recipients,
            messages = conversation.messages.size,
            deletedAt = at,
            snippet = last?.body?.takeIf { it.isNotBlank() } ?: if (last?.parts?.isNotEmpty() == true) "Photo or attachment" else "",
            someMessages = file.name.endsWith(SOME_MESSAGES),
        )
    }

    private fun deletedAt(file: File): Long? = file.name.substringBefore('-').toLongOrNull()?.takeIf { file.extension == "zip" }

    companion object {
        const val KEEP_DAYS = 30L
        /** How a file of some messages from a conversation ends, after its time and conversation. */
        private const val SOME_MESSAGES = "-messages.zip"
        /** A restore's notes, beside the file it's restoring. */
        private const val JOURNAL = ".restored"
        private const val DAY_MILLIS = 24 * 60 * 60_000L
        private const val ATTEMPTS = 3
        private const val TAG = "WinnowTrash"
    }
}
