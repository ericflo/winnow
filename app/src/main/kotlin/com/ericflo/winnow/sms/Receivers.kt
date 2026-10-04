package com.ericflo.winnow.sms

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.ericflo.winnow.WinnowApp
import kotlinx.coroutines.launch

/** Incoming SMS. Only the default SMS app receives SMS_DELIVER, and it alone must store the message. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        val first = parts.firstOrNull() ?: return
        val address = first.displayOriginatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val subscriptionId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)

        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.incoming.onSmsDelivered(address, body, first.timestampMillis, subscriptionId)
            } catch (e: Exception) {
                // Never lose an SMS to a bug downstream: at least tell the user it arrived.
                Log.e("WinnowSms", "Handling incoming SMS failed", e)
                runCatching {
                    container.notifier.showMessage(
                        -1, listOf(address), address, address, body,
                        hideOnLockScreen = runCatching { container.settings.current().hideOnLockScreen }.getOrDefault(false),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Incoming MMS notification (WAP push). Only the default SMS app receives WAP_PUSH_DELIVER. */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pdu = intent.getByteArrayExtra("data") ?: return
        val subscriptionId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.getDefaultSmsSubscriptionId())
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.mmsReceiver.onPush(pdu, subscriptionId)
            } catch (e: Exception) {
                Log.e("WinnowMms", "Handling MMS push failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/** "Respond via message" from the incoming-call screen. Required for the default SMS role. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val recipients = intent.data?.let(::recipientsOf).orEmpty()
            if (!text.isNullOrBlank()) {
                val container = (application as WinnowApp).container
                container.appScope.launch {
                    try {
                        recipients.forEach { container.smsSender.send(it, text) }
                    } finally {
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** `sms:+15551234567?body=hi` → "hi". Null for anything but an sms:, smsto:, mms: or mmsto: URI. */
fun smsBodyOf(uri: Uri): String? = SmsUris.body(uri.scheme, uri.encodedSchemeSpecificPart)

/**
 * `smsto:+15551234567,+15557654321?body=hi` → the recipient addresses. Empty for any other
 * scheme: a photo shared from the Files app carries its content:// URI in the same field.
 */
fun recipientsOf(uri: Uri): List<String> = SmsUris.recipients(uri.scheme, uri.encodedSchemeSpecificPart)

/** Parses sms:-style URIs as plain strings, so it's testable without Android. */
object SmsUris {
    private val SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

    fun recipients(scheme: String?, encodedPart: String?): List<String> {
        if (scheme?.lowercase() !in SCHEMES) return emptyList()
        return decode(encodedPart.orEmpty().substringBefore('?')).split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun body(scheme: String?, encodedPart: String?): String? {
        if (scheme?.lowercase() !in SCHEMES) return null
        return encodedPart.orEmpty().substringAfter('?', "").split('&').firstOrNull { it.startsWith("body=") }?.removePrefix("body=")?.let(::decode)
    }

    /** Percent-decoding that leaves "+" alone, since it starts phone numbers. */
    private fun decode(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 <= s.lastIndex && s.substring(i + 1, i + 3).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                out.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                out.write(c.toString().toByteArray())
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
