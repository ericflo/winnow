package com.ericflo.winnow.backup

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import android.util.Log
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
 * the app's own storage, and can be put back. After that it's gone for good.
 */
class Trash(
    private val context: Context,
    private val backups: BackupManager,
    private val repo: MessageRepository,
    private val states: ConversationStateStore,
    private val notifier: Notifier,
    /** Deleting or putting back messages needs Winnow to be the SMS app. */
    private val canWrite: () -> Boolean,
) {
    data class Item(
        val file: File,
        val title: String,
        val recipients: List<String>,
        val messages: Int,
        val deletedAt: Long,
        val snippet: String,
    ) {
        val expiresAt: Long get() = deletedAt + KEEP_DAYS * DAY_MILLIS
    }

    private val dir get() = File(context.filesDir, "trash").apply { mkdirs() }
    private val lock = Mutex()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    /** Newest first. */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    /** What [delete] did: [problem] says why some conversation wasn't deleted (it couldn't be kept). */
    data class Deleted(val problem: String?, val items: List<Item>) {
        val ok: Boolean get() = problem == null
    }

    /**
     * Keeps [threadIds] in Recently deleted, deleting each from the phone as soon as it's kept. A
     * conversation that couldn't be kept (say, the storage is full) isn't deleted.
     */
    suspend fun delete(threadIds: Set<Long>): Deleted = lock.withLock {
        // Not the SMS app: Android would quietly refuse the delete, leaving a copy here too.
        if (!canWrite()) return@withLock Deleted("Make Winnow your SMS app to delete conversations", emptyList())
        withContext(Dispatchers.IO) {
            val made = mutableSetOf<File>()
            val kept = mutableSetOf<Long>()
            // Kept and deleted, and no new text arrived meanwhile to keep the conversation going.
            val gone = mutableSetOf<Long>()
            try {
                threadIds.forEach { threadId ->
                    val result = keep(threadId) ?: return@forEach
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
                    }
                    reload()
                }
            }
            Deleted(
                "Couldn't keep a conversation in Recently deleted, so it wasn't deleted".takeIf { kept.size != threadIds.size },
                _items.value.filter { it.file in made },
            )
        }
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
                val media = HashMap<String, Long>()
                val conversation = backups.readConversations(media, only = setOf(threadId)).singleOrNull()
                if (conversation == null) {
                    // Nothing in it worth keeping (a new conversation, or only undownloaded MMS): fine
                    // to delete. Messages with no one to put them back with can't be kept, so they stay.
                    if (before.keepable != 0) Log.w(TAG, "A conversation with no recipients can't be kept")
                    return if (before.keepable == 0) Kept(null, before) else null
                }
                partial.outputStream().use { output ->
                    BackupArchive.write(output, WinnowBackup(createdAt = now, conversations = listOf(conversation))) { part ->
                        media[part.file]?.let { id ->
                            runCatching { context.contentResolver.openInputStream(ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI, id)) }.getOrNull()
                        }
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
            try {
                val backup = item.file.inputStream().use { input ->
                    BackupArchive.read(input) { name, stream -> File(spool, name).outputStream().use { stream.copyTo(it) } }
                }
                // Not reported to the Backup settings, which may be busy with a backup of their own.
                val restored = backups.restoreMessages(backup, spool, report = {})
                val complete = restored.covers(backup.messageCount)
                if (complete) item.file.delete()
                Restored(restored.added, complete)
            } finally {
                spool.deleteRecursively()
                reload()
            }
        }
    }

    /** Gone for good, now rather than after [KEEP_DAYS] days. */
    suspend fun forget(items: Collection<Item>) = lock.withLock {
        withContext(Dispatchers.IO) {
            items.forEach { it.file.delete() }
            reload()
        }
    }

    /** At start-up: drops what's been here longer than [KEEP_DAYS] days (and anything half-written), and lists the rest. */
    suspend fun purgeExpired() = lock.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { file -> if (deletedAt(file)?.let { now - it > KEEP_DAYS * DAY_MILLIS } != false) file.delete() }
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
        )
    }

    private fun deletedAt(file: File): Long? = file.name.substringBefore('-').toLongOrNull()?.takeIf { file.extension == "zip" }

    companion object {
        const val KEEP_DAYS = 30L
        private const val DAY_MILLIS = 24 * 60 * 60_000L
        private const val ATTEMPTS = 3
        private const val TAG = "WinnowTrash"
    }
}
