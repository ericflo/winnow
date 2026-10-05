package com.ericflo.winnow

import com.ericflo.winnow.classifier.local.Correction
import com.ericflo.winnow.classifier.local.OnDeviceClassifier
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classify.EvalData
import com.ericflo.winnow.classify.EvalSubject
import com.ericflo.winnow.classify.Evaluator
import com.ericflo.winnow.data.db.EvalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluatorTest {
    private val base = OnDeviceClassifier()
    private val model = base.model

    /**
     * Texts a user might label: a made-up club ("the zorblax league") the shipped model can't
     * know the user files as political, and plain spam and personal texts it mostly gets right already.
     */
    private fun labeled(n: Int): List<EvalData.Labeled> = (0 until n).map { i ->
        val (text, category) = when (i % 3) {
            0 -> "Zorblax league practice moved to field ${i % 7}, bring cleats" to Category.POLITICAL
            1 -> "Your toll balance is unpaid, pay now at tolls-$i.top" to Category.SPAM
            else -> "Want to grab dinner thursday? ${i % 5}" to Category.PERSONAL
        }
        val buckets = model.indices(base.features(InboundMessage("+1555555${1000 + i}", text)))
        EvalData.Labeled("sms:$i", threadId = i.toLong(), buckets = buckets, label = model.classes.indexOf(category.key), createdAt = i.toLong())
    }

    private fun data(n: Int = 60, service: Map<String, Pair<Category, Double>> = emptyMap()) = EvalData(labeled(n), emptyList(), emptyList(), service)

    @Test
    fun theShippedModelIsScoredOnEveryLabelAndTheModelNowByCrossValidation() {
        val d = data()
        val e = Evaluator()
        val shipped = e.evaluate(EvalSubject.Shipped, d)
        assertEquals(Evaluator.METHOD_UNTAUGHT, shipped.method)
        assertEquals(60, shipped.items.size)
        val now = e.evaluate(EvalSubject.Now, d)
        assertEquals(EvalEntity.METHOD_CROSS_VALIDATED, now.method)
        assertEquals(60, now.items.size)
        // Taught the made-up club by other conversations' labels, it beats the model as shipped.
        assertTrue("${now.metrics!!.accuracy} vs ${shipped.metrics!!.accuracy}", now.metrics!!.accuracy > shipped.metrics!!.accuracy)
        // The same data scores the same way every time.
        assertEquals(now.items, e.evaluate(EvalSubject.Now, d).items)
    }

    @Test
    fun aKeptFitIsScoredOnlyOnLabelsMadeAfterIt() {
        val d = data()
        val early = base.learn(d.labels.take(30).map { Correction(it.buckets, it.label) }).adjustments
        val kept = Evaluator().evaluate(EvalSubject.Kept("abc123", fittedAt = 29, adjustments = early), d)
        assertEquals(EvalEntity.METHOD_SINCE, kept.method)
        assertEquals((30 until 60).map { "sms:$it" }, kept.items.map { it.key })
        // With nothing labeled since, it says it's scored on what it learned from.
        val latest = Evaluator().evaluate(EvalSubject.Kept("def456", fittedAt = 1_000, adjustments = early), d)
        assertEquals(EvalEntity.METHOD_TRAINED_ON, latest.method)
        assertEquals(60, latest.items.size)
    }

    @Test
    fun theServiceIsScoredOnlyWhereItAnsweredAndAsSureAsItSaid() {
        val service = mapOf("sms:0" to (Category.POLITICAL to 0.9), "sms:1" to (Category.PERSONAL to 0.6), "sms:2" to (Category.PERSONAL to 0.95))
        val r = Evaluator().evaluate(EvalSubject.Service("Jev"), data(service = service))
        assertEquals(EvalEntity.METHOD_RECORDED, r.method)
        assertEquals(listOf("sms:0", "sms:1", "sms:2"), r.items.map { it.key })
        assertEquals(2.0 / 3, r.metrics!!.accuracy, 1e-9)
        assertEquals(0.6, r.items[1].confidence, 1e-9)
    }

    @Test
    fun theLearningCurveScoresEachStepOnLabelsItHadntSeen() {
        val curve = Evaluator().learningCurve(data(80), steps = 4)
        assertEquals(listOf(20, 40, 60), curve.map { it.taught })
        assertEquals(listOf(20, 20, 20), curve.map { it.tested })
        // Having learned the club, it gets more of the next labels right than the shipped model does.
        assertTrue(curve.joinToString(), curve.last().right > curve.last().shippedRight)
        // Too few labels for the steps asked: nothing to draw.
        assertTrue(Evaluator().learningCurve(data(6), steps = 4).isEmpty())
    }
}

class RebuildWeightTest {
    @Test
    fun aScoredRebuildSaysWhatWeightItUsed() {
        assertEquals(0.0, com.ericflo.winnow.ui.model.weightOf(EvalSubject.YoursOnly.key)!!, 0.0)
        assertEquals(0.6, com.ericflo.winnow.ui.model.weightOf(EvalSubject.ServiceWeight(0.6).key)!!, 0.0)
        assertEquals(null, com.ericflo.winnow.ui.model.weightOf(EvalSubject.Now.key))
        assertEquals(null, com.ericflo.winnow.ui.model.weightOf(EvalSubject.Shipped.key))
    }

    @Test
    fun aDifferentWeightIsADifferentFitButTheUsualOneKeepsItsStamp() {
        val rows = listOf(
            com.ericflo.winnow.data.db.CorrectionEntity(id = 1, threadId = 1, buckets = "1,2", label = "spam", featurizerVersion = 4, createdAt = 0, messageKey = "sms:1", source = "provider", runId = 3),
        )
        val usual = com.ericflo.winnow.classify.PersonalModelStore.stampOf(rows, 7)
        assertEquals(usual, com.ericflo.winnow.classify.PersonalModelStore.stampOf(rows, 7, com.ericflo.winnow.classify.Learner.PROVIDER_WEIGHT))
        assertTrue(usual != com.ericflo.winnow.classify.PersonalModelStore.stampOf(rows, 7, 0.0))
        assertTrue(com.ericflo.winnow.classify.PersonalModelStore.stampOf(rows, 7, 0.5) != com.ericflo.winnow.classify.PersonalModelStore.stampOf(rows, 7, 0.0))
    }
}
