package com.ericflo.winnow.debug

import android.content.BroadcastReceiver
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.ericflo.winnow.WinnowApp
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Debug builds only. Fills the emulator's SMS store with synthetic history to measure the app
 * against a realistically large inbox. Numbers stay inside the reserved fictional range
 * +1 415-555-0100…0199, so at most 100 threads:
 *
 *     adb shell am broadcast -n com.ericflo.winnow/.debug.DebugSeedReceiver --ei messages 5000 --ei threads 100
 *
 * Or one unread incoming text that Winnow never classified, as if it arrived before Winnow was
 * the SMS app (quote the whole command, so a multi-word text stays one extra):
 *
 *     adb shell "am broadcast -n com.ericflo.winnow/.debug.DebugSeedReceiver --es from +12065550142 --es text 'hi there'"
 *
 * Or `--ez summary true` posts the daily summary now (whatever the setting or the last one), with
 * the last day's counts, without touching the real evening schedule.
 *
 * Or `--ei clean_filtered_ahead_days 40` runs Settings → Clear out old filtered texts as if that many
 * days had passed (it still needs the setting on).
 *
 * Or `--el remind_in 5000` makes every pending message reminder due that many milliseconds from now.
 *
 * Or `--ez onboarding true` shows onboarding again on the next launch (force-stop the app first),
 * to look it over; finishing or skipping it changes nothing but choosing a classifier there.
 *
 * Needs Winnow to be the default SMS app (only it may write the store).
 */
class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messages = intent.getIntExtra("messages", 5000)
        val threads = intent.getIntExtra("threads", 100).coerceIn(1, 100)
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        val from = intent.getStringExtra("from")
        container.appScope.launch {
            try {
                if (intent.getBooleanExtra("summary", false)) {
                    container.dailySummary.fire(force = true)
                    return@launch
                }
                if (intent.hasExtra("clean_filtered_ahead_days")) {
                    val days = intent.getIntExtra("clean_filtered_ahead_days", 40)
                    Log.i(TAG, "Filtered cleaner, as if $days days on: ${container.filteredCleaner.clean(System.currentTimeMillis() + days * 86_400_000L)} moved")
                    return@launch
                }
                if (intent.hasExtra("remind_in")) {
                    container.reminders.bringForward(System.currentTimeMillis() + intent.getLongExtra("remind_in", 5_000))
                    return@launch
                }
                if (intent.getBooleanExtra("onboarding", false)) {
                    container.settings.update { it.copy(onboarded = false) }
                    return@launch
                }
                if (from != null) {
                    val values = ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, from)
                        put(Telephony.Sms.BODY, intent.getStringExtra("text") ?: "Hello")
                        put(Telephony.Sms.DATE, System.currentTimeMillis())
                        put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                        put(Telephony.Sms.READ, 0)
                    }
                    Log.i(TAG, "Seeded one unclassified text: ${context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)}")
                    return@launch
                }
                val random = Random(42)
                val now = System.currentTimeMillis()
                val started = System.nanoTime()
                val rows = Array(messages) { i ->
                    val incoming = random.nextInt(3) != 0
                    ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, "+1415555%04d".format(100 + random.nextInt(threads)))
                        put(Telephony.Sms.BODY, LINES[random.nextInt(LINES.size)])
                        // Spread over two years, oldest first.
                        put(Telephony.Sms.DATE, now - (messages - i) * 2L * 365 * 24 * 3_600_000 / messages)
                        put(Telephony.Sms.TYPE, if (incoming) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT)
                        put(Telephony.Sms.READ, 1)
                        put(Telephony.Sms.SEEN, 1)
                    }
                }
                // The SMS provider ignores bulkInsert from apps; batched single inserts work.
                val inserted = rows.toList().chunked(500).sumOf { chunk ->
                    val ops = chunk.map { ContentProviderOperation.newInsert(Telephony.Sms.CONTENT_URI).withValues(it).build() }
                    context.contentResolver.applyBatch("sms", ArrayList(ops)).count { it.uri != null }
                }
                Log.i(TAG, "Seeded $inserted SMS across $threads threads in ${(System.nanoTime() - started) / 1_000_000} ms")
            } catch (e: Exception) {
                Log.e(TAG, "Seeding failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "WinnowDebugSeed"
        val LINES = listOf(
            "On my way", "Running a few minutes late", "Sounds good!", "Can you grab milk?", "Call me when you can",
            "Happy birthday!! 🎉", "What time works for you?", "lol", "See you there", "Thanks so much",
            "Did you see the game last night?", "Heading out now", "Dinner Friday?", "👍", "Love you",
        )
    }
}
