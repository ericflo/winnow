package com.ericflo.winnow.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.db.VerdictDao
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Settings → "Daily summary": once an evening, a quiet notification saying how many texts Winnow
 * kept out of the inbox (and delivered silently) in the last day, opening Filtered. Nothing on a
 * day with nothing to report.
 */
class DailySummary(
    private val context: Context,
    private val verdicts: VerdictDao,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Arms (or disarms) the next evening's summary, as the setting says. */
    suspend fun rearm() {
        val intent = alarmIntent()
        alarms.cancel(intent)
        if (!settings.current().dailySummary) return
        // Not exact: a summary can wait a while, and needs no special permission.
        alarms.setWindow(AlarmManager.RTC_WAKEUP, nextEvening(), WINDOW_MILLIS, intent)
    }

    /** The alarm went off: report on the last day, then arm tomorrow's. */
    suspend fun fire() {
        try {
            if (!settings.current().dailySummary) return
            val (filtered, silenced) = counts(System.currentTimeMillis() - DAY_MILLIS)
            if (filtered + silenced > 0) notifier.showSummary(filtered, silenced)
        } finally {
            rearm()
        }
    }

    /**
     * Texts that arrived since [since] and were filtered, and those silenced, by the verdict on
     * each (corrections included). By arrival, not by when Winnow decided: a review of older
     * conversations isn't "today".
     */
    private suspend fun counts(since: Long): Pair<Int, Int> {
        val resolver = context.contentResolver
        val keys = buildList {
            resolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX} AND ${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), null,
            )?.use { c -> while (c.moveToNext()) add(ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0))) }
            resolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
                "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX} AND ${Telephony.Mms.DATE} >= ?", arrayOf((since / 1000).toString()), null,
            )?.use { c -> while (c.moveToNext()) add(ChatMessage.messageKey(ChatMessage.Kind.MMS, c.getLong(0))) }
        }
        var filtered = 0
        var silenced = 0
        keys.chunked(500).forEach { chunk ->
            verdicts.effectiveActions(chunk).forEach { action ->
                when (action) {
                    "FILTER" -> filtered++
                    "SILENCE" -> silenced++
                }
            }
        }
        return filtered to silenced
    }

    private fun nextEvening(): Long {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone).atTime(EVENING).atZone(zone)
        val at = if (today.toInstant().toEpochMilli() > System.currentTimeMillis()) today else today.plusDays(1)
        return at.toInstant().toEpochMilli()
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, DailySummaryReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        val EVENING: LocalTime = LocalTime.of(20, 0)
        const val WINDOW_MILLIS = 30 * 60_000L
        const val DAY_MILLIS = 24 * 60 * 60_000L
    }
}

/** The evening alarm for [DailySummary]. (After a reboot it's re-armed with scheduled sends.) */
class DailySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.dailySummary.fire()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("WinnowSummary", "The daily summary failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
