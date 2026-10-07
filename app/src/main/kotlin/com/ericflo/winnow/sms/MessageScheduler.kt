package com.ericflo.winnow.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.db.ScheduledMessageDao
import com.ericflo.winnow.data.db.ScheduledMessageEntity
import com.ericflo.winnow.data.joinAddresses
import com.ericflo.winnow.data.splitAddresses
import com.ericflo.winnow.data.displayNameFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.cancellation.CancellationException

private const val TOLD = "told"

/** Holds texts until their send time and sends them from an alarm. */
class MessageScheduler(
    private val context: Context,
    private val dao: ScheduledMessageDao,
    /** The store to send through, or null when Winnow can't send right now (not the SMS app). */
    private val messages: () -> MessageRepository?,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun observe(threadId: Long): Flow<List<ScheduledMessageEntity>> = dao.observeForThread(threadId)

    /** Every scheduled text, soonest first. */
    fun observeAll(): Flow<List<ScheduledMessageEntity>> = dao.observeAll()

    suspend fun schedule(threadId: Long, recipients: List<String>, body: String, sendAt: Long, subscriptionId: Int? = null) {
        val id = dao.insert(
            ScheduledMessageEntity(threadId = threadId, recipients = joinAddresses(recipients), body = body, sendAt = sendAt, subscriptionId = subscriptionId),
        )
        arm(id, sendAt)
    }

    /**
     * Moves a scheduled text to [sendAt]; its alarm moves with it, and a failure it had is
     * forgotten, so a new one is reported. False if it's gone (sent or deleted meanwhile).
     */
    suspend fun reschedule(id: Long, sendAt: Long): Boolean {
        val message = dao.get(id) ?: return false
        dao.setSendAt(id, sendAt)
        arm(id, sendAt)
        forgetFailure(id)
        (context.applicationContext as WinnowApp).container.notifier.cancelNotSent(message.threadId)
        return true
    }

    suspend fun cancel(id: Long) {
        alarms.cancel(alarmIntent(id))
        alarms.cancel(alarmIntent(id, idle = true))
        dao.delete(id)
        forgetFailure(id)
    }

    /**
     * Sends a scheduled message now, whether its time has come or the user asked. It's only
     * removed once the send has been handed off; on failure it stays, to go out on the next try.
     */
    suspend fun sendNow(id: Long) = send(id, early = true)

    /** An alarm's: sends [id] if its time has come; one moved later since the alarm was set waits for its new time. */
    suspend fun sendIfDue(id: Long) = send(id, early = false)

    private suspend fun send(id: Long, early: Boolean) = sending.withLock {
        // One at a time, and looked up again inside: its two alarms (see arm), or an alarm and
        // Send now, can't both find it and send it twice.
        val message = dao.get(id) ?: return@withLock
        // An alarm from before it was rescheduled later: not yet (set again, in case it was the only one).
        if (!early && message.sendAt > System.currentTimeMillis() + EARLY_SLACK_MILLIS) {
            arm(id, message.sendAt)
            return@withLock
        }
        val store = messages() ?: throw IllegalStateException("Winnow isn't the default SMS app, so it can't send")
        val recipients = splitAddresses(message.recipients)
        try {
            store.send(recipients, message.body, subscriptionId = message.subscriptionId)
        } catch (e: StoredAsFailed) {
            // It's in its conversation now, marked not sent: kept scheduled too, it would go out twice.
            cancel(id)
            val container = (context.applicationContext as WinnowApp).container
            if (container.visibleThread.value != message.threadId) {
                container.notifier.showNotSent(
                    message.threadId, recipients, displayNameFor(recipients, container.messages::displayName), message.body, scheduled = true,
                    retryKey = e.key,
                )
            }
            throw e
        }
        cancel(id)
    }

    private val sending = Mutex()

    private companion object {
        /** An alarm this much early still sends: alarms are allowed to be a little early. */
        const val EARLY_SLACK_MILLIS = 60_000L
    }

    /**
     * Tells the user a scheduled text didn't go out when its time came: once, though an overdue
     * one is tried again every time the app starts, and not while its conversation is on screen.
     */
    suspend fun notifyFailed(id: Long) {
        val message = dao.get(id) ?: return
        val container = (context.applicationContext as WinnowApp).container
        if (container.visibleThread.value == message.threadId) return
        val told = failures.getStringSet(TOLD, emptySet()).orEmpty()
        if (id.toString() in told) return
        failures.edit().putStringSet(TOLD, told + id.toString()).apply()
        val recipients = splitAddresses(message.recipients)
        container.notifier.showNotSent(
            message.threadId, recipients, displayNameFor(recipients, container.messages::displayName), message.body, scheduled = true,
            retryKey = "scheduled:$id",
        )
    }

    /** Scheduled texts the user has been told failed; forgotten once they're sent or deleted. */
    private val failures by lazy { context.getSharedPreferences("scheduled_failures", Context.MODE_PRIVATE) }

    fun forgetFailure(id: Long) {
        val told = failures.getStringSet(TOLD, emptySet()).orEmpty()
        if (id.toString() in told) failures.edit().putStringSet(TOLD, told - id.toString()).apply()
    }

    /**
     * Sends every scheduled text whose time has passed, now. An alarm can be held back for good:
     * Android doesn't fire a background-restricted app's alarms (Samsung's "Restricted" battery
     * setting, or an app it's put to sleep), not even once it's opened. So whenever Winnow comes
     * to the front, anything overdue goes out then. One that fails is reported as any would be.
     */
    suspend fun sendDue(now: Long = System.currentTimeMillis()) {
        dao.all().filter { it.sendAt <= now }.forEach { m ->
            runCatching { sendIfDue(m.id) }.onFailure { runCatching { notifyFailed(m.id) } }
        }
    }

    /** Alarms don't survive a reboot; this re-arms every pending message (overdue ones fire at once). */
    suspend fun rearmAll() {
        val now = System.currentTimeMillis()
        dao.all().forEach { arm(it.id, maxOf(it.sendAt, now)) }
    }

    private fun arm(id: Long, at: Long) {
        val intent = alarmIntent(id)
        // Exact timing needs the user's "Alarms & reminders" grant. Without it, two: a ten-minute
        // window (an inexact idle alarm alone can be an hour late while the phone's in use), and
        // one that still goes off while it's idle (a window waits for Doze's maintenance windows).
        // Whichever comes first sends it; the other finds it gone (see sendNow).
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            // One set before the grant (or a reschedule) would still go off at the old time.
            alarms.cancel(alarmIntent(id, idle = true))
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, intent)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(id, idle = true))
        }
    }

    private fun alarmIntent(id: Long, idle: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, ScheduledSendReceiver::class.java).putExtra(ScheduledSendReceiver.EXTRA_ID, id)
            // The idle alarm's own intent (an action, since request codes are the message's id).
            .apply { if (idle) action = ScheduledSendReceiver.ACTION_IDLE },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Fires when a scheduled message is due, and after boot to re-arm the rest. */
class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    // Reboots and exact-alarm permission changes both drop or reshape pending alarms.
                    Intent.ACTION_BOOT_COMPLETED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                        if (intent.action == Intent.ACTION_BOOT_COMPLETED) runCatching { tellStranded(context) }
                        runCatching { container.scheduler.rearmAll() }
                        runCatching { container.reminders.rearmAll() }
                        // The evening summary's alarm went with the reboot too.
                        runCatching { container.dailySummary.rearm() }
                    }
                    else -> container.scheduler.sendIfDue(intent.getLongExtra(EXTRA_ID, -1))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WinnowSchedule", "Scheduled send failed", e)
                // It's still scheduled, with Send now in its conversation; the user has to know.
                runCatching { container.scheduler.notifyFailed(intent.getLongExtra(EXTRA_ID, -1)) }
            } finally {
                pending.finish()
            }
        }
    }

    /** Texts the reboot cut off mid-send: marked not sent, each with a notice to try again. */
    private suspend fun tellStranded(context: Context) {
        val container = (context.applicationContext as WinnowApp).container
        if (!container.isDefaultSmsApp()) return
        val keys = withContext(Dispatchers.IO) { failStranded(context) }
        if (keys.isEmpty()) return
        Log.i("WinnowSms", "${keys.size} texts were still sending at the reboot; marked not sent")
        container.messages.messagesByKey(keys).forEach { found ->
            container.notifier.showNotSent(found.message.threadId, found.recipients, found.conversationName, found.message.body.ifBlank { "a picture message" }, retryKey = found.message.key)
        }
    }

    companion object {
        const val EXTRA_ID = "scheduled_id"
        const val ACTION_IDLE = "com.ericflo.winnow.SCHEDULED_IDLE"
    }
}
