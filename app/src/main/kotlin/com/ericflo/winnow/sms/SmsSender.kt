package com.ericflo.winnow.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import com.ericflo.winnow.WinnowApp
import kotlinx.coroutines.launch

class SmsSender(
    private val context: Context,
    private val deliveryReports: suspend () -> Boolean,
    /** Maps a chosen SIM to the subscription to send on (null: Android's default). */
    private val forSending: (Int?) -> Int? = { it },
) {

    /**
     * Records the message in the outbox and sends it; [SmsStatusReceiver] moves it to sent or
     * failed, and with delivery reports on, marks it delivered when the carrier confirms.
     */
    suspend fun send(address: String, body: String, subscriptionId: Int? = null): Uri? {
        val reports = deliveryReports()
        val sub = forSending(subscriptionId)
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.STATUS, if (reports) Telephony.Sms.STATUS_PENDING else Telephony.Sms.STATUS_NONE)
            put(Telephony.Sms.SUBSCRIPTION_ID, sub ?: SubscriptionManager.getDefaultSmsSubscriptionId())
        }
        val uri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        transmit(uri, address, body, reports, sub)
        return uri
    }

    /**
     * Sends a failed message again from its existing row. Deleting and re-inserting it instead
     * would empty (and so delete) a new conversation's thread.
     */
    suspend fun retry(message: Uri, address: String, body: String, subscriptionId: Int? = null) {
        val reports = deliveryReports()
        val values = ContentValues().apply {
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.STATUS, if (reports) Telephony.Sms.STATUS_PENDING else Telephony.Sms.STATUS_NONE)
        }
        context.contentResolver.update(message, values, null, null)
        transmit(message, address, body, reports, forSending(subscriptionId))
    }

    private fun transmit(message: Uri?, address: String, body: String, reports: Boolean, subscriptionId: Int?) {
        val manager = context.getSystemService(SmsManager::class.java).let { if (subscriptionId != null) it.createForSubscriptionId(subscriptionId) else it }
        val parts = manager.divideMessage(body)
        val id = message?.lastPathSegment?.toIntOrNull() ?: 0
        // One sent intent per part (distinct request codes), so any failed part marks the message failed.
        val sent = ArrayList(parts.indices.map { part ->
            PendingIntent.getBroadcast(
                context, id * MAX_PARTS + part,
                Intent(context, SmsStatusReceiver::class.java).setAction(SmsStatusReceiver.ACTION_SENT).setData(message),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        })
        // The radio attaches the status-report PDU as extras, so this one must be mutable. Only
        // the last part's report matters: the message is delivered once all parts are.
        val delivered = if (!reports) null else ArrayList(parts.indices.map { part ->
            if (part != parts.lastIndex) {
                null
            } else {
                PendingIntent.getBroadcast(
                    context, id * MAX_PARTS + part,
                    Intent(context, SmsStatusReceiver::class.java).setAction(SmsStatusReceiver.ACTION_DELIVERED).setData(message),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        })
        manager.sendMultipartTextMessage(address, null, parts, sent, delivered)
    }

    private companion object {
        /** Request codes are id * MAX_PARTS + part index; SMS this long don't exist in practice. */
        const val MAX_PARTS = 64
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val resolver = context.contentResolver
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
                resolver.update(uri, ContentValues().apply { put(Telephony.Sms.STATUS, status) }, null, null)
            }
            else -> if (resultCode == Activity.RESULT_OK) {
                // Parts report in any order: only promote a message that no other part has failed.
                resolver.update(
                    uri, ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT) },
                    "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_OUTBOX}", null,
                )
            } else {
                // Each part reports; only the first failure says so.
                val newlyFailed = resolver.update(
                    uri, ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) },
                    "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_FAILED}", null,
                )
                if (newlyFailed > 0) notifyNotSent(context, uri)
            }
        }
    }

    /** Says so, unless the conversation is on screen, where the message already shows "Not sent". */
    private fun notifyNotSent(context: Context, uri: android.net.Uri) {
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                context.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use
                    val threadId = c.getLong(0)
                    val address = c.getString(1).orEmpty()
                    if (container.visibleThread.value == threadId) return@use
                    container.notifier.showNotSent(
                        threadId, listOf(address), container.messages.displayName(address), c.getString(2).orEmpty(),
                        retryKey = "sms:${ContentUris.parseId(uri)}",
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("WinnowSms", "Couldn't say a text wasn't sent", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SENT = "com.ericflo.winnow.SMS_SENT"
        const val ACTION_DELIVERED = "com.ericflo.winnow.SMS_DELIVERED"
    }
}
