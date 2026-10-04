package com.ericflo.winnow.data

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Attachments leaving Winnow: saved into the phone's shared storage, or copied somewhere another
 * app may read them for the share sheet. MMS parts live in the message store, which only the
 * default SMS app can read, so they can't be handed out directly.
 */
class MediaExport(private val context: Context) {
    private val outbox get() = File(context.cacheDir, "outbox").apply { mkdirs() }

    /**
     * Copies [attachment] into Pictures, Movies, Recordings or Download, in a Winnow folder.
     * Returns that folder's name, or null if it couldn't be saved. Needs no permission: apps may
     * always add to shared storage.
     */
    fun save(attachment: Attachment): String? {
        val type = attachment.contentType.substringBefore(';').trim().lowercase()
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val (collection, folder) = when {
            type.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume) to Environment.DIRECTORY_PICTURES
            type.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume) to Environment.DIRECTORY_MOVIES
            type.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(volume) to Environment.DIRECTORY_RECORDINGS
            else -> MediaStore.Downloads.getContentUri(volume) to Environment.DIRECTORY_DOWNLOADS
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(attachment, type))
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Winnow")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return try {
            resolver.openInputStream(Uri.parse(attachment.uri))!!.use { input ->
                resolver.openOutputStream(target)!!.use { input.copyTo(it) }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            folder
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save ${attachment.contentType}", e)
            runCatching { resolver.delete(target, null, null) }
            null
        }
    }

    /** A share sheet for [attachments], as copies the chosen app is allowed to read; null if none could be copied. */
    fun shareIntent(attachments: List<Attachment>): Intent? {
        val copies = attachments.mapNotNull { a -> copyOut(a)?.let { it to a.contentType.substringBefore(';').trim().lowercase() } }
        if (copies.isEmpty()) return null
        val uris = copies.map { it.first }
        val types = copies.map { it.second }.distinct()
        val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = type
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The grant travels with ClipData; EXTRA_STREAM alone isn't enough for every target.
        send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        return Intent.createChooser(send, null)
    }

    /** A share sheet for [text] as a .txt file named [fileName], for exporting a conversation. */
    fun shareText(fileName: String, text: String): Intent? = runCatching {
        // Under the filesystem's 255-byte name limit, even for a group of many numbers.
        val safe = fileName.replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), " ").trim().take(100).trim().ifEmpty { "Conversation" }
        val file = File(File(outbox, UUID.randomUUID().toString()).apply { mkdirs() }, "$safe.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.mms", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, safe)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        Intent.createChooser(send, null)
    }.onFailure { Log.w(TAG, "Couldn't write a transcript", it) }.getOrNull()

    private fun copyOut(attachment: Attachment): Uri? {
        val type = attachment.contentType.substringBefore(';').trim().lowercase()
        val file = File(File(outbox, UUID.randomUUID().toString()).apply { mkdirs() }, fileName(attachment, type))
        return try {
            context.contentResolver.openInputStream(Uri.parse(attachment.uri))!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            FileProvider.getUriForFile(context, "${context.packageName}.mms", file)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't copy ${attachment.contentType} to share", e)
            null
        }
    }

    /** The attachment's own name if it has a usable one, else "Winnow_20261003_213501.jpg". */
    private fun fileName(attachment: Attachment, type: String): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: type.substringAfter('/').substringBefore('+').take(8)
        val own = attachment.name?.replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), " ")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }
        return when {
            own == null -> "Winnow_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.$extension"
            own.contains('.') -> own
            else -> "$own.$extension"
        }
    }

    private companion object {
        const val TAG = "WinnowExport"
    }
}
