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
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.channel_reminders_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** When each of [threadId]'s messages is to come back, by message key. */
    fun observe(threadId: Long): Flow<Map<String, Long>> =
        dao.observeForThread(threadId).map { rows -> rows.associate { it.messageKey to it.remindAt } }

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

    /** Conversations deleted: their reminders go too. */
    suspend fun cancelForThreads(threadIds: Collection<Long>) {
        dao.all().filter { it.threadId in threadIds }.forEach { cancel(it.messageKey) }
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
        show(reminder)
    }

    /** "In an hour" on the notification. */
    suspend fun snooze(key: String, recipients: List<String>, threadId: Long, preview: String, sender: String?) {
        val at = System.currentTimeMillis() + SNOOZE_MILLIS
        dao.upsert(ReminderEntity(key, threadId, joinAddresses(recipients), at, preview, sender))
        arm(key, at)
        manager.cancel(tag(key), 0)
    }

    fun dismiss(key: String) = manager.cancel(tag(key), 0)

    private fun show(reminder: ReminderEntity) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val recipients = splitAddresses(reminder.recipients)
        val conversation = displayNameFor(recipients, displayName)
        val who = when {
            reminder.sender == null -> "You"
            recipients.size > 1 -> displayName(reminder.sender)
            else -> null
        }
        val line = who?.let { "$it: ${reminder.preview}" } ?: reminder.preview
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_THREAD)
            .putExtra(MainActivity.EXTRA_THREAD_ID, reminder.threadId)
            .putExtra(MainActivity.EXTRA_ADDRESS, reminder.recipients)
            .putExtra(MainActivity.EXTRA_FOCUS, reminder.messageKey)
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
            // What it says stays off the lock screen, as messages' do when the user asks.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Message reminder").build(),
            )
            .build()
        manager.notify(tag(reminder.messageKey), 0, notification)
    }

    private fun arm(key: String, at: Long) {
        val intent = alarmIntent(key)
        // As scheduled sends: exact with the "Alarms & reminders" grant, else within ten minutes.
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, intent)
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
            .putExtra(EXTRA_SENDER, reminder.sender),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Each reminder's intents told apart by data, not request code (keys don't fit in an int). */
    private fun keyUri(key: String) = Uri.parse("winnow://reminder/${Uri.encode(key)}")

    private fun tag(key: String) = "reminder:$key"

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
                        key,
                        splitAddresses(intent.getStringExtra(Reminders.EXTRA_RECIPIENTS).orEmpty()),
                        intent.getLongExtra(Reminders.EXTRA_THREAD_ID, -1),
                        intent.getStringExtra(Reminders.EXTRA_PREVIEW).orEmpty(),
                        intent.getStringExtra(Reminders.EXTRA_SENDER),
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
