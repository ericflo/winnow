package com.ericflo.winnow

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classify.LabDiagnosis
import com.ericflo.winnow.classify.LabDiagnosis.LabeledText
import com.ericflo.winnow.classify.LabDiagnosis.Scored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabDiagnosisTest {
    private fun s(n: Int, label: Category, predicted: Category, confidence: Double = 0.6) = Scored("sms:$n", label, predicted, confidence)

    /** 20 marketing (12 called transactional), 30 personal (2 called spam), 10 spam all right. */
    private val items = buildList {
        repeat(8) { add(s(it, Category.MARKETING, Category.MARKETING)) }
        repeat(12) { add(s(100 + it, Category.MARKETING, Category.TRANSACTIONAL, if (it < 2) 0.95 else 0.6)) }
        repeat(28) { add(s(200 + it, Category.PERSONAL, Category.PERSONAL)) }
        repeat(2) { add(s(300 + it, Category.PERSONAL, Category.SPAM, 0.97)) }
        repeat(10) { add(s(400 + it, Category.SPAM, Category.SPAM)) }
    }

    @Test
    fun `mistakes are grouped by where they went, biggest first`() {
        val c = LabDiagnosis.confusions(items)
        assertEquals(Category.MARKETING, c[0].label)
        assertEquals(Category.TRANSACTIONAL, c[0].predicted)
        assertEquals(12, c[0].count)
        assertEquals(20, c[0].ofLabel)
        assertEquals(2, c[1].count)
    }

    @Test
    fun `each category says how many labels and how many it follows`() {
        val marketing = LabDiagnosis.categories(items).single { it.category == Category.MARKETING }
        assertEquals(20, marketing.labels)
        assertEquals(0.4, marketing.recall, 1e-9)
        val transactional = LabDiagnosis.categories(items).single { it.category == Category.TRANSACTIONAL }
        assertEquals(0, transactional.labels)
        assertEquals(0.0, transactional.precision!!, 1e-9)
    }

    @Test
    fun `its surest mistakes are only the confident ones, surest first`() {
        val m = LabDiagnosis.surestMistakes(items)
        assertEquals(4, m.size)
        assertEquals(0.97, m[0].confidence, 1e-9)
        assertTrue(m.all { it.label != it.predicted && it.confidence >= LabDiagnosis.SURE })
    }

    @Test
    fun `alike texts labeled differently are found, numbers and links aside, and a pair is one pair either way round`() {
        val texts = listOf(
            LabeledText("a", "Your Walgreens prescription is ready for pickup at 123 Main St. Reply STOP to opt out", Category.MARKETING),
            LabeledText("b", "Your Walgreens prescription is ready for pickup at 900 Oak Ave. Reply STOP to opt out", Category.TRANSACTIONAL),
            LabeledText("c", "Your Walgreens prescription is ready for pickup at 55 Elm St. Reply STOP to opt out", Category.MARKETING),
            LabeledText("d", "Hey are we still on for dinner tonight?", Category.PERSONAL),
            LabeledText("e", "Track your order at https://ups.com/x123 now", Category.TRANSACTIONAL),
            LabeledText("f", "Track your order at https://ups.com/y999 now", Category.SPAM),
        )
        val pairs = LabDiagnosis.labeledApart(texts)
        val ids = pairs.map { it.id }.toSet()
        assertTrue("a|b" in ids)
        assertTrue("b|c" in ids)
        assertTrue("e|f" in ids)
        assertFalse("same label: nothing to look at", "a|c" in ids)
        assertTrue(pairs.none { it.a.key == "d" || it.b.key == "d" })
        assertEquals(LabDiagnosis.Pair2(texts[1], texts[0], 1.0).id, LabDiagnosis.Pair2(texts[0], texts[1], 1.0).id)
    }

    @Test
    fun `suggestions are about the model, never the user's labels`() {
        val out = LabDiagnosis.suggestions(items, toReview = 3, keptApart = 2, fitAccuracy = 1.0, serviceWeight = 0.35, serviceAgreement = 0.6)
        val titles = out.map { it.title }
        assertEquals("Similar texts, different labels from you", titles[0])
        assertTrue(titles.any { it == "It can't yet tell your marketing from transactional" })
        assertTrue(titles.any { it == "A bigger model won't help" })
        assertTrue(titles.any { it == "Your labels outweigh the service's" })
        // Kept apart: the model's limit, said so.
        assertTrue(out.single { it.title.startsWith("It can't yet tell") }.detail.contains("You kept 2 pairs"))
        // No talk of wrong, noisy or mistaken labels, or of checking them against the model.
        val all = out.joinToString(" ") { it.title + " " + it.detail }.lowercase()
        listOf("noisy", "mislabel", "wrong label", "slip", "check your label", "check the label").forEach { assertFalse(it, it in all) }
    }

    @Test
    fun `nothing to review, and a category short of examples, are said so`() {
        val few = items + List(4) { s(500 + it, Category.POLITICAL, Category.SPAM) }
        val out = LabDiagnosis.suggestions(few, toReview = 0, keptApart = 0, fitAccuracy = null)
        assertFalse(out.any { it.title.startsWith("Similar texts") })
        assertTrue(out.any { it.title == "Give it more political to learn from" })
    }

    @Test
    fun `a model held back from its own labels is told what to change`() {
        val out = LabDiagnosis.suggestions(items, toReview = 0, keptApart = 0, fitAccuracy = 0.8)
        assertTrue(out.any { it.title == "It can't follow all of your labels even while learning them" })
    }

    @Test
    fun `where it can't tell two categories apart, it says when the user's labels of a sender take over`() {
        fun apart(senderMemory: Double?) = LabDiagnosis.suggestions(items, toReview = 0, keptApart = 0, fitAccuracy = null, senderMemory = senderMemory)
            .single { it.title.startsWith("It can't yet tell") }.detail
        assertTrue(apart(1.0).contains("once you've labeled 3 or more of a sender's texts, all the same way, that sender's next texts follow your label"))
        assertTrue(apart(null).contains("that sender's next texts follow your label"))
        assertTrue(apart(0.0).contains("With \"Who sent it\" off"))
    }

    @Test
    fun `a model as good on new texts as on what it learned is told nothing about its size`() {
        val good = List(97) { s(600 + it, Category.PERSONAL, Category.PERSONAL) } + List(3) { s(700 + it, Category.PERSONAL, Category.SPAM) }
        val out = LabDiagnosis.suggestions(good, toReview = 0, keptApart = 0, fitAccuracy = 1.0)
        assertFalse(out.any { it.title.startsWith("It can't follow all") || it.title == "A bigger model won't help" })
    }
}
