package com.ericflo.winnow

import com.ericflo.winnow.classifier.local.OnDeviceClassifier
import com.ericflo.winnow.classifier.local.Correction
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classifier.message.VerdictSource
import com.ericflo.winnow.classify.ModelInsight
import com.ericflo.winnow.classify.ModelInspector
import com.ericflo.winnow.classify.Pairwise
import com.ericflo.winnow.data.db.RunAnswerEntity
import com.ericflo.winnow.data.db.VerdictEntity
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInsightTest {
    private fun v(
        key: String,
        kind: String = VerdictEntity.KIND_PROVIDER,
        category: String? = "spam",
        local: String? = null,
        mine: String? = null,
        at: Long = 0,
        arrival: Boolean = true,
        fallback: String? = null,
        localModel: String? = "winnow-local-1·abc123",
    ) = VerdictEntity(
        messageKey = key, threadId = 1, address = "+15555550101", category = category, confidence = 0.9, action = Action.FILTER.name,
        sourceKind = kind, sourceDetail = "x", model = null, costUsd = 0.0, decidedAt = at, userCategory = mine, atArrival = arrival,
        localCategory = local, localModel = localModel, fallbackReason = fallback,
    )

    private fun a(key: String, said: String, model: String?, at: Long = 0) = RunAnswerEntity(
        runId = 1, messageKey = key, threadId = 1, address = "+15555550101", category = said, subcategory = null, confidence = 0.9,
        taught = true, modelCategory = model, modelConfidence = 0.8, previous = null, answeredAt = at,
    )

    @Test
    fun eachPairIsComparedOnlyWhereBothJudged() {
        val verdicts = listOf(
            // The service decided; the model thought otherwise; the user sided with the model.
            v("sms:1", category = "spam", local = "personal", mine = "personal"),
            // The service decided; the model agreed; the user never labeled it.
            v("sms:2", category = "marketing", local = "marketing"),
            // The model decided alone; the user agreed.
            v("sms:3", kind = VerdictEntity.KIND_LOCAL, category = "spam", mine = "spam"),
            // A rule: no one's opinion but the user's.
            v("sms:4", kind = VerdictEntity.KIND_RULE, category = "personal", mine = "personal"),
        )
        // A run asked about sms:3 later; the model had said spam before, the service said spam.
        val answers = listOf(a("sms:3", "spam", "spam"), a("sms:5", "personal", "spam"))
        val x = ModelInsight.agreement(verdicts, answers)
        assertEquals(Pairwise(2, 1), x.youService)
        assertEquals(Pairwise(2, 2), x.youModel)
        assertEquals(Pairwise(4, 2), x.modelService)
        assertEquals(1, x.modelWithYouAgainstService)
        assertEquals(0, x.modelWithServiceAgainstYou)
    }

    @Test
    fun aVerdictsAnswerWinsOverAnOlderRunsAndTheModelsFirstOpinionStands() {
        val verdicts = listOf(v("sms:1", category = "spam", local = "spam", mine = "spam"))
        val answers = listOf(a("sms:1", "marketing", "personal", at = 5))
        val x = ModelInsight.agreement(verdicts, answers)
        // The service's answer is the verdict's (spam), applied after the run's.
        assertEquals(Pairwise(1, 1), x.youService)
        // The model's opinion is the run's, kept from before it learned (the verdict's comes second).
        assertEquals(Pairwise(1, 0), x.youModel)
    }

    @Test
    fun weeksStartOnMondayAndCountOnlyLiveServiceDecisionsWithAnOpinion() {
        val monday = LocalDate.of(2026, 9, 28).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val day = 86_400_000L
        val verdicts = listOf(
            v("a", category = "spam", local = "spam", at = monday + 2 * day),
            v("b", category = "spam", local = "personal", at = monday + 6 * day),
            v("c", category = "spam", local = "spam", at = monday + 7 * day),
            // Not as it arrived, no opinion kept, or not the service's: left out.
            v("d", category = "spam", local = "spam", at = monday, arrival = false),
            v("e", category = "spam", local = null, at = monday),
            v("f", kind = VerdictEntity.KIND_LOCAL, category = "spam", local = "spam", at = monday),
        )
        val weeks = ModelInsight.weekly(verdicts, ZoneOffset.UTC)
        assertEquals(listOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5)), weeks.map { it.start })
        assertEquals(Pairwise(2, 1), weeks[0].pair)
        assertEquals(Pairwise(1, 1), weeks[1].pair)
    }

    @Test
    fun decidersSplitTheModelByWhyItDecided() {
        val verdicts = listOf(
            v("1", kind = VerdictEntity.KIND_RULE, at = 10),
            v("2", at = 10, mine = "spam"),
            v("3", kind = VerdictEntity.KIND_LOCAL, at = 10, fallback = VerdictSource.OnDevice.SURE),
            v("4", kind = VerdictEntity.KIND_LOCAL, at = 10, fallback = "Provider unavailable (x: timed out)"),
            v("5", kind = VerdictEntity.KIND_LOCAL, at = 10, fallback = "No provider fits your privacy settings"),
            v("6", kind = VerdictEntity.KIND_LOCAL, at = 10),
            v("7", kind = VerdictEntity.KIND_LOCAL, at = 10, localModel = null),
            v("8", kind = VerdictEntity.KIND_HEURISTIC, at = 10),
            // Too old, and not as it arrived.
            v("9", at = 1),
            v("10", at = 10, arrival = false),
        )
        val d = ModelInsight.deciders(verdicts, since = 5)
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1, 1), listOf(d.rule, d.service, d.modelSure, d.modelFallback, d.modelKept, d.modelOnly, d.modelUnknown, d.keywords))
        assertEquals(8, d.total)
        assertEquals(1, d.youSince)
    }

    @Test
    fun whatTeachingChangedIsNamedFromTheTextsThatTaughtIt() {
        val base = OnDeviceClassifier()
        val texts = listOf(InboundMessage("+15555550101", "Rehearsal moved to the blue barn tonight"))
        val taught = base.learn(texts.map { Correction(base.model.indices(base.features(it)), base.model.classes.indexOf(Category.REMINDER.key)) })
        val learned = ModelInspector.learned(taught, texts)
        val reminder = learned.getValue(Category.REMINDER)
        assertTrue(reminder.isNotEmpty())
        assertTrue(reminder.joinToString { it.name }, reminder.any { "barn" in it.name || "rehearsal" in it.name })
        // Nothing taught toward personal from that text.
        assertTrue(learned.getValue(Category.PERSONAL).isEmpty())
        val reading = ModelInspector.read(taught, texts.single())
        assertTrue(reading.taught.getValue(Category.REMINDER) > reading.shipped.getValue(Category.REMINDER))
        assertEquals("“#”", ModelInspector.name("w:zznumshort"))
        assertEquals("“at [link]”", ModelInspector.name("b:at zzurl"))
    }
}
