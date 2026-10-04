package com.ericflo.winnow.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactEntry(val name: String, val number: String, val photoUri: String? = null)

/** Contacts for New chat, by phone number and by email address (an MMS can go to either). */
class ContactsSource(private val context: Context) {

    suspend fun all(): List<ContactEntry> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return withContext(Dispatchers.IO) {
            val phones = read(Phone.CONTENT_URI, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.PHOTO_THUMBNAIL_URI)
            val emails = read(Email.CONTENT_URI, Email.DISPLAY_NAME, Email.ADDRESS, Email.PHOTO_THUMBNAIL_URI).filter { isEmailAddress(it.number) }
            (phones + emails)
                .sortedWith(compareBy(java.text.Collator.getInstance()) { it: ContactEntry -> it.name })
                .distinctBy { it.name to normalizeAddress(it.number) }
        }
    }

    private fun read(uri: android.net.Uri, name: String, address: String, photo: String): List<ContactEntry> =
        context.contentResolver.query(uri, arrayOf(name, address, photo), null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val who = c.getString(0) ?: continue
                    val at = c.getString(1)?.trim()?.takeIf(String::isNotEmpty) ?: continue
                    add(ContactEntry(who, at, c.getString(2)))
                }
            }
        }.orEmpty()
}
