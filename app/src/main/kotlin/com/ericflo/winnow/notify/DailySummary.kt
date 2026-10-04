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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Filtering is Winnow's only while it's the SMS app; another app's day isn't Winnow's to report. */
    private val isDefaultSmsApp: () -> Boolean = { true },
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** When this process last fired one: trusted even if saving it failed, so a failed save can't mean "due now" again. */
    @Volatile private var firedAt = 0L

    /**
     * When the last one went out: saved or remembered, whichever is later. A little in the future
     * (the clock was set back a day or so) counts as just now; far in the future (a clock that
     * was years ahead) as never, so it can't silence the summary for that long.
     */
    private fun lastAt(saved: Long, now: Long): Long {
        val last = maxOf(saved, firedAt)
        return when {
            last <= now -> last
            last - now <= FUTURE_TOLERANCE_MILLIS -> now
            else -> 0L
        }
    }

    /**
     * Arms (or disarms) the next summary, as the setting says: this evening's, or straight away if
     * it's past 8 PM and today's hasn't gone out (the app starting late mustn't skip it), else
     * tomorrow's.
     */
    suspend fun rearm() {
        val intent = alarmIntent()
        val current = settings.current()
        if (!current.dailySummary) {
            alarms.cancel(intent)
            return
        }
        val now = System.currentTimeMillis()
        // Saved a little in the future (the clock was set back by less than the gap between
        // summaries): that's "just now" from here on, or once the clock passes it the next one
        // would look too soon and skip a day. Set back further, the gap alone keeps it to one an
        // evening; which texts count never depends on the clock (see VerdictEntity.summarized).
        if (current.dailySummaryLastAt > now && current.dailySummaryLastAt - now < MIN_GAP_MILLIS) {
            runCatching { settings.update { it.copy(dailySummaryLastAt = now) } }
            firedAt = minOf(firedAt, now)
        }
        val tonight = evening(LocalDate.now(ZoneId.systemDefault()))
        // Not due if one went out lately (before a time-zone change, say): then tomorrow, or this
        // would fire, skip and re-arm for "now" again and again.
        val last = lastAt(current.dailySummaryLastAt, now)
        val due = now - last >= MIN_GAP_MILLIS
        val at = when {
            now < tonight -> tonight
            due && last < tonight -> now
            else -> evening(LocalDate.now(ZoneId.systemDefault()).plusDays(1))
        }
        // Not exact: a summary can wait a while, and needs no special permission.
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MILLIS, intent)
    }

    /** The alarm went off: report on what came since the last one (a day at most), then arm the next. */
    suspend fun fire(force: Boolean = false) {
        val current = settings.current()
        val now = System.currentTimeMillis()
        val last = lastAt(current.dailySummaryLastAt, now)
        // Off, or twice in one evening (the clock or time zone moved): once is enough.
        if (!force && (!current.dailySummary || now - last < MIN_GAP_MILLIS)) {
            rearm()
            return
        }
        // A debug run (force) reports the last day and leaves the real schedule alone.
        if (force) {
            val found = unsummarized(now - DAY_MILLIS)
            if (found.filtered + found.silenced > 0) notifier.showSummary(found.filtered, found.silenced)
            return
        }
        firedAt = now
        try {
            if (isDefaultSmsApp()) {
                // Texts not yet in a summary, from the last two days (one, the first time): a
                // skipped evening's still count, and nothing counts twice whatever the clock does.
                val found = unsummarized(now - if (last == 0L) DAY_MILLIS else 2 * DAY_MILLIS)
                // Flagged first (only what was counted: a text decided a moment later is the next
                // one's), so a failure after can't make tomorrow's repeat tonight's.
                found.keys.chunked(500).forEach { verdicts.markSummarized(it) }
                if (found.filtered + found.silenced > 0) notifier.showSummary(found.filtered, found.silenced)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("WinnowSummary", "Couldn't count the day's texts", e)
        } finally {
            // Done for today whatever happened, so the re-arm below looks to tomorrow, not "now" again.
            withContext(NonCancellable) {
                runCatching { settings.update { it.copy(dailySummaryLastAt = now) } }
                rearm()
            }
        }
    }

    private class Found(val filtered: Int, val silenced: Int, val keys: List<String>)

    /**
     * Incoming texts that arrived since [arrivedSince] and aren't in a summary yet, filtered and
     * silenced by the action that stood (corrections included). Texts whose verdicts were
     * restored, came from a review of older conversations or are the user's own corrections are
     * never news (see VerdictEntity.summarized).
     */
    private suspend fun unsummarized(arrivedSince: Long): Found {
        val resolver = context.contentResolver
        val keys = buildList {
            resolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX} AND ${Telephony.Sms.DATE} >= ?", arrayOf(arrivedSince.toString()), null,
            )?.use { c -> while (c.moveToNext()) add(ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0))) }
            resolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
                "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX} AND ${Telephony.Mms.DATE} >= ?", arrayOf((arrivedSince / 1000).toString()), null,
            )?.use { c -> while (c.moveToNext()) add(ChatMessage.messageKey(ChatMessage.Kind.MMS, c.getLong(0))) }
        }
        val rows = keys.chunked(500).flatMap { verdicts.unsummarized(it) }
        return Found(
            filtered = rows.count { it.stood == "FILTER" },
            silenced = rows.count { it.stood == "SILENCE" },
            keys = rows.map { it.messageKey },
        )
    }

    private fun evening(day: LocalDate): Long = day.atTime(EVENING).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, DailySummaryReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        val EVENING: LocalTime = LocalTime.of(20, 0)
        const val WINDOW_MILLIS = 30 * 60_000L
        const val DAY_MILLIS = 24 * 60 * 60_000L
        // Long enough that one evening never gets two; short enough that a late one (Doze) doesn't cost tomorrow's.
        const val MIN_GAP_MILLIS = 12 * 60 * 60_000L
        const val FUTURE_TOLERANCE_MILLIS = 2 * 24 * 60 * 60_000L
    }
}

/**
 * The evening alarm for [DailySummary], and the clock or time zone changing, which moves 8 PM.
 * (After a reboot it's re-armed with scheduled sends.)
 */
class DailySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> container.dailySummary.rearm()
                    else -> container.dailySummary.fire()
                }
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
