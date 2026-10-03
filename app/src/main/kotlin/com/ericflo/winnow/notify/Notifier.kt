package com.ericflo.winnow.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.ericflo.winnow.R
import com.ericflo.winnow.data.joinAddresses
import com.ericflo.winnow.ui.MainActivity

/** Conversation notifications with inline reply and mark-as-read. */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.channel_messages_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts or extends the notification for [threadId]. Messages already showing for the thread
     * stay in the stack, so three texts in a row read as one conversation.
     */
    fun showMessage(
        threadId: Long,
        recipients: List<String>,
        conversationTitle: String,
        senderName: String,
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        /** A verification code in [body], offered as a one-tap "Copy" action. */
        code: String? = null,
        senderPhotoUri: String? = null,
    ) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = notificationId(threadId)
        val photo = senderPhotoUri?.let { uri ->
            runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream) }.getOrNull()
        }
        val sender = Person.Builder().setName(senderName).setKey(senderName).apply {
            photo?.let { setIcon(IconCompat.createWithBitmap(it)) }
        }.build()
        val previous = manager.activeNotifications.firstOrNull { it.tag == TAG && it.id == id }
            ?.notification?.let(NotificationCompat.MessagingStyle::extractMessagingStyleFromNotification)
        val style = previous ?: NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        style.addMessage(body, timestamp, sender)
        if (recipients.size > 1) {
            style.conversationTitle = conversationTitle
            style.isGroupConversation = true
        }

        val joined = joinAddresses(recipients)
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_THREAD)
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .putExtra(MainActivity.EXTRA_ADDRESS, joined)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "Reply",
            actionIntent(NotificationActionReceiver.ACTION_REPLY, threadId, joined, mutable = true),
        )
            .addRemoteInput(RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val markRead = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "Mark as read",
            actionIntent(NotificationActionReceiver.ACTION_MARK_READ, threadId, joined, mutable = false),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        // A long-lived conversation shortcut puts the notification in the shade's Conversations
        // section (priority, bubbles) and the thread on the launcher icon's long-press menu.
        val shortcutId = shortcutId(threadId)
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(
                context,
                ShortcutInfoCompat.Builder(context, shortcutId)
                    .setShortLabel(conversationTitle)
                    .setLongLived(true)
                    .setIsConversation()
                    .setLocusId(LocusIdCompat(shortcutId))
                    .setPerson(sender)
                    .setIcon(photo?.let(IconCompat::createWithBitmap) ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(Intent(open).setAction(MainActivity.ACTION_OPEN_THREAD))
                    .build(),
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (code != null) {
            val copy = actionIntent(NotificationActionReceiver.ACTION_COPY_CODE, threadId, joined, mutable = false) {
                putExtra(NotificationActionReceiver.EXTRA_CODE, code)
            }
            builder.addAction(R.drawable.ic_copy, "Copy $code", copy)
        }
        // Without a stored thread (the store refused the message) there's nothing to reply into.
        if (threadId >= 0) builder.addAction(reply).addAction(markRead)
        val notification = builder.build()
        manager.notify(TAG, id, notification)
    }

    fun cancel(threadId: Long) {
        manager.cancel(TAG, notificationId(threadId))
    }

    /** Drops notifications and conversation shortcuts for deleted threads. */
    fun forget(threadIds: Collection<Long>) {
        threadIds.forEach(::cancel)
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, threadIds.map(::shortcutId)) }
    }

    private fun shortcutId(threadId: Long) = "thread-$threadId"

    private fun actionIntent(
        action: String,
        threadId: Long,
        recipients: String,
        mutable: Boolean,
        extras: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
            .putExtra(NotificationActionReceiver.EXTRA_RECIPIENTS, recipients)
            .apply(extras)
        // RemoteInput fills in the reply text, so the reply intent must be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (action.hashCode() * 31) + notificationId(threadId), intent, flags)
    }

    private fun notificationId(threadId: Long) = threadId.toInt()

    private companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val TAG = "thread"
    }
}
