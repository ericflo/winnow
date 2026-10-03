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
                runCatching { container.notifier.showMessage(-1, listOf(address), address, address, body) }
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

/** `sms:+15551234567?body=Hello%20there` → "Hello there", as browsers and other apps link it. */
fun smsBodyOf(uri: Uri): String? =
    uri.schemeSpecificPart.orEmpty().substringAfter('?', "").split('&')
        .firstOrNull { it.startsWith("body=") }?.removePrefix("body=")?.let(Uri::decode)

/** `smsto:+15551234567,+15557654321?body=hi` → the recipient addresses. */
fun recipientsOf(uri: Uri): List<String> =
    Uri.decode(uri.schemeSpecificPart.orEmpty().substringBefore('?'))
        .split(',', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
