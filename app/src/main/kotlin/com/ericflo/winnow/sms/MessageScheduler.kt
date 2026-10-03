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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Holds texts until their send time and sends them from an alarm. */
class MessageScheduler(
    private val context: Context,
    private val dao: ScheduledMessageDao,
    private val messages: () -> MessageRepository,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun observe(threadId: Long): Flow<List<ScheduledMessageEntity>> = dao.observeForThread(threadId)

    suspend fun schedule(threadId: Long, recipients: List<String>, body: String, sendAt: Long) {
        val id = dao.insert(ScheduledMessageEntity(threadId = threadId, recipients = joinAddresses(recipients), body = body, sendAt = sendAt))
        arm(id, sendAt)
    }

    suspend fun cancel(id: Long) {
        alarms.cancel(alarmIntent(id))
        dao.delete(id)
    }

    /** Sends a scheduled message now, whether its time has come or the user asked. */
    suspend fun sendNow(id: Long) {
        val message = dao.get(id) ?: return
        cancel(id)
        messages().send(splitAddresses(message.recipients), message.body)
    }

    /** Alarms don't survive a reboot; this re-arms every pending message (overdue ones fire at once). */
    suspend fun rearmAll() {
        val now = System.currentTimeMillis()
        dao.all().forEach { arm(it.id, maxOf(it.sendAt, now)) }
    }

    private fun arm(id: Long, at: Long) {
        val intent = alarmIntent(id)
        // Exact timing needs the user's "Alarms & reminders" grant; otherwise send within ten minutes.
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, intent)
        }
    }

    private fun alarmIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, ScheduledSendReceiver::class.java).putExtra(ScheduledSendReceiver.EXTRA_ID, id),
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
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) container.scheduler.rearmAll()
                else container.scheduler.sendNow(intent.getLongExtra(EXTRA_ID, -1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WinnowSchedule", "Scheduled send failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "scheduled_id"
    }
}
