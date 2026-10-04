package com.ericflo.winnow.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
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

    private fun privateAuthorities() = setOf("${context.packageName}.mms", "mms", "sms", "mms-sms")

    private companion object {
        const val TAG = "WinnowShare"
    }

    /** A fresh file for the camera app to write a photo into, and the URI to hand it. */
    fun newCameraPhoto(): Pair<File, Uri> {
        val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
        return file to FileProvider.getUriForFile(context, "${context.packageName}.mms", file)
    }

    /** Drops shares the user never sent, and copies handed to other apps a day ago. */
    fun cleanUp(olderThanMillis: Long = 24 * 60 * 60_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        listOf(dir, File(context.cacheDir, "camera"), File(context.cacheDir, "outbox"), File(context.cacheDir, "voice")).forEach { d ->
            d.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        }
    }
}
