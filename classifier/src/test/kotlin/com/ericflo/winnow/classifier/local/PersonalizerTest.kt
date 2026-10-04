package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonalizerTest {
    private val base = OnDeviceClassifier()
    private val promo = InboundMessage("38411", "Brew Lab: Taco Tuesday is back! $2 tacos and $5 margaritas tonight. Reply STOP to opt out")
    private val allowed = setOf(Category.PERSONAL, Category.TRANSACTIONAL)

    @Test
    fun `a correction moves the corrected message and messages like it`() {
        assertEquals(Category.MARKETING, base.classify(promo).category)
        val correction = base.correction(promo, allowed)!!
        val taught = base.learn(listOf(correction))

        assertTrue(taught.classify(promo).category in allowed)
        // The same bar's next text moves the same way.
        val sibling = InboundMessage("38411", "Brew Lab: Trivia night is back! $5 margaritas and $2 tacos all night. Reply STOP to opt out")
        assertTrue(taught.classify(sibling).let { it.category in allowed || it.confidence < base.classify(sibling).confidence })
    }

    @Test
    fun `a correction barely moves unrelated messages`() {
        val taught = base.learn(listOfNotNull(base.correction(promo, allowed)))
        val corpus = Corpus.load(File("training/corpus")).map { InboundMessage(it.sender, it.body) }
        val changed = corpus.count { base.classify(it).category != taught.classify(it).category }
        assertTrue("$changed of ${corpus.size} changed", changed <= corpus.size / 50)

        val phish = InboundMessage("+17025550161", "FasTrak: You have an unpaid toll of 6.25 USD. Pay now to avoid penalties: fastrak.com-billing.vip")
        assertEquals(Category.PHISHING, taught.classify(phish).category)
    }

    @Test
    fun `the label is the likeliest acceptable category`() {
        val phish = InboundMessage("+17025550161", "Your E-ZPass has an unpaid balance. Pay now: ezpass.com-pay.top")
        val filterable = setOf(Category.PHISHING, Category.SCAM, Category.SPAM, Category.POLITICAL)
        val c = base.correction(phish, filterable)!!
        assertEquals(Category.PHISHING.key, LocalModel.bundled.classes[c.label])
        assertEquals(null, base.correction(phish, emptySet()))
    }

    @Test
    fun `no corrections means no change`() {
        assertEquals(0, Personalizer.train(LocalModel.bundled, emptyList()).size)
        assertEquals(base.classify(promo), base.learn(emptyList()).classify(promo))
    }
}
