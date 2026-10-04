package com.ericflo.winnow.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.Telephony

/** The threads table without the platform's expensive snippet joins. */
internal val THREADS_SIMPLE: Uri = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()

/** The newest message of each thread, SMS and MMS together, one row per thread. */
internal val MMS_SMS_CONVERSATIONS: Uri = Telephony.MmsSms.CONTENT_CONVERSATIONS_URI

private val CANONICAL_ADDRESSES: Uri = Uri.parse("content://mms-sms/canonical-addresses")

/** thread id → participant addresses, from the threads table and canonical addresses. */
internal fun ContentResolver.threadRecipients(): Map<Long, List<String>> {
    val canonical = HashMap<Long, String>()
    query(CANONICAL_ADDRESSES, arrayOf("_id", "address"), null, null, null)?.use { c ->
        while (c.moveToNext()) canonical[c.getLong(0)] = c.getString(1).orEmpty()
    }
    val result = HashMap<Long, List<String>>()
    query(THREADS_SIMPLE, arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS), null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val ids = c.getString(1).orEmpty().split(' ').mapNotNull { it.toLongOrNull() }
            result[c.getLong(0)] = ids.mapNotNull(canonical::get).filter { it.isNotBlank() }
        }
    }
    return result
}
