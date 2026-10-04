package com.ericflo.winnow.notify

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.splitAddresses
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ericflo.winnow.classifier.message.Action

/** Handles Reply, Mark as read, Copy code and Spam from a message notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        val recipients = splitAddresses(intent.getStringExtra(EXTRA_RECIPIENTS).orEmpty())
        if (threadId < 0 || recipients.isEmpty()) return
        if (intent.action == ACTION_COPY_CODE) {
            val code = intent.getStringExtra(EXTRA_CODE) ?: return
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Verification code", code))
            return
        }
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (intent.action == ACTION_REPLY && !reply.isNullOrEmpty()) {
                    container.messages.send(recipients, reply, subscriptionId = container.simFor(threadId))
                }
                // The same correction as "Always filter": the sender's texts go to Filtered from now on,
                // and the on-phone model learns from this one.
                if (intent.action == ACTION_SPAM && recipients.size == 1) {
                    container.messages.overrideVerdict(threadId, recipients.single(), Action.FILTER)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Moved to Filtered. Winnow will filter this sender.", Toast.LENGTH_SHORT).show()
                    }
                }
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
        const val ACTION_COPY_CODE = "com.ericflo.winnow.COPY_CODE"
        const val ACTION_SPAM = "com.ericflo.winnow.SPAM"
        const val EXTRA_CODE = "code"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_RECIPIENTS = "recipients"
        const val KEY_REPLY = "reply"
        private const val TAG = "WinnowNotifyAction"
    }
}
