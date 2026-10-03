package com.ericflo.winnow.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ContactLookup(private val context: Context) {
    private data class Info(val name: String, val photoUri: String?)

    // NOT_FOUND caches "not a contact" so unknown senders aren't looked up on every frame.
    private val cache = ConcurrentHashMap<String, Info>()

    fun displayName(address: String): String? = info(address)?.name

    /** The contact's thumbnail photo, if they have one. */
    fun photoUri(address: String): String? = info(address)?.photoUri

    fun isContact(address: String): Boolean = info(address) != null

    fun clear() = cache.clear()

    private fun info(address: String): Info? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return cache.getOrPut(address) { query(address) ?: NOT_FOUND }.takeIf { it !== NOT_FOUND }
    }

    private fun query(address: String): Info? {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { Info(it, c.getString(1)) } else null
        }
    }

    companion object {
        private val NOT_FOUND = Info("", null)

        fun formatAddress(address: String): String =
            if (address.any(Char::isLetter)) address
            else PhoneNumberUtils.formatNumber(address, Locale.getDefault().country) ?: address
    }
}
