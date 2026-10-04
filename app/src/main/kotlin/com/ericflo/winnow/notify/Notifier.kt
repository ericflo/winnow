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
import com.ericflo.winnow.ui.BubbleActivity
import com.ericflo.winnow.ui.MainActivity
import android.provider.Settings

/** Conversation notifications with inline reply and mark-as-read. */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.channel_messages_description) }
        val notSent = NotificationChannel(CHANNEL_NOT_SENT, context.getString(R.string.channel_not_sent), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.channel_not_sent_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, notSent))
    }

    /**
     * A text that couldn't go out (no signal, airplane mode, a carrier refusal, or a scheduled one
     * that failed when its time came). Tapping opens the conversation, where it can be retried;
     * opening the conversation any other way clears it too.
     */
    fun showNotSent(threadId: Long, recipients: List<String>, title: String, body: String, scheduled: Boolean = false) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_THREAD)
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .putExtra(MainActivity.EXTRA_ADDRESS, joinAddresses(recipients))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val heading = if (scheduled) "Scheduled message not sent" else "Message not sent"
        val line = "To $title: ${body.ifBlank { "your message" }}"
        val notification = NotificationCompat.Builder(context, CHANNEL_NOT_SENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$line\nOpen the conversation to try again."))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            // Its own request code range, so it doesn't replace the conversation notification's intent.
            .setContentIntent(PendingIntent.getActivity(context, -1 - notificationId(threadId), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_NOT_SENT)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(heading)
                    .build(),
            )
            .build()
        manager.notify(TAG_NOT_SENT, notificationId(threadId), notification)
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
        /** Keep the notification off the lock screen entirely. */
        hideOnLockScreen: Boolean = false,
        /** Offer "Spam", for a stranger's text the classifier let through. */
        offerSpam: Boolean = false,
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

        val shortcutId = pushShortcut(threadId, joined, conversationTitle, sender, photo)
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            // What a locked phone shows when Android hides notification contents: no name, no text.
            .setVisibility(if (hideOnLockScreen) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("New message")
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .build(),
            )
        if (code != null) {
            val copy = actionIntent(NotificationActionReceiver.ACTION_COPY_CODE, threadId, joined, mutable = false) {
                putExtra(NotificationActionReceiver.EXTRA_CODE, code)
            }
            builder.addAction(R.drawable.ic_copy, "Copy $code", copy)
        }
        // Without a stored thread (the store refused the message) there's nothing to reply into.
        if (threadId >= 0) {
            builder.addAction(reply).addAction(markRead)
            if (offerSpam) {
                builder.addAction(R.drawable.ic_block, "Spam", actionIntent(NotificationActionReceiver.ACTION_SPAM, threadId, joined, mutable = false))
            }
            // Lets Android float the conversation as a chat bubble, if the user allows bubbles.
            val bubble = Intent(context, BubbleActivity::class.java)
                .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
                .putExtra(MainActivity.EXTRA_ADDRESS, joined)
            builder.setBubbleMetadata(
                NotificationCompat.BubbleMetadata.Builder(
                    // Bubble intents must be mutable: the system adds the bubble's own extras.
                    PendingIntent.getActivity(context, id, bubble, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    photo?.let(IconCompat::createWithAdaptiveBitmap) ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher),
                )
                    .setDesiredHeight(640)
                    .build(),
            )
        }
        val notification = builder.build()
        manager.notify(TAG, id, notification)
    }

    /**
     * A long-lived conversation shortcut puts the notification in the shade's Conversations
     * section (priority, bubbles) and the thread on the launcher icon's long-press menu.
     */
    private fun pushShortcut(threadId: Long, joined: String, title: String, person: Person, photo: android.graphics.Bitmap?): String {
        val shortcutId = shortcutId(threadId)
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_THREAD)
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .putExtra(MainActivity.EXTRA_ADDRESS, joined)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(
                context,
                ShortcutInfoCompat.Builder(context, shortcutId)
                    .setShortLabel(title)
                    .setLongLived(true)
                    .setIsConversation()
                    .setLocusId(LocusIdCompat(shortcutId))
                    .setPerson(person)
                    .setIcon(photo?.let(IconCompat::createWithBitmap) ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(open)
                    .build(),
            )
        }
        return shortcutId
    }

    /**
     * Android's own notification settings for one conversation: its sound, vibration, priority
     * and bubble. Those settings hang off the conversation's shortcut, so it's made first.
     */
    fun conversationSettings(threadId: Long, recipients: List<String>, title: String): Intent {
        val shortcutId = pushShortcut(threadId, joinAddresses(recipients), title, Person.Builder().setName(title).setKey(title).build(), null)
        // Settings shows the general Messages page until the conversation has a channel of its
        // own. Notifications for this conversation move to it automatically, by shortcut ID.
        val system = context.getSystemService(NotificationManager::class.java)
        if (conversationChannel(system, shortcutId) == null) {
            val parent = system.getNotificationChannel(CHANNEL_MESSAGES)
            // A copy of Messages as the user has it, since an app can't change a channel's sound
            // or vibration once it exists; Android's own conversation channels copy it too.
            val channel = NotificationChannel("$CHANNEL_MESSAGES:$shortcutId", title, parent?.importance ?: NotificationManager.IMPORTANCE_HIGH).apply {
                setConversationId(CHANNEL_MESSAGES, shortcutId)
                parent?.let { p ->
                    description = p.description
                    setSound(p.sound, p.audioAttributes)
                    enableVibration(p.shouldVibrate())
                    vibrationPattern = p.vibrationPattern
                    enableLights(p.shouldShowLights())
                    lightColor = p.lightColor
                    setShowBadge(p.canShowBadge())
                    lockscreenVisibility = p.lockscreenVisibility
                    group = p.group
                }
            }
            system.createNotificationChannel(channel)
        }
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_MESSAGES)
            .putExtra(Settings.EXTRA_CONVERSATION_ID, shortcutId)
    }

    fun cancel(threadId: Long) {
        manager.cancel(TAG, notificationId(threadId))
        manager.cancel(TAG_NOT_SENT, notificationId(threadId))
    }

    /** Drops notifications and conversation shortcuts for deleted threads. */
    fun forget(threadIds: Collection<Long>) {
        threadIds.forEach(::cancel)
        val system = context.getSystemService(NotificationManager::class.java)
        threadIds.forEach { id -> conversationChannel(system, shortcutId(id))?.let { system.deleteNotificationChannel(it.id) } }
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, threadIds.map(::shortcutId)) }
    }

    private fun shortcutId(threadId: Long) = "thread-$threadId"

    /** The conversation's own channel, if it has one. The platform call falls back to the parent channel, which mustn't be mistaken for it. */
    private fun conversationChannel(system: NotificationManager, shortcutId: String): NotificationChannel? =
        system.getNotificationChannel(CHANNEL_MESSAGES, shortcutId)?.takeIf { it.conversationId == shortcutId }

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
        const val CHANNEL_NOT_SENT = "not_sent"
        const val TAG = "thread"
        const val TAG_NOT_SENT = "not_sent"
    }
}
