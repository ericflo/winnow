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
import android.telephony.SmsMessage

class SmsSender(private val context: Context, private val deliveryReports: () -> Boolean) {

    /**
     * Records the message in the outbox and sends it; [SmsStatusReceiver] moves it to sent or
     * failed, and with delivery reports on, marks it delivered when the carrier confirms.
     */
    fun send(address: String, body: String): Uri? {
        val reports = deliveryReports()
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.STATUS, if (reports) Telephony.Sms.STATUS_PENDING else Telephony.Sms.STATUS_NONE)
        }
        val uri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        val manager = context.getSystemService(SmsManager::class.java)
        val parts = manager.divideMessage(body)
        val requestCode = uri?.lastPathSegment?.toIntOrNull() ?: 0
        val sent = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, SmsStatusReceiver::class.java).setAction(SmsStatusReceiver.ACTION_SENT).setData(uri),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The radio attaches the status report PDU as extras, so this one must be mutable.
        val delivered = if (!reports) null else PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, SmsStatusReceiver::class.java).setAction(SmsStatusReceiver.ACTION_DELIVERED).setData(uri),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Only the last part's report matters: the message is delivered once all parts are.
        val deliveredIntents = delivered?.let { d -> ArrayList(parts.indices.map { if (it == parts.lastIndex) d else null }) }
        manager.sendMultipartTextMessage(address, null, parts, ArrayList(parts.map { sent }), deliveredIntents)
        return uri
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val values = ContentValues()
        when (intent.action) {
            ACTION_DELIVERED -> {
                val pdu = intent.getByteArrayExtra("pdu") ?: return
                val report = SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")) ?: return
                // TP-Status: 0x00–0x1F completed, 0x20–0x3F still trying, 0x40+ permanent failure.
                val status = when {
                    report.status < 0x20 -> Telephony.Sms.STATUS_COMPLETE
                    report.status < 0x40 -> Telephony.Sms.STATUS_PENDING
                    else -> Telephony.Sms.STATUS_FAILED
                }
                values.put(Telephony.Sms.STATUS, status)
            }
            else -> values.put(
                Telephony.Sms.TYPE,
                if (resultCode == Activity.RESULT_OK) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED,
            )
        }
        context.contentResolver.update(uri, values, null, null)
    }

    companion object {
        const val ACTION_SENT = "com.ericflo.winnow.SMS_SENT"
        const val ACTION_DELIVERED = "com.ericflo.winnow.SMS_DELIVERED"
    }
}
