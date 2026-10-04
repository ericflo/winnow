package com.ericflo.winnow.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import android.provider.ContactsContract

/**
 * Photos, videos and contacts other apps share into Winnow. They're copied into the cache right away,
 * because the sharing app's permission to read them can end before the user picks who to
 * send them to.
 */
class SharedFiles(private val context: Context) {
    private val dir = File(context.cacheDir, "shared").apply { mkdirs() }

    /** [fallbackType] is the share intent's own type, for providers that won't say; a wildcard like image/any gets a typical type. */
    fun import(uri: Uri, fallbackType: String? = null): OutgoingAttachment? {
        // Only another app's content. file:// (which would read with Winnow's own permissions,
        // its private files included) and the message stores Winnow alone can read are refused.
        // The host, not the authority: "content://0@mms/part/1" names the same provider as
        // "content://mms/part/1", and ContentResolver strips the user prefix.
        val host = uri.host
        if (uri.scheme != "content" || host == null || host in privateAuthorities()) {
            Log.w(TAG, "Refused a shared item from $uri")
            return null
        }
        val resolver = context.contentResolver
        val type = runCatching { resolver.getType(uri) }.getOrNull()
            ?: fallbackType?.takeIf { !it.endsWith("/*") }
            ?: fallbackType?.let { if (it.startsWith("video/")) "video/mp4" else if (it.startsWith("image/")) "image/jpeg" else null }
            ?: return null.also { Log.w(TAG, "Shared item has no type: $uri") }
        if (VCard.isVCard(type)) return importContact(uri)
        // Winnow can read contacts and the other app may not, so nothing but a contact card comes from there.
        if (host == ContactsContract.AUTHORITY) return null
        if (!type.startsWith("image/") && !type.startsWith("video/")) return null
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "bin"
        val file = File(dir, "${UUID.randomUUID()}.$extension")
        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
        }.onFailure { Log.w(TAG, "Couldn't read a shared $type", it) }.getOrDefault(false)
        return if (copied) OutgoingAttachment(Uri.fromFile(file).toString(), type, name) else null
    }

    /**
     * A copy of one of a message's own attachments (an MMS part in the system store), to forward.
     * Anything else is refused: this reads with Winnow's own permissions.
     */
    fun copyPart(attachment: Attachment): OutgoingAttachment? {
        val uri = Uri.parse(attachment.uri)
        if (uri.scheme != "content" || uri.host != "mms" || uri.pathSegments.firstOrNull() != "part") return null
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(attachment.contentType) ?: "bin"
        val file = File(dir, "${UUID.randomUUID()}.$extension")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
        }.getOrDefault(false)
        if (!copied) {
            file.delete()
            return null
        }
        return OutgoingAttachment(Uri.fromFile(file).toString(), attachment.contentType, attachment.name)
    }

    /** A contact picked in the composer, as a vCard from the Contacts provider's own export. */
    fun contactCard(contactUri: Uri): OutgoingAttachment? {
        val lookupKey = runCatching {
            context.contentResolver.query(contactUri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return lookupKey?.let { importContact(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, it)) }
    }

    /**
     * A card from a phone number picked with the system's number picker, for when Winnow can't
     * read contacts: the picker's grant covers that one row, its name and number, and nothing more.
     */
    fun phoneCard(phoneUri: Uri): OutgoingAttachment? = runCatching {
        context.contentResolver.query(phoneUri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
            ?.use { c -> if (c.moveToFirst()) VCardContact(c.getString(0).orEmpty(), listOfNotNull(c.getString(1)?.takeIf { it.isNotBlank() }), emptyList()) else null }
    }.onFailure { Log.w(TAG, "Couldn't read the picked number", it) }.getOrNull()
        ?.takeIf { it.name.isNotEmpty() || it.phones.isNotEmpty() }
        ?.let { saveCard(VCard.write(it), it) }

    /** Copies a vCard without its photo, which would rarely fit in an MMS; named for the contact. */
    private fun importContact(uri: Uri): OutgoingAttachment? {
        val text = runCatching {
            // Read generously: the photo, stripped next, can be most of it.
            context.contentResolver.openInputStream(uri)?.use { VCard.read(it, VCard.MAX_BYTES * 8) }
        }.onFailure { Log.w(TAG, "Couldn't read a shared contact", it) }.getOrNull() ?: return null
        val card = VCard.withoutPhotos(text)
        val contact = VCard.parse(card).firstOrNull() ?: return null
        if (card.length > VCard.MAX_BYTES) return null
        return saveCard(card, contact)
    }

    private fun saveCard(card: String, contact: VCardContact): OutgoingAttachment {
        val name = contact.name.ifBlank { contact.phones.firstOrNull() ?: "Contact" }
        val file = File(dir, "${UUID.randomUUID()}.vcf")
        file.writeText(card)
        return OutgoingAttachment(Uri.fromFile(file).toString(), VCard.CONTENT_TYPE, "${name.replace(Regex("[/\\\\:*?\"<>|]"), " ").trim()}.vcf")
    }

    /**
     * A copy of the photo [attachment] turned a quarter-turn clockwise (its camera orientation
     * applied first), for the composer's Rotate. Null if it can't be read. At most [ROTATED_EDGE_PX]
     * on a side: it'll be shrunk to fit an MMS anyway.
     */
    fun rotated(attachment: OutgoingAttachment): OutgoingAttachment? = runCatching {
        val uri = Uri.parse(attachment.uri)
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > ROTATED_EDGE_PX) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val orientation = resolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        // The camera's orientation as the thumbnail shows it (mirrors too), then the quarter-turn.
        val matrix = upright(orientation).apply { postRotate(90f) }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { turned.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (turned !== bitmap) turned.recycle()
        bitmap.recycle()
        OutgoingAttachment(Uri.fromFile(file).toString(), "image/jpeg", attachment.name?.substringBeforeLast('.')?.let { "$it.jpg" })
    }.onFailure { Log.w(TAG, "Couldn't rotate a photo", it) }.getOrNull()

    /** A photo's EXIF orientation (normal if it has none, or isn't a format that carries one). */
    fun orientationOf(uri: String): Int = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /** A photo's width and height as it's shown, upright; null if it can't be read. */
    fun uprightSize(uri: String): Pair<Int, Int>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (PhotoCrop.swapsSides(orientationOf(uri))) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
    }.getOrNull()

    /**
     * A copy of the photo [attachment] cut to [box] (fractions of it as it's shown, upright),
     * for the composer's Crop. Cut from the full-size file, so a small crop stays sharp; at most
     * [ROTATED_EDGE_PX] on a side. A PNG stays a PNG (screenshots); anything else becomes a JPEG.
     */
    fun cropped(attachment: OutgoingAttachment, box: PhotoCrop.Box): OutgoingAttachment? = runCatching {
        val uri = Uri.parse(attachment.uri)
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val orientation = orientationOf(attachment.uri)
        val stored = PhotoCrop.toStored(box, orientation)
        val region = Rect(
            (stored.left * width).roundToInt().coerceIn(0, width - 1),
            (stored.top * height).roundToInt().coerceIn(0, height - 1),
            (stored.right * width).roundToInt().coerceIn(1, width),
            (stored.bottom * height).roundToInt().coerceIn(1, height),
        )
        if (region.width() < 1 || region.height() < 1) return null
        var sample = 1
        while (maxOf(region.width(), region.height()) / sample > ROTATED_EDGE_PX) sample *= 2
        val piece = resolver.openInputStream(uri)?.use { input ->
            BitmapRegionDecoder.newInstance(input)?.let { decoder ->
                try {
                    decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sample })
                } finally {
                    decoder.recycle()
                }
            }
        } ?: return null
        val shown = Bitmap.createBitmap(piece, 0, 0, piece.width, piece.height, upright(orientation), true)
        val png = attachment.contentType.equals("image/png", ignoreCase = true)
        val file = File(dir, "${UUID.randomUUID()}.${if (png) "png" else "jpg"}")
        file.outputStream().use { shown.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
        if (shown !== piece) shown.recycle()
        piece.recycle()
        OutgoingAttachment(
            Uri.fromFile(file).toString(),
            if (png) "image/png" else "image/jpeg",
            attachment.name?.substringBeforeLast('.')?.let { "$it.${if (png) "png" else "jpg"}" },
        )
    }.onFailure { Log.w(TAG, "Couldn't crop a photo", it) }.getOrNull()

    /** Turns a photo stored with EXIF [orientation] upright, mirrors and all. */
    private fun upright(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        }
    }

    /** Deletes [attachment]'s file if it's one of the copies here (a rotation's, say), now replaced. */
    fun discardCopy(attachment: OutgoingAttachment) {
        val path = Uri.parse(attachment.uri).takeIf { it.scheme == "file" }?.path ?: return
        val file = File(path).canonicalFile
        if (file.parentFile == dir.canonicalFile) file.delete()
    }

    private fun privateAuthorities() = setOf("${context.packageName}.mms", "mms", "sms", "mms-sms")

    private companion object {
        const val TAG = "WinnowShare"
        const val ROTATED_EDGE_PX = 2048
    }

    /** A fresh file for the camera app to write a photo into, and the URI to hand it. */
    fun newCameraPhoto(): Pair<File, Uri> = newCameraFile("jpg")

    /** Where the camera app records a video for the composer; shrunk to fit afterwards if need be. */
    fun newCameraVideo(): Pair<File, Uri> = newCameraFile("mp4")

    private fun newCameraFile(extension: String): Pair<File, Uri> {
        val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "${UUID.randomUUID()}.$extension")
        return file to FileProvider.getUriForFile(context, "${context.packageName}.mms", file)
    }

    /** How many bytes an attachment is, if its file or provider says. */
    fun sizeOf(uri: String): Long? = runCatching {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") return@runCatching parsed.path?.let { File(it).length() }
        context.contentResolver.query(parsed, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    /** Drops shares the user never sent, and copies handed to other apps a day ago. */
    fun cleanUp(olderThanMillis: Long = 24 * 60 * 60_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        listOf(dir, File(context.cacheDir, "camera"), File(context.cacheDir, "outbox"), File(context.cacheDir, "voice"), File(context.cacheDir, "video")).forEach { d ->
            d.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        }
    }
}
