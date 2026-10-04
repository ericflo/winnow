package com.ericflo.winnow.debug

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.db.VerdictEntity
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Debug builds only. Simulates a month of mixed incoming traffic: fictional senders texting
 * the kinds of messages Winnow sorts. Each one goes into the SMS store at its own time and
 * through the real classifier, so Activity and Filtered look like a phone in use:
 *
 *     adb shell am broadcast -n com.ericflo.winnow/.debug.DebugTrafficReceiver --ei messages 400 --ei days 30
 *
 * Nothing is sent anywhere. Needs Winnow to be the default SMS app.
 */
class DebugTrafficReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val count = intent.getIntExtra("messages", 400)
        val days = intent.getIntExtra("days", 30)
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                val random = Random(7)
                val now = System.currentTimeMillis()
                val settings = container.settings.current()
                val classifier = container.classifiers.create(settings)
                var stored = 0
                repeat(count) { i ->
                    val (sender, body) = TRAFFIC[random.nextInt(TRAFFIC.size)].let { (kind, text) -> senderFor(kind, random) to text }
                    // Busier in the evening, with a gentle rise toward today.
                    val dayAgo = (days * (1 - Math.sqrt(random.nextDouble()))).toLong()
                    val date = now - dayAgo * 86_400_000L - random.nextLong(0, 12 * 3_600_000L) - 60_000L * i % 3_600_000L
                    val uri = context.contentResolver.insert(
                        Telephony.Sms.Inbox.CONTENT_URI,
                        ContentValues().apply {
                            put(Telephony.Sms.ADDRESS, sender)
                            put(Telephony.Sms.BODY, body)
                            put(Telephony.Sms.DATE, date)
                            put(Telephony.Sms.READ, 1)
                            put(Telephony.Sms.SEEN, 1)
                        },
                    ) ?: return@repeat
                    val id = ContentUris.parseId(uri)
                    val threadId = context.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return@repeat
                    val verdict = classifier.classify(InboundMessage(sender, body))
                    container.verdictDao.upsert(VerdictEntity.from(ChatMessage.messageKey(ChatMessage.Kind.SMS, id), threadId, sender, verdict, date))
                    stored++
                }
                Log.i(TAG, "Simulated $stored texts over $days days")
            } catch (e: Exception) {
                Log.e(TAG, "Simulation failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun senderFor(kind: Char, random: Random): String = when (kind) {
        'p' -> "+1415555%04d".format(100 + random.nextInt(100))
        's' -> (20000 + random.nextInt(70000)).toString()
        't' -> "+1888555%04d".format(100 + random.nextInt(100))
        else -> "+1%03d555%04d".format(listOf(213, 305, 702, 786, 818, 469)[random.nextInt(6)], 100 + random.nextInt(100))
    }

    private companion object {
        const val TAG = "WinnowDebugTraffic"

        /** p: a friend's number, s: short code, t: toll-free, u: an unknown number. */
        val TRAFFIC = listOf(
            'p' to "Running 10 min late, sorry!", 'p' to "Dinner Friday? I'm thinking tacos", 'p' to "Can you grab milk on the way home?",
            'p' to "Happy birthday!! 🎉", 'p' to "lol yes", 'p' to "Are you around this weekend?", 'p' to "Thanks so much for yesterday",
            'p' to "I'm outside", 'p' to "Did you see the game last night?", 'p' to "call me when you can",
            's' to "Your Amazon package was delivered to the front porch.", 's' to "Chase: A $64.20 purchase at WHOLE FOODS was made on your card ending 4412.",
            's' to "Reminder: dentist appointment tomorrow at 9:30 AM. Reply C to confirm.", 's' to "Your Uber is arriving now.",
            's' to "USPS: Your package will arrive by 9pm today.", 's' to "Your prescription is ready for pickup at CVS.",
            's' to "Target: 25% off fall decor this weekend. Reply STOP to opt out", 's' to "Starbucks: Double Star Day is Thursday!",
            's' to "Old Navy: 50% off everything today only! Reply STOP to opt out", 't' to "Sunrise Toyota: 0% APR on select models this month. Reply STOP to opt out",
            'u' to "USPS: Your package is on hold. Confirm your address: usps-parcel.top/c", 'u' to "E-ZPass: unpaid toll of $4.35. Pay now to avoid a fee: ezpass.com-pay.vip",
            'u' to "Chase: your account is locked. Verify at chase-secure-verify.com", 'u' to "Netflix: payment failed. Update billing: netflix-billing-update.com",
            'u' to "Hi, is this David? This is Amy from yoga", 'u' to "We have a remote job: $400/day, 1 hour. Reply Y", 'u' to "Hey, are you free to talk?",
            'u' to "BREAKING: We need 500 more signatures by midnight. Donate now: act.ly/x Stop2End",
            'u' to "Hi Karen, it's Josh with the Nevada Democrats. Early voting starts Saturday, can we count on you? Reply STOP to opt out",
            'u' to "OFFICIAL POLL: Do you approve of Congress? Reply YES or NO. STOP to end", 'u' to "Your gift will be 5X MATCHED until midnight: winred.com/x STOP=quit",
            't' to "We buy houses for cash! Any condition. Reply YES. STOP to opt out", 't' to "Lower your car insurance today. Reply QUOTE. STOP to end",
            't' to "Medicare members: extra benefits available. Call 555-0170. Reply STOP to unsubscribe",
        )
    }
}
