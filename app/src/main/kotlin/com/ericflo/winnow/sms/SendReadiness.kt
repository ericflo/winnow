package com.ericflo.winnow.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * What's in the way of sending right now, said before a message fails rather than after:
 * airplane mode, or (for a picture message) mobile data being off. Neither is certain (Wi-Fi
 * calling sends texts in airplane mode; some carriers allow MMS without mobile data), so it's a
 * hint, never a block.
 */
class SendReadiness(private val context: Context) {
    data class State(val airplane: Boolean = false, val mobileDataOff: Boolean = false)

    fun now(subscriptionId: Int? = null): State = State(
        airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
        mobileDataOff = runCatching {
            val telephony = context.getSystemService(TelephonyManager::class.java)
            val forSim = if (subscriptionId != null) telephony.createForSubscriptionId(subscriptionId) else telephony
            // No SIM, no data to speak of: nothing to warn about.
            forSim.simState == TelephonyManager.SIM_STATE_READY && !forSim.isDataEnabled
        }.getOrDefault(false),
    )

    /** [now], again whenever airplane mode flips, and every few seconds (mobile data has no broadcast). */
    fun changes(subscriptionId: () -> Int?): Flow<State> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(now(subscriptionId()))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val poll = launch {
            while (true) {
                trySend(now(subscriptionId()))
                delay(POLL_MILLIS)
            }
        }
        awaitClose {
            poll.cancel()
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()

    private companion object {
        const val POLL_MILLIS = 5_000L
    }
}
