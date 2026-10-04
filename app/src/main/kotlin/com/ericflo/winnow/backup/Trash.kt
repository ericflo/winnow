package com.ericflo.winnow.backup

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.displayNameFor
import com.ericflo.winnow.notify.Notifier
import com.ericflo.winnow.data.ConversationStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

    /** What [delete] did: [ok] unless some conversation couldn't be kept (and so wasn't deleted). */
    data class Deleted(val ok: Boolean, val items: List<Item>)

    /**
     * Keeps [threadIds] in Recently deleted, then deletes them from the phone. A conversation that
     * couldn't be kept (say, the storage is full) isn't deleted.
     */
    suspend fun delete(threadIds: Set<Long>): Deleted = lock.withLock {
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
            Deleted(kept.size == threadIds.size, _items.value.filter { it.file in made })
        }
    }

    private data class Kept(val ok: Boolean, val file: File? = null)

    private suspend fun keep(threadId: Long): Kept {
        val media = HashMap<String, Long>()
        val conversation = runCatching { backups.readConversations(media, only = setOf(threadId)).singleOrNull() }
            .onFailure { Log.w(TAG, "Couldn't read a conversation to keep", it) }.getOrNull()
            // Nothing in it (a new, empty conversation): nothing to keep, fine to delete.
            ?: return Kept(true)
        val now = System.currentTimeMillis()
        val file = File(dir, "$now-$threadId.zip")
        return runCatching {
            file.outputStream().use { output ->
                BackupArchive.write(output, WinnowBackup(createdAt = now, conversations = listOf(conversation))) { part ->
                    media[part.file]?.let { id ->
                        runCatching { context.contentResolver.openInputStream(ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI, id)) }.getOrNull()
                    }
                }
            }
            Kept(true, file)
        }.getOrElse {
            Log.w(TAG, "Couldn't keep a conversation in Recently deleted", it)
            file.delete()
            Kept(false)
        }
    }

    /** Puts [item] back: whatever of it isn't on the phone again is added, then it leaves Recently deleted. */
    suspend fun restore(item: Item): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            val spool = File(context.cacheDir, "trash-restore").apply { deleteRecursively(); mkdirs() }
            try {
                val backup = item.file.inputStream().use { input ->
                    BackupArchive.read(input) { name, stream -> File(spool, name).outputStream().use { stream.copyTo(it) } }
                }
                val (added, _) = backups.restoreMessages(backup, spool)
                // The restore reports its progress to the Backup settings; that's not where this happened.
                backups.dismiss()
                item.file.delete()
                added
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

    /** At start-up: drops what's been here longer than [KEEP_DAYS] days, and lists the rest. */
    suspend fun purgeExpired() = lock.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { file -> if (deletedAt(file)?.let { now - it > KEEP_DAYS * DAY_MILLIS } != false) file.delete() }
            reload()
        }
    }

    private fun reload() {
        _items.value = dir.listFiles().orEmpty().mapNotNull { file ->
            val at = deletedAt(file) ?: return@mapNotNull null
            val conversation = runCatching { file.inputStream().use(BackupArchive::peek).conversations.singleOrNull() }.getOrNull() ?: return@mapNotNull null
            val last = conversation.messages.lastOrNull()
            Item(
                file = file,
                title = conversation.title ?: displayNameFor(conversation.recipients, repo::displayName),
                recipients = conversation.recipients,
                messages = conversation.messages.size,
                deletedAt = at,
                snippet = last?.body?.takeIf { it.isNotBlank() } ?: if (last?.parts?.isNotEmpty() == true) "Photo or attachment" else "",
            )
        }.sortedByDescending { it.deletedAt }
    }

    private fun deletedAt(file: File): Long? = file.name.substringBefore('-').toLongOrNull()?.takeIf { file.extension == "zip" }

    companion object {
        const val KEEP_DAYS = 30L
        private const val DAY_MILLIS = 24 * 60 * 60_000L
        private const val TAG = "WinnowTrash"
    }
}
