package com.ericflo.winnow.classifier

import com.ericflo.winnow.classifier.http.OkHttpTransport
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classifier.message.MessageClassifier
import com.ericflo.winnow.classifier.message.Verdict
import com.ericflo.winnow.classifier.message.VerdictSource
import com.ericflo.winnow.classifier.providers.SystemOneConfig
import com.ericflo.winnow.classifier.providers.SystemOneProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * Real network calls, opt-in: `WINNOW_LIVE_TESTS=1 ./gradlew :classifier:test --tests '*Live*'`.
 * Uses OPENROUTER_API_KEY and/or TYPESAFE_API_KEY, whichever are set. Costs fractions of a cent.
 */
class LiveProviderTest {
    private val samples = listOf(
        InboundMessage("+13185550182", "E-ZPass: Your toll balance of \$4.35 is unpaid. Avoid a \$50 late fee, pay today: ezpass-tolls.top/pay"),
        InboundMessage("+16595550147", "Hi, is this Jessica? We met at the wine tasting last weekend 😊"),
        InboundMessage("827438", "Harbor & Pine: 30% off fall decor this weekend only! Shop now: hpine.co/fall Reply STOP to opt out"),
        InboundMessage("+12025550199", "Election Day is 31 days away and we're \$12K short of our goal. Chip in \$5 before midnight?"),
        InboundMessage("+14155550177", "Reminder: you have an appointment Tue Oct 7 at 2:30 PM with Dr. Patel. Reply C to confirm or R to reschedule."),
        InboundMessage("+14155550110", "Hey it's Jordan from the climbing gym, still down for Thursday?"),
        // Patterns from a real spam folder, with names and numbers made up.
        InboundMessage("+17715550142", "BREAKING: The House just passed a CATASTROPHIC bill. Add your name before midnight >>"),
        InboundMessage("+17715550143", "Hi, it's Mark! Can you complete your Approval Poll? Due to low response we need yours by 11:59pm"),
        InboundMessage("+13305550144", "DANA, I have some tough news, and I wanted you to hear it from me first."),
        InboundMessage("+17375550145", "Are you free to talk?"),
        InboundMessage("+12025550146", "Tax-free Social Security payments start next month. Confirm your eligibility now"),
        InboundMessage("71523", "Micro Mart: Hurry - Member Days end tonight! Up to 40% off. Reply STOP to opt out"),
    )

    @Test
    fun `Jev via OpenRouter classifies the demo messages`() = live("OPENROUTER_API_KEY") { key ->
        SystemOneProvider(SystemOneConfig.openRouter(key), OkHttpTransport())
    }

    @Test
    fun `Jev via TypeSafe classifies the demo messages`() = live("TYPESAFE_API_KEY") { key ->
        SystemOneProvider(SystemOneConfig.typeSafe(key), OkHttpTransport())
    }

    private fun live(keyVar: String, provider: (String) -> DecisionProvider) {
        assumeTrue("set WINNOW_LIVE_TESTS=1 to run", System.getenv("WINNOW_LIVE_TESTS") == "1")
        val key = System.getenv(keyVar)
        assumeTrue("$keyVar is not set", !key.isNullOrBlank())
        val classifier = MessageClassifier(listOf(provider(key!!)), timeoutMillis = 30_000)
        val verdicts = runBlocking { samples.map { it to classifier.classify(it) } }
        verdicts.forEach { (m, v) -> println(describe(m, v)) }
        verdicts.forEach { (_, v) -> assertIs<VerdictSource.Provider>(v.source, "provider should answer, got ${v.source}") }
        assertNotEquals(Action.ALLOW, verdicts.first().second.action, "the toll scam must not reach the inbox")
    }

    private fun describe(m: InboundMessage, v: Verdict): String {
        val top = v.distribution.entries.sortedByDescending { it.value }.take(2)
            .joinToString(", ") { "${it.key.key} ${"%.2f".format(it.value)}" }
        return "%-11s %-8s [%s] %s  ←  %s".format(v.category?.key, v.action, top, v.source, m.body.take(50))
    }
}
