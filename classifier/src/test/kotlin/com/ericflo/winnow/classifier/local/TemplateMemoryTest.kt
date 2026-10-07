package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateMemoryTest {
    private val base = LocalModel.bundled
    private val classes = base.classes
    private fun c(category: Category) = classes.indexOf(category.key)
    private fun buckets(text: String) = base.indices(Featurizer.features(Featurizer.Input("72345", text)))

    private val labeled = listOf(
        "Sprout Market: Double points weekend! Earn 2x on every purchase thru Sun. Reply STOP to end" to Category.MARKETING,
        "Sprout Market: Double points weekend! Earn 2x on every purchase thru Mon. Reply STOP to end" to Category.MARKETING,
        "Your Sprout Market order #4471 is ready for pickup at the Elm St store" to Category.TRANSACTIONAL,
        "Cascade Credit Union: a purchase of \$42.18 was made with your card ending 4417" to Category.TRANSACTIONAL,
    )
    private val memory = TemplateMemory.of(labeled.map { (t, cat) -> buckets(t) to c(cat) }, classes, 2.0)

    @Test
    fun aTextNearlyLikeLabeledOnesFindsThemAndOneLikeNoneFindsNothing() {
        val near = memory.neighbors(buckets("Sprout Market: Double points weekend! Earn 2x on every purchase thru Tue. Reply STOP to end"))
        assertEquals(listOf(c(Category.MARKETING), c(Category.MARKETING)), near.map { it.label })
        assertTrue(near.all { it.likeness >= TemplateMemory.MIN_LIKENESS && it.likeness <= 1.0 + 1e-9 }, "$near")
        assertTrue(memory.neighbors(buckets("hey are we still on for dinner tonight?")).isEmpty())
        assertTrue(TemplateMemory.NONE.neighbors(buckets("anything")).isEmpty())
    }

    @Test
    fun itLeansTowardWhatTheLookalikesWereCalledAndOnlyThen() {
        val p = DoubleArray(classes.size) { 0.05 }.also { it[c(Category.TRANSACTIONAL)] = 0.8 }
        val promo = buckets("Sprout Market: Double points weekend! Earn 2x on every purchase thru Tue. Reply STOP to end")
        val leaned = memory.follow(p, promo)
        assertEquals(1.0, leaned.sum(), 1e-9)
        assertEquals(c(Category.MARKETING), leaned.indices.maxBy { leaned[it] })
        // Strength 0, or a text like none of them: as it was.
        assertTrue(memory.withStrength(0.0).follow(p, promo) === p)
        assertTrue(memory.follow(p, buckets("hey are we still on for dinner tonight?")) === p)
        assertNull(TemplateMemory.lean(p, null, 3.0).takeIf { it !== p })
        // The vote is the lookalikes' likeness squared, shared out: all of it to marketing here.
        assertContentEquals(DoubleArray(classes.size) { if (it == c(Category.MARKETING)) 1.0 else 0.0 }.toList(), memory.vote(promo)!!.toList())
    }

    @Test
    fun onThePhoneItSaysWhenTheTextsItReadsLikeMadeTheDifference() {
        val promo = InboundMessage("72345", "Sprout Market: Double points weekend! Earn 2x on every purchase thru Tue. Reply STOP to end")
        val withMemory = OnDeviceClassifier().withTemplates(TemplateMemory.of(labeled.map { (t, cat) -> buckets(t) to c(cat) }, classes, 3.0))
        val plain = OnDeviceClassifier().classify(promo)
        val leaned = withMemory.classify(promo)
        assertEquals(Category.MARKETING, leaned.category)
        if (plain.category != Category.MARKETING) {
            assertTrue(leaned.reasons.first().startsWith("it reads like 2 texts you labeled marketing"), "${leaned.reasons}")
        }
        // A text like none of them: exactly as without it.
        val dinner = InboundMessage("+14155550142", "hey are we still on for dinner tonight?")
        assertEquals(OnDeviceClassifier().classify(dinner).distribution, withMemory.classify(dinner).distribution)
    }
}
