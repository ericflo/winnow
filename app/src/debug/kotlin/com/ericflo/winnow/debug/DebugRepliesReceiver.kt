package com.ericflo.winnow.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ericflo.winnow.SIMULATE_REPLIES

/**
 * Debug builds only. Emulators have no Smart Reply model, so the on-device classifier never
 * suggests a reply; this supplies some, to see the composer's suggestion chips (they're still
 * offered only where real ones would be). Without --es replies, it goes back to the classifier:
 *
 *     adb shell "am broadcast -n com.ericflo.winnow/.debug.DebugRepliesReceiver --es replies 'Sounds good|On my way|Can't today'"
 */
class DebugRepliesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val replies = intent.getStringExtra("replies")
        context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putString(SIMULATE_REPLIES, replies).apply()
        Log.i("WinnowDebugReplies", if (replies == null) "Suggested replies from the classifier" else "Simulated suggested replies: $replies")
    }
}
