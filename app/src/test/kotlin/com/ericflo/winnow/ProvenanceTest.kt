package com.ericflo.winnow

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.ActionPolicy
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.Verdict
import com.ericflo.winnow.classifier.message.VerdictSource
import com.ericflo.winnow.classify.Decider
import com.ericflo.winnow.classify.ModelReason
import com.ericflo.winnow.classify.Provenance
import com.ericflo.winnow.classify.teaches
import com.ericflo.winnow.data.db.CorrectionEntity
import com.ericflo.winnow.data.db.RunEntity
import com.ericflo.winnow.data.db.VerdictEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceTest {
    private fun verdict(
        kind: String = VerdictEntity.KIND_PROVIDER,
        detail: String = "typesafe-jev",
        category: String? = "spam",
        confidence: Double = 0.92,
        action: Action = Action.FILTER,
        localCategory: String? = "personal",
        localModel: String? = "winnow-local-1·3fa2c1",
        fallback: String? = null,
        userCategory: String? = null,
        runId: Long? = null,
        examples: Int = 0,
        atArrival: Boolean = true,
    ) = VerdictEntity(
        messageKey = "sms:1", threadId = 1, address = "+15555550101", category = category, confidence = confidence, action = action.name,
        sourceKind = kind, sourceDetail = detail, model = if (kind == VerdictEntity.KIND_PROVIDER) "jev-1" else localModel, costUsd = 0.0001, decidedAt = 0,
        userCategory = userCategory, atArrival = atArrival, subcategory = "toll_phishing".takeIf { kind == VerdictEntity.KIND_PROVIDER },
        localCategory = localCategory, localConfidence = 0.61, localModel = localModel, fallbackReason = fallback, latencyMillis = 840, runId = runId, promptExamples = examples,
    )

    private fun explain(v: VerdictEntity, taught: List<CorrectionEntity> = emptyList(), run: RunEntity? = null, learn: Boolean = true, hook: Boolean? = true) =
        Provenance.explain(v, taught, run, null, ActionPolicy(), { "Jev (TypeSafe)" }, learn, hook, format = { "Oct 5" })

    private fun label(source: String, runId: Long? = null) =
        CorrectionEntity(threadId = 1, buckets = "1", label = "spam", featurizerVersion = 4, createdAt = 0, messageKey = "sms:1", source = source, runId = runId)

    @Test
    fun aServicesAnswerShowsWhatTheModelThoughtBesideItAndThatYourLabelsWentNowhere() {
        val e = explain(verdict(), taught = listOf(label(CorrectionEntity.SOURCE_PROVIDER)))
        assertEquals(Decider.PROVIDER, e.decider)
        assertEquals("Spam · filtered", e.outcome)
        assertTrue(e.decidedBy, e.decidedBy.startsWith("Jev (TypeSafe) decided, as it arrived"))
        assertTrue(e.why.any { "toll phishing" in it })
        assertTrue(e.why.any { "840 ms" in it })
        assertTrue(e.why.any { "carried none of your labels" in it })
        assertEquals(listOf("Jev (TypeSafe)", "On-device model"), e.opinions.map { it.who })
        assertEquals(Category.PERSONAL, e.opinions[1].category)
        assertTrue(e.learning.single(), "taught the on-device model as the text arrived" in e.learning.single())
        assertNull(e.gaps)
    }

    @Test
    fun aBacklogRunsAnswerSaysWhichRunAndWhatExamplesItCarried() {
        val run = RunEntity(id = 4, kind = RunEntity.KIND_BACKLOG, provider = "Jev (TypeSafe)", startedAt = 0, updatedAt = 0, planned = 10, conversations = 3)
        val e = explain(verdict(runId = 4, examples = 12, atArrival = false), taught = listOf(label(CorrectionEntity.SOURCE_PROVIDER, runId = 4)), run = run)
        assertTrue(e.decidedBy, "in a backlog run on Oct 5" in e.decidedBy)
        assertTrue(e.why.any { "carried 12 texts you'd labeled" in it })
        assertTrue("in this run" in e.learning.single())
        assertEquals(4L, e.runId)
    }

    @Test
    fun anUnsureAnswerOrLearningTurnedOffSaySoInsteadOfTeaching() {
        assertTrue("Under 70%" in explain(verdict(confidence = 0.55, action = Action.SILENCE)).learning.single())
        assertTrue("off in Settings" in explain(verdict(), learn = false).learning.single())
    }

    @Test
    fun theModelSaysWhyItDecidedAndNotTheService() {
        val sure = verdict(kind = VerdictEntity.KIND_LOCAL, detail = "“toll”, a .top link", category = "spam", confidence = 0.97, localCategory = "spam", fallback = VerdictSource.OnDevice.SURE)
        assertEquals(ModelReason.SURE, Provenance.modelReason(sure))
        assertTrue(explain(sure).why.first().contains("wasn't asked"))
        val failed = sure.copy(fallbackReason = "Provider unavailable (typesafe-jev: timed out)")
        assertEquals(ModelReason.PROVIDER_FAILED, Provenance.modelReason(failed))
        assertTrue(explain(failed).why.first().contains("typesafe-jev: timed out"))
        assertEquals(ModelReason.KEPT_ON_PHONE, Provenance.modelReason(sure.copy(fallbackReason = "No provider fits your privacy settings")))
        assertEquals(ModelReason.ONLY_ONE, Provenance.modelReason(sure.copy(fallbackReason = null)))
        // From before the model's opinion and its reasons were kept: unknown, and said so.
        val old = sure.copy(fallbackReason = null, localModel = null, localCategory = null)
        assertEquals(ModelReason.UNKNOWN, Provenance.modelReason(old))
        assertNotNull(explain(old).gaps)
    }

    @Test
    fun theUsersLabelStandsAndRulesSayTheirReason() {
        val labeled = explain(verdict(userCategory = "personal"), taught = listOf(label(CorrectionEntity.SOURCE_USER)))
        assertEquals("Personal · filtered", labeled.outcome)
        assertTrue(labeled.why.first().startsWith("You've since labeled it Personal"))
        assertEquals("You", labeled.opinions.first().who)
        assertTrue("counting fully" in labeled.learning.single())
        val rule = explain(verdict(kind = VerdictEntity.KIND_RULE, detail = "Sender is in your contacts", category = "personal", action = Action.ALLOW, localCategory = null, localModel = null))
        assertEquals(Decider.RULE, rule.decider)
        assertEquals("Sender is in your contacts.", rule.decidedBy)
        assertNull(rule.policy)
    }

    @Test
    fun howACategoryBecameAnActionIsExplained() {
        val p = ActionPolicy()
        assertEquals("Your settings filter Spam.", Provenance.policyLine(Category.SPAM, 0.95, Action.FILTER, Decider.PROVIDER, p, true))
        assertTrue("only 55% sure (under 70%)" in Provenance.policyLine(Category.SPAM, 0.55, Action.SILENCE, Decider.PROVIDER, p, true))
        assertTrue("under 85%" in Provenance.policyLine(Category.SPAM, 0.8, Action.SILENCE, Decider.MODEL, p, true))
        assertTrue("nothing a scammer could use" in Provenance.policyLine(Category.SPAM, 0.9, Action.SILENCE, Decider.MODEL, p, false))
        assertTrue("let through" in Provenance.policyLine(Category.SPAM, 0.5, Action.ALLOW, Decider.MODEL, p, false))
        assertTrue("never do more than silenced" in Provenance.policyLine(Category.SPAM, 0.9, Action.SILENCE, Decider.FALLBACK, p, null))
    }

    @Test
    fun onlyASureServiceAnswerTeachesAsTextsArrive() {
        val answer = Verdict(Category.SPAM, 0.9, Action.FILTER, VerdictSource.Provider("typesafe-jev", "jev-1"))
        assertTrue(teaches(answer, learnFromProvider = true))
        assertFalse(teaches(answer, learnFromProvider = false))
        assertFalse(teaches(answer.copy(confidence = 0.6), learnFromProvider = true))
        assertFalse(teaches(answer.copy(source = VerdictSource.OnDevice("m", emptyList())), learnFromProvider = true))
        assertFalse(teaches(answer.copy(category = null), learnFromProvider = true))
    }

    @Test
    fun aServicesAnswerYourLabelsOutweighedSaysWhatItSaidAndWhyItDidntStand() {
        val v = verdict(
            kind = VerdictEntity.KIND_LOCAL, detail = "you labeled 3 of this sender's 4 texts transactional", category = "transactional",
            localCategory = "transactional", fallback = "${VerdictSource.OnDevice.OVER_SERVICE} (it said personal)",
        ).copy(serviceCategory = "personal", serviceConfidence = 0.88)
        assertEquals(ModelReason.OVER_SERVICE, Provenance.modelReason(v))
        val e = explain(v)
        assertTrue(e.why.first(), e.why.first().contains("called it personal, which you've never called this sender's texts"))
        assertTrue(e.why.any { "answered in" in it })
        val service = e.opinions.single { it.who == "Classifier service" }
        assertEquals(Category.PERSONAL, service.category)
        assertEquals("outweighed by your labels of this sender", service.detail)
    }
}

class DescribeModelTest {
    @org.junit.Test
    fun aModelSaysWhichItIs() {
        org.junit.Assert.assertEquals("as it ships, before anything you taught it", Provenance.describeModel("winnow-local-1"))
        org.junit.Assert.assertEquals("fit 3fa2c1", Provenance.describeModel("winnow-local-1·3fa2c1"))
        org.junit.Assert.assertEquals("the model you trained in the Lab (d5c2a3)", Provenance.describeModel("winnow-lab·d5c2a3"))
    }
}
