package com.ericflo.winnow.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ContactLookup(private val context: Context) {
    private data class Info(val name: String, val photoUri: String?)

    // NOT_FOUND caches "not a contact" so unknown senders aren't looked up on every frame.
    private val cache = ConcurrentHashMap<String, Info>()

    // Every contact's numbers, from one query. A PhoneLookup per address cost about a second
    // for an inbox of a few hundred conversations; this is a few milliseconds per thousand.
    @Volatile private var numbers: Map<String, Info>? = null

    fun displayName(address: String): String? = info(address)?.name

    /** The contact's thumbnail photo, if they have one. */
    fun photoUri(address: String): String? = info(address)?.photoUri

    fun isContact(address: String): Boolean = info(address) != null

    fun clear() {
        cache.clear()
        numbers = null
    }

    /**
     * Emits whenever the contact list changes (a contact added, renamed, given a photo), after
     * forgetting what was looked up, so names and photos are read afresh. Nothing without
     * READ_CONTACTS: Android won't let an app watch what it can't read.
     */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                clear()
                trySend(Unit)
            }
        }
        val watching = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            runCatching { context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer) }.isSuccess
        awaitClose { if (watching) context.contentResolver.unregisterContentObserver(observer) }
    }.conflate()

    private fun info(address: String): Info? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return cache.getOrPut(address) { query(address) ?: NOT_FOUND }.takeIf { it !== NOT_FOUND }
    }

    private fun query(address: String): Info? {
        // A phone number the contact list doesn't have isn't a contact; only short codes,
        // emails and the like still go to PhoneLookup.
        numberKey(address)?.let { key -> return index()[key] }
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { Info(it, c.getString(1)) } else null
        }
    }

    private fun index(): Map<String, Info> = numbers ?: loadIndex().also { numbers = it }

    private fun loadIndex(): Map<String, Info> {
        val index = HashMap<String, Info>()
        runCatching {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.DISPLAY_NAME, Phone.PHOTO_THUMBNAIL_URI),
                null, null,
                // Primary numbers first, so a shared number goes to the contact it's primary for.
                "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    val info = Info(name, c.getString(3))
                    listOfNotNull(c.getString(0), c.getString(1)).mapNotNull(::numberKey).forEach { index.putIfAbsent(it, info) }
                }
            }
        }
        return index
    }

    companion object {
        private val NOT_FOUND = Info("", null)

        /**
         * How numbers are matched: the last 10 digits, so "+1 415-555-0192", "(415) 555-0192"
         * and "14155550192" agree, as do "+44 7700 900123" and "07700 900123". Short codes
         * match exactly. Null for emails and alphanumeric senders, which aren't matched this way.
         */
        fun numberKey(address: String): String? {
            if (address.any(Char::isLetter) || '@' in address) return null
            val digits = address.filter(Char::isDigit)
            return when {
                digits.length >= 7 -> digits.takeLast(10)
                digits.isNotEmpty() -> "short:$digits"
                else -> null
            }
        }

        /** A full phone number (7+ digits), not a short code, email or alphanumeric sender. */
        fun isPersonalNumber(address: String): Boolean = numberKey(address)?.startsWith("short:") == false

        fun formatAddress(address: String): String =
            if (address.any(Char::isLetter)) address
            else PhoneNumberUtils.formatNumber(address, Locale.getDefault().country) ?: address
    }
}
