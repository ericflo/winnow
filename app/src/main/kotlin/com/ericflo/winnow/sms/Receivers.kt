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
            } finally {
                pending.finish()
            }
        }
    }
}

/** Incoming MMS notification. Required for the default SMS role. */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // TODO(mms): parse the WAP push, download with SmsManager.downloadMultimediaMessage, store, classify.
        Log.i(TAG, "MMS arrived; MMS support is not implemented yet")
    }

    private companion object {
        const val TAG = "WinnowMms"
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
                val sender = (application as WinnowApp).container.smsSender
                recipients.forEach { sender.send(it, text) }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** `smsto:+15551234567,+15557654321?body=hi` → the recipient addresses. */
fun recipientsOf(uri: Uri): List<String> =
    Uri.decode(uri.schemeSpecificPart.orEmpty().substringBefore('?'))
        .split(',', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
