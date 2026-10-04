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
     * Keeps [threadIds] in Recently deleted, then deletes them from the phone. A conversation that
     * couldn't be kept (say, the storage is full) isn't deleted.
     */
    suspend fun delete(threadIds: Set<Long>): Deleted = lock.withLock {
        // Not the SMS app: Android would quietly refuse the delete, leaving a copy here too.
        if (!canWrite()) return@withLock Deleted("Make Winnow your SMS app to delete conversations", emptyList())
        withContext(Dispatchers.IO) {
            val files = threadIds.associateWith { keep(it) }
            val kept = files.filterValues { it.ok }.keys
            if (kept.isNotEmpty()) {
                repo.deleteThreads(kept)
                states.forget(kept)
                notifier.forget(kept)
            }
            reload()
            val made = files.values.mapNotNull { it.file }.toSet()
            Deleted(
                "Couldn't keep a conversation in Recently deleted, so it wasn't deleted".takeIf { kept.size != threadIds.size },
                _items.value.filter { it.file in made },
            )
        }
    }

    private data class Kept(val ok: Boolean, val file: File? = null)

    /**
     * Writes [threadId] to a file here. It's read again if a message arrives (or goes) while it's
     * being written, so what's deleted afterwards is exactly what was kept. MMS that were never
     * downloaded have nothing to keep, and aren't.
     */
    private suspend fun keep(threadId: Long): Kept {
        repeat(ATTEMPTS) {
            val before = snapshot(threadId)
            val media = HashMap<String, Long>()
            val conversation = try {
                backups.readConversations(media, only = setOf(threadId)).singleOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read a conversation to keep", e)
                return Kept(false)
            }
            if (conversation == null) {
                // Nothing in it worth keeping (a new conversation, or only undownloaded MMS): fine to
                // delete. Messages with no one to put them back with can't be kept, so they stay.
                if (before.messages != 0) Log.w(TAG, "A conversation with no recipients can't be kept")
                return Kept(before.messages == 0)
            }
            val now = System.currentTimeMillis()
            val file = File(dir, "$now-$threadId.zip")
            val partial = File(dir, "${file.name}.part")
            try {
                partial.outputStream().use { output ->
                    BackupArchive.write(output, WinnowBackup(createdAt = now, conversations = listOf(conversation))) { part ->
                        media[part.file]?.let { id ->
                            runCatching { context.contentResolver.openInputStream(ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI, id)) }.getOrNull()
                        }
                    }
                }
                // On the disk before the messages are deleted: a crash or power cut can't leave neither.
                FileOutputStream(partial, true).use { it.fd.sync() }
                if (snapshot(threadId) == before && partial.renameTo(file)) return Kept(true, file)
            } catch (e: CancellationException) {
                partial.delete()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't keep a conversation in Recently deleted", e)
                partial.delete()
                return Kept(false)
            }
            partial.delete()
        }
        Log.w(TAG, "A conversation kept changing while it was being kept")
        return Kept(false)
    }

    /** How many keepable messages [threadId] has, and its newest of each kind: enough to notice a change. */
    private data class Snapshot(val messages: Int, val newestSms: Long, val newestMms: Long)

    private fun snapshot(threadId: Long): Snapshot {
        fun ids(uri: android.net.Uri, selection: String): List<Long> =
            context.contentResolver.query(uri, arrayOf("_id"), selection, arrayOf(threadId.toString()), null)
                ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()
        val sms = ids(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}")
        val mms = ids(
            Telephony.Mms.CONTENT_URI,
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS} AND " +
                "${Telephony.Mms.MESSAGE_TYPE} != ${MmsStore.MESSAGE_TYPE_NOTIFICATION_IND}",
        )
        return Snapshot(sms.size + mms.size, sms.maxOrNull() ?: 0, mms.maxOrNull() ?: 0)
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
                val (added, present) = backups.restoreMessages(backup, spool, report = {})
                val complete = added + present >= backup.messageCount
                if (complete) item.file.delete()
                Restored(added, complete)
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
