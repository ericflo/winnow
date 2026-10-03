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
 * Needs Winnow to be the default SMS app (only it may write the store).
 */
class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messages = intent.getIntExtra("messages", 5000)
        val threads = intent.getIntExtra("threads", 100).coerceIn(1, 100)
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
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
