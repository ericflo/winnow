package com.ericflo.winnow.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class ContactEntry(val name: String, val number: String, val photoUri: String? = null)

/** Phone contacts for New chat; sample contacts while the app shows sample conversations. */
class ContactsSource(private val context: Context, private val isLive: StateFlow<Boolean>) {

    suspend fun all(): List<ContactEntry> {
        if (!isLive.value) return DEMO
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return withContext(Dispatchers.IO) {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.PHOTO_THUMBNAIL_URI),
                null, null,
                "${Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: continue
                        val number = c.getString(1) ?: continue
                        add(ContactEntry(name, number, c.getString(2)))
                    }
                }
            }.orEmpty().distinctBy { it.name to normalizeAddress(it.number) }
        }
    }

    private companion object {
        val DEMO = listOf(
            ContactEntry("Alex Chen", "+15555550103"),
            ContactEntry("Mom", "+15555550101"),
            ContactEntry("Priya Natarajan", "+15555550104"),
            ContactEntry("Sam Rivera", "+15555550102"),
        )
    }
}
