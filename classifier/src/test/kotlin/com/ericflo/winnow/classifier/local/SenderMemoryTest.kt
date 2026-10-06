package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderMemoryTest {
    private val classes = LocalModel.bundled.classes
    private fun c(category: Category) = classes.indexOf(category.key)

    @Test
    fun `a sender is the same sender however its number is written`() {
        assertEquals(SenderMemory.keyOf("+14155550100"), SenderMemory.keyOf("(415) 555-0100"))
        assertEquals(SenderMemory.keyOf("4155550100"), SenderMemory.keyOf("+1 415 555 0100"))
        assertEquals("short:72166", SenderMemory.keyOf("72166"))
        assertEquals("amazon", SenderMemory.keyOf(" AMAZON "))
        assertNull(SenderMemory.keyOf("  "))
    }

    @Test
    fun `a sender with no labels, or strength zero, changes nothing`() {
        val p = DoubleArray(classes.size) { 1.0 / classes.size }
        val memory = SenderMemory.of(listOf("+14155550100" to c(Category.TRANSACTIONAL)), classes)
        assertTrue(memory.apply(p, "+14155550199") === p)
        assertTrue(memory.withStrength(0.0).apply(p, "+14155550100") === p)
    }

    @Test
    fun `one label nudges and many decide, toward the user's category`() {
        val p = DoubleArray(classes.size) { 0.02 }.also { it[c(Category.REMINDER)] = 0.9 }
        fun memory(n: Int) = SenderMemory.of(List(n) { "+14155550100" to c(Category.TRANSACTIONAL) }, classes)
        val one = memory(1).apply(p, "+14155550100")
        assertEquals(1.0, one.sum(), 1e-9)
        assertTrue("nudged", one[c(Category.TRANSACTIONAL)] > p[c(Category.TRANSACTIONAL)])
        assertEquals("but one label doesn't overrule a sure model", c(Category.REMINDER), one.indices.maxBy { one[it] })
        // Enough labels, one way, settle it whatever the model says.
        assertNull("two don't", memory(2).decisive("+14155550100"))
        assertEquals("three do", c(Category.TRANSACTIONAL), memory(3).decisive("+14155550100"))
        assertEquals(0.75, memory(3).decisiveConfidence("+14155550100", c(Category.TRANSACTIONAL)), 1e-9)
    }

    @Test
    fun `the classifier follows the user's labels of a sender, and says so`() {
        val text = InboundMessage("+14155550100", "Your prescription is ready for pickup at the pharmacy counter")
        val plain = OnDeviceClassifier().classify(text)
        val target = if (plain.category == Category.TRANSACTIONAL) Category.REMINDER else Category.TRANSACTIONAL
        val memory = SenderMemory.of(List(6) { "(415) 555-0100" to c(target) }, classes)
        val followed = OnDeviceClassifier().withMemory(memory).classify(text)
        assertEquals(target, followed.category)
        assertTrue(followed.reasons.first(), followed.reasons.first().startsWith("you labeled 6 of this sender's 6 texts"))
        assertTrue(followed.yourLabelsDecide)
        // Another sender: as before.
        assertEquals(plain.category, OnDeviceClassifier().withMemory(memory).classify(text.copy(sender = "+14155550199")).category)
        assertFalse(plain.yourLabelsDecide)
    }

    @Test
    fun `labels that disagree with each other don't decide on their own`() {
        val text = InboundMessage("+14155550100", "Your prescription is ready for pickup at the pharmacy counter")
        val memory = SenderMemory.of(List(3) { "+14155550100" to c(Category.REMINDER) } + List(3) { "+14155550100" to c(Category.TRANSACTIONAL) }, classes)
        assertFalse(OnDeviceClassifier().withMemory(memory).classify(text).yourLabelsDecide)
    }

    @Test
    fun `someone the user texts with is leaned toward their labels, never decided by them`() {
        // A friend: four texts labeled reminder ("can you grab milk?"), and now dinner plans.
        val dinner = InboundMessage("+14155550198", "Dinner Friday? We could try the new thai place", userHasMessagedSender = true)
        val memory = SenderMemory.of(List(4) { "+14155550198" to c(Category.REMINDER) }, classes)
        val p = DoubleArray(classes.size) { 0.02 }.also { it[c(Category.PERSONAL)] = 0.9 }
        assertNull(memory.decisive("+14155550198", conversing = true))
        assertEquals(c(Category.REMINDER), memory.decisive("+14155550198"))
        val leaned = memory.follow(p, "+14155550198", conversing = true)
        assertFalse(leaned.decided)
        assertTrue("nudged toward their labels", leaned.p[c(Category.REMINDER)] > p[c(Category.REMINDER)])
        val said = OnDeviceClassifier().withMemory(memory).classify(dinner)
        assertFalse(said.yourLabelsDecide)
        assertEquals(Category.PERSONAL, OnDeviceClassifier().classify(dinner).category)
        assertEquals("their dinner plans stay personal", Category.PERSONAL, said.category)
        // The same labels of a sender they don't text with would decide.
        assertTrue(OnDeviceClassifier().withMemory(memory).classify(dinner.copy(userHasMessagedSender = false)).yourLabelsDecide)
    }

    @Test
    fun `counts are kept per class in model order`() {
        val memory = SenderMemory.of(listOf("72166" to c(Category.SPAM), "72166" to c(Category.SPAM), "72166" to c(Category.MARKETING)), classes)
        val counts = memory.countsFor("72166")!!
        assertArrayEquals(IntArray(classes.size).also { it[c(Category.SPAM)] = 2; it[c(Category.MARKETING)] = 1 }, counts)
    }
}
