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
 * Or `--ei mms 3000` writes that many text-only MMS into one group conversation (`--es group
 * +12065550121,+12065550122,+12065550123` by default, fictional numbers), as a long-running
 * group chat leaves them: two in three from a member, the rest the user's. Written straight to
 * the store, as a restore does, so nothing is classified or announced.
 *
 * Or `--ez onboarding true` shows onboarding again on the next launch (force-stop the app first),
 * to look it over; finishing or skipping it changes nothing but choosing a classifier there.
 *
 * Needs Winnow to be the default SMS app (only it may write the store).
 */
class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messages = intent.getIntExtra("messages", 5000)
        // `--es areas 206,312,646` spreads the threads over those area codes' fictional 555-01xx
        // numbers, 100 to an area, for an inbox of more than 100 conversations.
        val areas = intent.getStringExtra("areas")?.split(',')?.map { it.trim() }?.filter { it.length == 3 && it.all(Char::isDigit) }.orEmpty().ifEmpty { listOf("415") }
        val threads = intent.getIntExtra("threads", 100).coerceIn(1, 100 * areas.size)
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
                    Log.i(TAG, "Filtered cleaner, as if $days days on: ${container.filteredCleaner.clean(System.currentTimeMillis() + days * 86_400_000L, force = true)} moved")
                    return@launch
                }
                if (intent.hasExtra("remind_in")) {
                    container.reminders.bringForward(System.currentTimeMillis() + intent.getLongExtra("remind_in", 5_000))
                    return@launch
                }
                if (intent.getBooleanExtra("checkformat", false)) {
                    // Winnow's own formatting of North American numbers against Android's, number by number.
                    val r = Random(5)
                    var mismatches = 0
                    repeat(5000) {
                        val n = (2 + r.nextInt(8)).toString() + (0 until 2).joinToString("") { r.nextInt(10).toString() } +
                            (2 + r.nextInt(8)).toString() + (0 until 6).joinToString("") { r.nextInt(10).toString() }
                        val ours = com.ericflo.winnow.data.ContactLookup.nanpFormat(n)
                        val android = android.telephony.PhoneNumberUtils.formatNumber(n, "US")
                        if (ours != android) { mismatches++; if (mismatches < 10) Log.w(TAG, "format $n: ours $ours, Android's $android") }
                    }
                    Log.i(TAG, "Format check: $mismatches of 5000 differ")
                    return@launch
                }
                if (intent.hasExtra("groups")) {
                    // --ei groups N --ei per M: N group conversations of 3–6 people with M picture messages
                    // each, like a phone with many family and friend groups. Every fifth group's members
                    // write from RCS addresses ("<id>@rcs.google.com"), as Google Messages leaves them.
                    val groups = intent.getIntExtra("groups", 300)
                    val per = intent.getIntExtra("per", 7)
                    val store = com.ericflo.winnow.sms.MmsStore(context)
                    val random = Random(11)
                    val now = System.currentTimeMillis() / 1000
                    val started = System.nanoTime()
                    var written = 0
                    repeat(groups) { g ->
                        val rcs = g % 5 == 0
                        val size = 3 + random.nextInt(4)
                        val members = List(size) { m ->
                            if (rcs) "%016x@rcs.google.com".format(random.nextLong()) else "+1%s555%04d".format(listOf("206", "425", "253", "360")[g % 4], 100 + (g * 7 + m) % 100)
                        }.distinct()
                        val threadId = Telephony.Threads.getOrCreateThreadId(context, members.toSet())
                        repeat(per) { i ->
                            val from = members[random.nextInt(members.size)].takeIf { random.nextInt(4) != 0 }
                            val box = if (from != null) Telephony.Mms.MESSAGE_BOX_INBOX else Telephony.Mms.MESSAGE_BOX_SENT
                            val parts = listOf(com.ericflo.winnow.mms.MmsPart.plainText(LINES[random.nextInt(LINES.size)]))
                            val at = now - random.nextInt(400 * 86_400) - (per - i) * 60L
                            if (store.insertRestored(threadId, box, at, read = true, subject = null, from = from, to = members - from.orEmpty(), parts = parts) != null) written++
                        }
                    }
                    Log.i(TAG, "Seeded $written MMS across $groups group threads in ${(System.nanoTime() - started) / 1_000_000} ms")
                    return@launch
                }
                if (intent.hasExtra("mms")) {
                    val count = intent.getIntExtra("mms", 3000)
                    val members = (intent.getStringExtra("group") ?: "+12065550121,+12065550122,+12065550123").split(',').map { it.trim() }
                    val threadId = Telephony.Threads.getOrCreateThreadId(context, members.toSet())
                    val store = com.ericflo.winnow.sms.MmsStore(context)
                    val random = Random(7)
                    val now = System.currentTimeMillis() / 1000
                    val started = System.nanoTime()
                    var written = 0
                    repeat(count) { i ->
                        val from = members[random.nextInt(members.size)].takeIf { random.nextInt(3) != 0 }
                        val box = if (from != null) Telephony.Mms.MESSAGE_BOX_INBOX else Telephony.Mms.MESSAGE_BOX_SENT
                        val parts = listOf(com.ericflo.winnow.mms.MmsPart.plainText(LINES[random.nextInt(LINES.size)]))
                        if (store.insertRestored(threadId, box, now - (count - i) * 600L, read = true, subject = null, from = from, to = members - from.orEmpty(), parts = parts) != null) written++
                    }
                    Log.i(TAG, "Seeded $written MMS in group thread $threadId in ${(System.nanoTime() - started) / 1_000_000} ms")
                    return@launch
                }
                if (intent.getBooleanExtra("onboarding", false)) {
                    container.settings.update { it.copy(onboarded = false) }
                    return@launch
                }
                if (from != null) {
                    // --el ago <ms> backdates it (read, as an old one would be); --ez outgoing true makes it the user's.
                    val ago = intent.getLongExtra("ago", 0)
                    val outgoing = intent.getBooleanExtra("outgoing", false)
                    val values = ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, from)
                        put(Telephony.Sms.BODY, intent.getStringExtra("text") ?: "Hello")
                        put(Telephony.Sms.DATE, System.currentTimeMillis() - ago)
                        put(Telephony.Sms.TYPE, if (outgoing) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_INBOX)
                        put(Telephony.Sms.READ, if (ago > 0 || outgoing) 1 else 0)
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
                        val n = random.nextInt(threads)
                        put(Telephony.Sms.ADDRESS, "+1%s555%04d".format(areas[n / 100], 100 + n % 100))
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
