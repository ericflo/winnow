package com.ericflo.winnow.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager

class SmsSender(private val context: Context) {

    /** Records the message in the outbox and sends it; [SmsStatusReceiver] moves it to sent or failed. */
    fun send(address: String, body: String): Uri? {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        }
        val uri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        val manager = context.getSystemService(SmsManager::class.java)
        val parts = manager.divideMessage(body)
        val status = PendingIntent.getBroadcast(
            context,
            uri?.lastPathSegment?.toIntOrNull() ?: 0,
            Intent(context, SmsStatusReceiver::class.java).setData(uri),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.sendMultipartTextMessage(address, null, parts, ArrayList(parts.map { status }), null)
        return uri
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val type = if (resultCode == Activity.RESULT_OK) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED
        context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.TYPE, type) }, null, null)
    }
}
