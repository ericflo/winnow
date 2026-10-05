package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonalEvaluationTest {
    private val base = OnDeviceClassifier()
    private val model = LocalModel.bundled

    /** Corpus texts as the user's labels: each its own conversation unless [perConversation] > 1. */
    private fun labels(n: Int, perConversation: Int = 1): List<PersonalEvaluation.Label> =
        Corpus.load(File("training/corpus")).shuffled(kotlin.random.Random(11)).take(n).mapIndexedNotNull { i, t ->
            val c = base.correction(InboundMessage(t.sender, t.body), setOf(t.category)) ?: return@mapIndexedNotNull null
            PersonalEvaluation.Label(c.buckets, c.label, group = (i / perConversation).toLong())
        }

    @Test
    fun everyLabelIsScoredOnceAndTheSameWayEachTime() {
        val labels = labels(120, perConversation = 3)
        val first = PersonalEvaluation.crossValidate(model, labels)
        assertEquals(labels.size, first.size)
        val again = PersonalEvaluation.crossValidate(model, labels)
        assertEquals(first.map { it.label to it.probabilities.toList() }.sortedBy { it.toString() }, again.map { it.label to it.probabilities.toList() }.sortedBy { it.toString() })
    }

    @Test
    fun aLabelIsNeverScoredByAModelThatSawItsConversation() {
        // Two conversations whose texts are identical within each, labeled against the grain:
        // a model that saw a twin would get it "right"; one that didn't, can't.
        val promo = base.correction(InboundMessage("38411", "Brew Lab: Taco Tuesday! \$2 tacos tonight. Reply STOP to opt out"), setOf(Category.PERSONAL))!!
        val bank = base.correction(InboundMessage("+17025550161", "Chase: your account is locked. Verify at chase-secure-verify.com"), setOf(Category.PERSONAL))!!
        val twins = List(5) { PersonalEvaluation.Label(promo.buckets, promo.label, group = 1) } + List(5) { PersonalEvaluation.Label(bank.buckets, bank.label, group = 2) }
        val rows = PersonalEvaluation.crossValidate(model, twins, folds = 2)
        val personal = model.classes.indexOf(Category.PERSONAL.key)
        assertTrue("held-out twins aren't scored as personal", rows.none { it.predicted == personal })
    }

    @Test
    fun tooFewLabelsMeansNoCharts() {
        assertNull(PersonalEvaluation.metrics(model, labels(10), emptyList(), filterAt = 0.85))
        val oneKind = labels(200).filter { it.label == 0 }
        assertNull(PersonalEvaluation.metrics(model, oneKind, emptyList(), filterAt = 0.85))
    }

    @Test
    fun enoughLabelsGiveEveryChart() {
        val m = PersonalEvaluation.metrics(model, labels(150), emptyList(), filterAt = 0.85)!!
        assertEquals(150, m.examples)
        assertEquals(Category.entries.size, m.confusion.size)
        assertEquals(150, m.confusion.sumOf { it.sum() })
        assertTrue(m.unwanted.roc.isNotEmpty())
        assertTrue(m.calibration.sumOf { it.count } == 150)
    }
}
