package com.ericflo.winnow.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.splitAddresses
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Handles Reply and Mark as read from a message notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        val recipients = splitAddresses(intent.getStringExtra(EXTRA_RECIPIENTS).orEmpty())
        if (threadId < 0 || recipients.isEmpty()) return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (intent.action == ACTION_REPLY && !reply.isNullOrEmpty()) container.messages.send(recipients, reply)
                container.messages.markRead(threadId)
                container.notifier.cancel(threadId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Notification action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.ericflo.winnow.REPLY"
        const val ACTION_MARK_READ = "com.ericflo.winnow.MARK_READ"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_RECIPIENTS = "recipients"
        const val KEY_REPLY = "reply"
        private const val TAG = "WinnowNotifyAction"
    }
}
