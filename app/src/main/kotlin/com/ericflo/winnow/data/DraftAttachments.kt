package com.ericflo.winnow.data

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Attachments waiting in a conversation's composer, kept in the app's own storage (not the cache,
 * and not a photo picker grant, which ends with the app's process) so a draft comes back whole
 * even after Android has closed Winnow in the background.
 */
class DraftAttachments(private val context: Context) {
    @Serializable
    private data class Item(val uri: String, val type: String, val name: String? = null)

    private val dir get() = File(context.filesDir, "drafts").apply { mkdirs() }

    /** [attachment] as a file of its own here: itself if it already is one, null if it can't be read. */
    fun keep(attachment: OutgoingAttachment): OutgoingAttachment? {
        if (isKept(attachment)) return attachment
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(attachment.contentType) ?: "bin"
        val file = File(dir, "${UUID.randomUUID()}.$extension")
        val copied = runCatching {
            context.contentResolver.openInputStream(Uri.parse(attachment.uri))?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
        }.onFailure { Log.w(TAG, "Couldn't keep a draft attachment", it) }.getOrDefault(false)
        if (!copied) {
            file.delete()
            return null
        }
        return attachment.copy(uri = Uri.fromFile(file).toString())
    }

    /** Deletes [attachment]'s kept copy, once it's been sent or taken out of the draft. */
    fun release(attachment: OutgoingAttachment) {
        if (isKept(attachment)) Uri.parse(attachment.uri).path?.let { File(it).delete() }
    }

    /**
     * Deletes kept copies no draft refers to any more (left by a send the app closing cut short).
     * Recent ones are spared: a composer may have just made one and not yet saved the list.
     */
    fun sweep(referenced: Collection<String?>, olderThanMillis: Long = 60 * 60_000L) {
        val keep = referenced.filterNotNull().flatMap(::decode).map { it.uri }.toSet()
        val cutoff = System.currentTimeMillis() - olderThanMillis
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff && Uri.fromFile(it).toString() !in keep) it.delete() }
    }

    fun encode(attachments: List<OutgoingAttachment>): String? =
        if (attachments.isEmpty()) null else json.encodeToString(ListSerializer(Item.serializer()), attachments.map { Item(it.uri, it.contentType, it.name) })

    /** The draft's attachments whose files are still there. */
    fun decode(value: String?): List<OutgoingAttachment> = items(value)
        .map { OutgoingAttachment(it.uri, it.type, it.name) }
        .filter { a -> Uri.parse(a.uri).path?.let { File(it).exists() } == true }

    private fun isKept(attachment: OutgoingAttachment): Boolean {
        val uri = Uri.parse(attachment.uri)
        return uri.scheme == "file" && uri.path?.let { File(it).parentFile?.canonicalPath } == dir.canonicalPath
    }

    companion object {
        private const val TAG = "WinnowDrafts"
        private val json = Json { ignoreUnknownKeys = true }

        private fun items(value: String?): List<Item> =
            if (value.isNullOrEmpty()) emptyList() else runCatching { json.decodeFromString(ListSerializer(Item.serializer()), value) }.getOrDefault(emptyList())

        /** "Photo", "2 photos", "Video and photo"… for the inbox's "Draft:" line. */
        fun summary(value: String?): String? = items(value).takeIf { it.isNotEmpty() }?.let { attachmentSummary(it.map(Item::type)) }
    }
}
