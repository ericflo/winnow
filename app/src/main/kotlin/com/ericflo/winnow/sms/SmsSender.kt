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
import com.ericflo.winnow.data.SimpleCharacters
import com.ericflo.winnow.data.Tapback
import kotlinx.coroutines.launch

class SmsSender(
    private val context: Context,
    private val deliveryReports: suspend () -> Boolean,
    /** Maps a chosen SIM to the subscription to send on (null: Android's default). */
    private val forSending: (Int?) -> Int? = { it },
    /** Settings → Simple characters. */
    private val simpleCharacters: suspend () -> Boolean = { false },
) {

    /**
     * [body] as it would go out as a text: with Simple characters on, maybe in plainer
     * characters. Never a reaction (`Loved “…”`), which other phones know by its curly quotes.
     */
    suspend fun prepared(body: String): String =
        if (simpleCharacters() && Tapback.parse(body) == null) SimpleCharacters.forSms(body, ::measureSms) else body

    /**
     * Records the message in the outbox and sends it; [SmsStatusReceiver] moves it to sent or
     * failed, and with delivery reports on, marks it delivered when the carrier confirms.
     */
    suspend fun send(address: String, typed: String, subscriptionId: Int? = null): Uri? {
        val body = prepared(typed)
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
        try {
            transmit(uri, address, body, reports, sub)
        } catch (e: Exception) {
            uri ?: throw e
            // It's in the conversation now: marked not sent (Tap to retry), not left "Sending…" for good.
            context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }, null, null)
            throw StoredAsFailed("sms:${ContentUris.parseId(uri)}", e)
        }
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
        // Claimed by moving it out of failed: of two tries at once (Tap to retry and the notice's
        // Try again), only the one that moved it sends.
        if (context.contentResolver.update(message, values, "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_FAILED}", null) == 0) return
        try {
            transmit(message, address, body, reports, forSending(subscriptionId))
        } catch (e: Exception) {
            // Back to failed, so it can be tried again rather than looking like it's still going.
            context.contentResolver.update(message, ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }, null, null)
            throw e
        }
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

/**
 * The message was stored and then couldn't be handed to the radio: it's in its conversation
 * marked not sent, under [key], to be tried again from there rather than typed again.
 */
class StoredAsFailed(val key: String, cause: Exception) : Exception(cause.message, cause)

/**
 * After a reboot, texts still sending never will be: the radio and the MMS service forgot them
 * with it. Each is marked not sent instead, to be tried again; returns their keys.
 */
fun failStranded(context: Context): List<String> {
    val resolver = context.contentResolver
    val stranded = mutableListOf<String>()
    val smsWaiting = "${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_OUTBOX}, ${Telephony.Sms.MESSAGE_TYPE_QUEUED})"
    resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), smsWaiting, null, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val failed = ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }
            if (resolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), failed, smsWaiting, null) > 0) stranded += "sms:$id"
        }
    }
    val mmsWaiting = "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_OUTBOX}"
    resolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID), mmsWaiting, null, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val failed = ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED) }
            if (resolver.update(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id), failed, mmsWaiting, null) > 0) stranded += "mms:$id"
        }
    }
    return stranded
}

/**
 * A delivery report's status as the store keeps it. GSM's (3GPP) is the TP-Status: 0x00–0x1F
 * completed, 0x20–0x3F still trying, 0x40 and up failed for good. A CDMA network's (3GPP2)
 * comes as an error class in bits 24–25 (none, temporary, permanent) over a message status in
 * bits 16–21, of which 2 is delivered.
 */
internal fun deliveryStatus(status: Int, format: String?): Int {
    if (format == "3gpp2") {
        val errorClass = (status shr 24) and 0x03
        val messageStatus = (status shr 16) and 0x3f
        return when (errorClass) {
            0 -> if (messageStatus == 0x02) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_PENDING
            2 -> Telephony.Sms.STATUS_PENDING
            else -> Telephony.Sms.STATUS_FAILED
        }
    }
    return when {
        status < 0x20 -> Telephony.Sms.STATUS_COMPLETE
        status < 0x40 -> Telephony.Sms.STATUS_PENDING
        else -> Telephony.Sms.STATUS_FAILED
    }
}

/** Android's count for [text] as a text, which knows the carrier's alphabets. */
fun measureSms(text: String): SimpleCharacters.Measure =
    SmsMessage.calculateLength(text, false).let { SimpleCharacters.Measure(it[0], it[3] == SmsMessage.ENCODING_7BIT) }

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val resolver = context.contentResolver
        when (intent.action) {
            ACTION_DELIVERED -> {
                val pdu = intent.getByteArrayExtra("pdu") ?: return
                val format = intent.getStringExtra("format")
                val report = SmsMessage.createFromPdu(pdu, format) ?: return
                resolver.update(uri, ContentValues().apply { put(Telephony.Sms.STATUS, deliveryStatus(report.status, format)) }, null, null)
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
