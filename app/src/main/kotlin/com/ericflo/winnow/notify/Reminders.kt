package com.ericflo.winnow.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ericflo.winnow.R
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.attachmentSummary
import com.ericflo.winnow.data.db.ReminderDao
import com.ericflo.winnow.data.db.ReminderEntity
import com.ericflo.winnow.data.displayNameFor
import com.ericflo.winnow.data.joinAddresses
import com.ericflo.winnow.data.splitAddresses
import com.ericflo.winnow.data.subjectAndText
import com.ericflo.winnow.ui.MainActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * "Remind me" on a message: at the chosen time a notification brings it back, and tapping it
 * opens the conversation at that message. One reminder per message; setting another moves it.
 */
class Reminders(
    private val context: Context,
    private val dao: ReminderDao,
    /** A name for an address, as the app shows it. */
    private val displayName: (String) -> String,
    /** Settings → Hide texts on the lock screen. */
    private val hideOnLockScreen: suspend () -> Boolean = { false },
    /** Whether the message (key, sent or received at) is still on the phone, to open the conversation at it. */
    private val messageExists: suspend (key: String, at: Long) -> Boolean = { _, _ -> true },
) {
    /** A message's reminder as a conversation shows it: when it's due, and which message it's for. */
    data class Mark(val at: Long, val messageAt: Long) {
        /** For [message], not another that took its reused id since. */
        fun isFor(message: ChatMessage) = messageAt == 0L || messageAt == message.timestamp
    }

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.channel_reminders_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** When each of [threadId]'s messages is to come back, by message key. */
    fun observe(threadId: Long): Flow<Map<String, Mark>> =
        dao.observeForThread(threadId).map { rows -> rows.associate { it.messageKey to Mark(it.remindAt, it.messageAt) } }

    /** Every reminder, by message key (for backups). */
    suspend fun all(): Map<String, ReminderEntity> = dao.all().associateBy { it.messageKey }

    /**
     * Whether a reminder can show when it comes due: notifications allowed, and the Reminders
     * channel not turned off. ContextCompat, not Context: before Android 13 the permission doesn't
     * exist and the platform calls it denied.
     */
    fun canShow(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!manager.areNotificationsEnabled()) return false
        return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    suspend fun set(message: ChatMessage, recipients: List<String>, at: Long) {
        val words = subjectAndText(message.subject, message.body)
        val preview = words.ifBlank { attachmentSummary(message.attachments.map { it.contentType }) }
        dao.upsert(
            ReminderEntity(
                messageKey = message.key,
                threadId = message.threadId,
                recipients = joinAddresses(recipients),
                remindAt = at,
                preview = preview,
                sender = if (message.outgoing) null else message.sender ?: recipients.singleOrNull(),
                messageAt = message.timestamp,
                fromMe = message.outgoing,
            ),
        )
        arm(message.key, at)
        // A reminder already showing for it is done with: this one replaces it.
        manager.cancel(tag(message.key), 0)
    }

    suspend fun cancel(key: String) {
        alarms.cancel(alarmIntent(key))
        dao.delete(key)
        manager.cancel(tag(key), 0)
    }

    /**
     * Conversations deleted: their reminders go too, and any showing (whose "In an hour" would
     * otherwise bring one back). A backup of them keeps the reminders (see BackupManager).
     */
    suspend fun cancelForThreads(threadIds: Collection<Long>) {
        dao.all().filter { it.threadId in threadIds }.forEach { cancel(it.messageKey) }
        runCatching {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { it.tag?.startsWith(TAG_PREFIX) == true && it.notification.extras.getLong(EXTRA_THREAD_ID, -1) in threadIds }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }

    /** A reminder put back from a backup under its message's new key; only one still to come. */
    suspend fun restore(reminder: ReminderEntity) {
        if (reminder.remindAt <= System.currentTimeMillis()) return
        dao.upsert(reminder)
        arm(reminder.messageKey, reminder.remindAt)
    }

    /** Debug builds (DebugSeedReceiver): every reminder due at [at] instead, to see one fire. */
    suspend fun bringForward(at: Long) {
        dao.all().forEach { r ->
            dao.upsert(r.copy(remindAt = at))
            arm(r.messageKey, at)
        }
    }

    /** Alarms don't survive a reboot or a force-stop: every reminder armed again (overdue ones at once). */
    suspend fun rearmAll() {
        val now = System.currentTimeMillis()
        dao.all().forEach { arm(it.messageKey, maxOf(it.remindAt, now)) }
    }

    /** The alarm went off: the notification, and the reminder is done with. */
    suspend fun fire(key: String) {
        val reminder = dao.get(key) ?: return
        // Moved later since the alarm was set (a race with "Remind me" again): not yet.
        if (reminder.remindAt > System.currentTimeMillis() + EARLY_TOLERANCE_MILLIS) return arm(key, reminder.remindAt)
        dao.delete(key)
        // Its message gone (deleted, or its id now someone else's): the reminder still shows, from
        // what it saved, but opens the conversation rather than a message that isn't it.
        show(reminder, focus = messageExists(key, reminder.messageAt))
    }

    /** "In an hour" on the notification. */
    suspend fun snooze(reminder: ReminderEntity) {
        val at = System.currentTimeMillis() + SNOOZE_MILLIS
        dao.upsert(reminder.copy(remindAt = at))
        arm(reminder.messageKey, at)
        manager.cancel(tag(reminder.messageKey), 0)
    }

    fun dismiss(key: String) = manager.cancel(tag(key), 0)

    private suspend fun show(reminder: ReminderEntity, focus: Boolean) {
        // ContextCompat, not Context: before Android 13 the permission doesn't exist, and the platform calls it denied.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val recipients = splitAddresses(reminder.recipients)
        val conversation = displayNameFor(recipients, displayName)
        val who = when {
            reminder.fromMe -> "You"
            recipients.size > 1 -> reminder.sender?.let(displayName)
            else -> null
        }
        val line = who?.let { "$it: ${reminder.preview}" } ?: reminder.preview
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_THREAD)
            .putExtra(MainActivity.EXTRA_THREAD_ID, reminder.threadId)
            .putExtra(MainActivity.EXTRA_ADDRESS, reminder.recipients)
            .apply { if (focus) putExtra(MainActivity.EXTRA_FOCUS, reminder.messageKey) }
            // Its own data, so each reminder's tap opens its own message.
            .setData(Uri.parse("winnow://reminder/${Uri.encode(reminder.messageKey)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reminder: $conversation")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(R.drawable.ic_notification, "In an hour", receiverIntent(ACTION_SNOOZE, reminder))
            .addAction(R.drawable.ic_notification, "Done", receiverIntent(ACTION_DONE, reminder))
            .addExtras(android.os.Bundle().apply { putLong(EXTRA_THREAD_ID, reminder.threadId) })
            // What it says stays off the lock screen; with "Hide texts on the lock screen" on, all of
            // it does, as messages' notifications.
            .setVisibility(if (hideOnLockScreen()) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Message reminder").build(),
            )
            .build()
        manager.notify(tag(reminder.messageKey), 0, notification)
    }

    private fun arm(key: String, at: Long) {
        val intent = alarmIntent(key)
        // Exact with the "Alarms & reminders" grant; else inexact, but still while the phone is
        // idle (a plain window waits for Doze's maintenance windows, hours sometimes).
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun alarmIntent(key: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).setData(keyUri(key)).putExtra(EXTRA_KEY, key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun receiverIntent(action: String, reminder: ReminderEntity): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .setData(keyUri(reminder.messageKey))
            .putExtra(EXTRA_KEY, reminder.messageKey)
            .putExtra(EXTRA_THREAD_ID, reminder.threadId)
            .putExtra(EXTRA_RECIPIENTS, reminder.recipients)
            .putExtra(EXTRA_PREVIEW, reminder.preview)
            .putExtra(EXTRA_SENDER, reminder.sender)
            .putExtra(EXTRA_MESSAGE_AT, reminder.messageAt)
            .putExtra(EXTRA_FROM_ME, reminder.fromMe),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Each reminder's intents told apart by data, not request code (keys don't fit in an int). */
    private fun keyUri(key: String) = Uri.parse("winnow://reminder/${Uri.encode(key)}")

    private fun tag(key: String) = "$TAG_PREFIX$key"

    companion object {
        const val CHANNEL = "reminders"
        const val ACTION_FIRE = "com.ericflo.winnow.REMINDER"
        const val ACTION_SNOOZE = "com.ericflo.winnow.REMINDER_SNOOZE"
        const val ACTION_DONE = "com.ericflo.winnow.REMINDER_DONE"
        const val EXTRA_KEY = "message_key"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_RECIPIENTS = "recipients"
        const val EXTRA_PREVIEW = "preview"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_MESSAGE_AT = "message_at"
        const val EXTRA_FROM_ME = "from_me"
        private const val TAG_PREFIX = "reminder:"
        private const val SNOOZE_MILLIS = 60 * 60_000L
        /** An alarm a little early (inexact windows) still counts as on time. */
        private const val EARLY_TOLERANCE_MILLIS = 60_000L
    }
}

/** A reminder coming due, and its notification's buttons. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as WinnowApp).container
        val key = intent.getStringExtra(Reminders.EXTRA_KEY) ?: return
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    Reminders.ACTION_FIRE -> container.reminders.fire(key)
                    Reminders.ACTION_DONE -> container.reminders.dismiss(key)
                    Reminders.ACTION_SNOOZE -> container.reminders.snooze(
                        ReminderEntity(
                            messageKey = key,
                            threadId = intent.getLongExtra(Reminders.EXTRA_THREAD_ID, -1),
                            recipients = intent.getStringExtra(Reminders.EXTRA_RECIPIENTS).orEmpty(),
                            remindAt = 0,
                            preview = intent.getStringExtra(Reminders.EXTRA_PREVIEW).orEmpty(),
                            sender = intent.getStringExtra(Reminders.EXTRA_SENDER),
                            messageAt = intent.getLongExtra(Reminders.EXTRA_MESSAGE_AT, 0),
                            fromMe = intent.getBooleanExtra(Reminders.EXTRA_FROM_ME, false),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("WinnowReminders", "A reminder failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
