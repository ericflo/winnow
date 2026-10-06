package com.ericflo.winnow

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classify.SenderInsight
import com.ericflo.winnow.classify.SenderInsight.Labeled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderInsightTest {
    private val labels = listOf(
        // A pharmacy, its number written two ways: three transactional, all one way.
        Labeled("+12395550160", Category.TRANSACTIONAL, 10),
        Labeled("(239) 555-0160", Category.TRANSACTIONAL, 11),
        Labeled("2395550160", Category.TRANSACTIONAL, 11),
        // A bank's short code, labeled both ways.
        Labeled("72166", Category.TRANSACTIONAL, 20),
        Labeled("72166", Category.TRANSACTIONAL, 20),
        Labeled("72166", Category.TRANSACTIONAL, 20),
        Labeled("72166", Category.MARKETING, 20),
        // A friend the user texts with.
        Labeled("+14155550198", Category.TRANSACTIONAL, 30),
        Labeled("+14155550198", Category.TRANSACTIONAL, 30),
        Labeled("+14155550198", Category.TRANSACTIONAL, 30),
        // Two labels: not yet enough.
        Labeled("+16465550142", Category.SPAM, 40),
        Labeled("+16465550142", Category.SPAM, 41),
    )

    @Test
    fun eachSenderSaysWhetherTheirLabelsDecideOrLean() {
        val senders = SenderInsight.of(labels, conversing = setOf(30L), strength = 1.0).associateBy { it.key }
        val pharmacy = senders.getValue("2395550160")
        assertEquals(Category.TRANSACTIONAL, pharmacy.decides)
        assertEquals(3, pharmacy.total)
        assertEquals(11L, pharmacy.threadId)
        // Labeled both ways: their labels lean, the bigger count first.
        val bank = senders.getValue("short:72166")
        assertNull(bank.decides)
        assertEquals(listOf(Category.TRANSACTIONAL to 3, Category.MARKETING to 1), bank.counts)
        // Someone the user texts with: lean, whatever the labels.
        val friend = senders.getValue("4155550198")
        assertNull(friend.decides)
        assertTrue(friend.conversing)
        assertNull(senders.getValue("6465550142").decides)
    }

    @Test
    fun decidersComeFirstThenTheMostLabeled() {
        val order = SenderInsight.of(labels, conversing = setOf(30L), strength = 1.0).map { it.key }
        assertEquals(listOf("2395550160", "short:72166", "4155550198", "6465550142"), order)
    }

    @Test
    fun withWhoSentItOffNothingDecides() {
        assertTrue(SenderInsight.of(labels, conversing = emptySet(), strength = 0.0).all { it.decides == null })
    }
}
