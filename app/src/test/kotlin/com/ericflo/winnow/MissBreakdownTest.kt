package com.ericflo.winnow

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.SenderKind
import com.ericflo.winnow.classify.MissBreakdown
import org.junit.Assert.assertEquals
import org.junit.Test

class MissBreakdownTest {
    private fun item(n: Int, label: Category, predicted: Category, confidence: Double = 0.7, thread: Long = n.toLong(), sender: String = "+1206555${"%04d".format(n % 10000)}") =
        MissBreakdown.Item("sms:$n", thread, sender, label, predicted, confidence)

    @Test
    fun itSaysHowFarToTheTargetAndWhichMissesWouldGetThere() {
        // 100 labels, 15 wrong: 12 marketing it called transactional, 3 political it called spam.
        val items = (0 until 85).map { item(it, Category.PERSONAL, Category.PERSONAL, 0.95) } +
            (0 until 12).map { item(100 + it, Category.MARKETING, Category.TRANSACTIONAL, if (it < 9) 0.9 else 0.5, thread = 7L + it % 2, sender = "72345") } +
            (0 until 3).map { item(200 + it, Category.POLITICAL, Category.SPAM, 0.6) }
        val r = MissBreakdown.of(items, 0.9)
        assertEquals(15, r.misses)
        assertEquals(10, r.allowed)
        assertEquals(5, r.needed)
        val marketing = r.groups.first()
        assertEquals(Category.MARKETING to Category.TRANSACTIONAL, marketing.label to marketing.predicted)
        assertEquals(12, marketing.ofLabel)
        // All from two conversations, all from a short code, nine of them sure.
        assertEquals(2, marketing.conversations)
        assertEquals(6, marketing.mostFromOne)
        assertEquals(mapOf(SenderKind.SHORT_CODE to 12), marketing.senderKinds)
        assertEquals(9, marketing.sure)
        assertEquals(3, marketing.close)
        // Five of the marketing right would reach 90%.
        assertEquals(listOf(marketing to 5), r.path)
        assertEquals(3, r.closeCalls)
    }

    @Test
    fun atTheTargetAlreadyNothingIsNeeded() {
        val items = (0 until 95).map { item(it, Category.PERSONAL, Category.PERSONAL) } + (0 until 5).map { item(100 + it, Category.SPAM, Category.MARKETING) }
        val r = MissBreakdown.of(items, 0.9)
        assertEquals(0, r.needed)
        assertEquals(emptyList<Pair<MissBreakdown.Group, Int>>(), r.path)
    }
}
