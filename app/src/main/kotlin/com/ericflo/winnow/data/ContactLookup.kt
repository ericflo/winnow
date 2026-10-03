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
    // Empty string caches "not a contact".
    private val names = ConcurrentHashMap<String, String>()

    fun displayName(address: String): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return names.getOrPut(address) { query(address).orEmpty() }.ifEmpty { null }
    }

    fun isContact(address: String): Boolean = displayName(address) != null

    fun clear() = names.clear()

    private fun query(address: String): String? {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    companion object {
        fun formatAddress(address: String): String =
            if (address.any(Char::isLetter)) address
            else PhoneNumberUtils.formatNumber(address, Locale.getDefault().country) ?: address
    }
}
