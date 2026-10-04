package com.ericflo.winnow.classifier.message

import com.ericflo.winnow.classifier.DataHandling
import com.ericflo.winnow.classifier.DecisionProvider
import com.ericflo.winnow.classifier.DecisionRequest
import com.ericflo.winnow.classifier.DecisionResponse
import com.ericflo.winnow.classifier.Distribution
import com.ericflo.winnow.classifier.ProviderDescriptor
import com.ericflo.winnow.classifier.ProviderException
import com.ericflo.winnow.classifier.local.OnDeviceClassifier
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeProvider(
    id: String = "fake",
    handling: DataHandling = DataHandling.REMOTE,
    private val answer: suspend (DecisionRequest) -> Map<String, Double>,
) : DecisionProvider {
    val seen = mutableListOf<DecisionRequest>()
    override val descriptor = ProviderDescriptor(id, id, handling)
    override suspend fun decide(request: DecisionRequest): DecisionResponse {
        seen += request
        val options = request.questions.getValue(MessageClassifier.QUESTION_KEY).options.keys
        return DecisionResponse(mapOf(MessageClassifier.QUESTION_KEY to Distribution.of(answer(request), options)), model = "fake-1")
    }
}

class MessageClassifierTest {
    private val stranger = InboundMessage(sender = "+15555550123", body = "Toll balance unpaid, pay at ezpass-help.top/pay?id=8812")

    @Test
    fun `provider verdict maps to category and configured action`() = runTest {
        val provider = FakeProvider { mapOf("phishing" to 0.95, "spam" to 0.05) }
        val verdict = MessageClassifier(listOf(provider)).classify(stranger)
        assertEquals(Category.PHISHING, verdict.category)
        assertEquals(Action.FILTER, verdict.action)
        assertEquals(VerdictSource.Provider("fake", "fake-1"), verdict.source)
    }

    @Test
    fun `low confidence softens the action one step`() = runTest {
        val provider = FakeProvider { mapOf("scam" to 0.55, "personal" to 0.45) }
        assertEquals(Action.SILENCE, MessageClassifier(listOf(provider)).classify(stranger).action)
    }

    @Test
    fun `contacts, prior conversations and codes never reach the provider`() = runTest {
        val provider = FakeProvider { mapOf("scam" to 1.0) }
        val classifier = MessageClassifier(listOf(provider))
        assertEquals(Action.ALLOW, classifier.classify(stranger.copy(senderInContacts = true)).action)
        assertEquals(Action.ALLOW, classifier.classify(stranger.copy(userHasMessagedSender = true)).action)
        val code = classifier.classify(InboundMessage("72975", "Your verification code is 482913. Don't share it."))
        assertEquals(Category.TRANSACTIONAL, code.category)
        assertTrue(provider.seen.isEmpty())
    }

    @Test
    fun `sender rules override everything without classifying`() = runTest {
        val classifier = MessageClassifier(listOf(FakeProvider { mapOf("personal" to 1.0) }))
        val filtered = classifier.classify(stranger.copy(senderInContacts = true, senderRule = SenderRule.ALWAYS_FILTER))
        assertEquals(Action.FILTER, filtered.action)
        assertNull(filtered.category)
    }

    @Test
    fun `provider sees redacted text and no sender address by default`() = runTest {
        val provider = FakeProvider { mapOf("scam" to 1.0) }
        MessageClassifier(listOf(provider)).classify(stranger)
        val state = provider.seen.single().state.jsonObject
        assertEquals("Toll balance unpaid, pay at ezpass-help.top/…", state["message"]!!.jsonPrimitive.content)
        val sender = state["sender"]!!.jsonObject
        assertEquals("phone_number", sender["kind"]!!.jsonPrimitive.content)
        assertFalse("address" in sender)
    }

    @Test
    fun `ZDR-only mode skips providers that retain data`() = runTest {
        val retains = FakeProvider("retains", DataHandling.REMOTE) { mapOf("personal" to 1.0) }
        val zdr = FakeProvider("zdr", DataHandling.REMOTE_ZERO_RETENTION) { mapOf("scam" to 1.0) }
        val privacy = PrivacyPolicy(allowedDataHandling = setOf(DataHandling.ON_DEVICE, DataHandling.REMOTE_ZERO_RETENTION))
        val verdict = MessageClassifier(listOf(retains, zdr), privacy).classify(stranger)
        assertEquals(VerdictSource.Provider("zdr", "fake-1"), verdict.source)
        assertTrue(retains.seen.isEmpty())
    }

    @Test
    fun `falls through failing and slow providers, then to a capped heuristic`() = runTest {
        val failing = FakeProvider("failing") { throw ProviderException("down", retryable = true) }
        val slow = FakeProvider("slow") { awaitCancellation() }
        val backup = FakeProvider("backup") { mapOf("marketing" to 0.9, "spam" to 0.1) }
        val classifier = MessageClassifier(listOf(failing, slow, backup), timeoutMillis = 1_000)
        assertEquals(VerdictSource.Provider("backup", "fake-1"), classifier.classify(stranger).source)

        val offline = MessageClassifier(listOf(failing), timeoutMillis = 1_000).classify(stranger)
        assertIs<VerdictSource.Heuristic>(offline.source)
        assertEquals(Category.PHISHING, offline.category)
        assertEquals(Action.SILENCE, offline.action, "the heuristic alone may silence but not filter")
    }

    private val model = OnDeviceClassifier()

    @Test
    fun `with no provider the on-device model decides, and says why`() = runTest {
        val verdict = MessageClassifier(emptyList(), onDevice = model).classify(stranger)
        val source = assertIs<VerdictSource.OnDevice>(verdict.source)
        assertNull(source.fallbackReason)
        assertTrue(source.reasons.isNotEmpty())
        assertEquals(Category.PHISHING, verdict.category)
        assertFalse(verdict.providerContacted)
    }

    @Test
    fun `a failing provider falls back to the model, not the keyword heuristic`() = runTest {
        val failing = FakeProvider("failing") { throw ProviderException("down", retryable = true) }
        val verdict = MessageClassifier(listOf(failing), onDevice = model).classify(stranger)
        val source = assertIs<VerdictSource.OnDevice>(verdict.source)
        assertTrue(source.fallbackReason!!.startsWith("Provider unavailable"))
        assertTrue(verdict.providerContacted)
        assertEquals(1, failing.seen.size)
    }

    @Test
    fun `deciding on the phone when sure skips the provider`() = runTest {
        val sure = FakeProvider("sure") { mapOf("personal" to 1.0) }
        val local = MessageClassifier(listOf(sure), onDevice = model, decideOnDeviceAbove = 0.0).classify(stranger)
        assertIs<VerdictSource.OnDevice>(local.source)
        assertFalse(local.providerContacted)
        assertTrue(sure.seen.isEmpty())

        val asked = MessageClassifier(listOf(sure), onDevice = model, decideOnDeviceAbove = 1.01).classify(stranger)
        assertEquals(VerdictSource.Provider("sure", "fake-1"), asked.source)
        assertTrue(asked.providerContacted)
    }

    @Test
    fun `the on-device model silences scams and phishing that have nothing to hook you with`() = runTest {
        val policy = ActionPolicy()
        assertEquals(Action.SILENCE, policy.resolve(Category.SCAM, 0.99, Origin.ON_DEVICE, hasHook = false))
        assertEquals(Action.SILENCE, policy.resolve(Category.PHISHING, 0.99, Origin.ON_DEVICE, hasHook = false))
        assertEquals(Action.FILTER, policy.resolve(Category.SCAM, 0.99, Origin.ON_DEVICE, hasHook = true))
        assertEquals(Action.FILTER, policy.resolve(Category.SPAM, 0.99, Origin.ON_DEVICE, hasHook = false))
        // Providers are judged as they are.
        assertEquals(Action.FILTER, policy.resolve(Category.SCAM, 0.99, Origin.PROVIDER, hasHook = false))
        // Not sure, and nothing to defraud with: it rings like any text ("sorry I missed your call").
        assertEquals(Action.ALLOW, policy.resolve(Category.SCAM, 0.6, Origin.ON_DEVICE, hasHook = false))
        assertEquals(Action.ALLOW, policy.resolve(Category.PHISHING, 0.84, Origin.ON_DEVICE, hasHook = false))
        // With a hook, an unsure one is still kept quiet.
        assertEquals(Action.SILENCE, policy.resolve(Category.SCAM, 0.6, Origin.ON_DEVICE, hasHook = true))

        val opener = MessageClassifier(emptyList(), onDevice = model).classify(InboundMessage("+14155550199", "Hi, is this David? This is Amy from yoga"))
        assertTrue(opener.action <= Action.SILENCE, "$opener")
    }

    @Test
    fun `the on-device model must be surer than a provider to filter`() {
        val policy = ActionPolicy()
        assertEquals(Action.FILTER, policy.resolve(Category.PHISHING, 0.8, Origin.PROVIDER))
        assertEquals(Action.SILENCE, policy.resolve(Category.PHISHING, 0.8, Origin.ON_DEVICE))
        assertEquals(Action.FILTER, policy.resolve(Category.PHISHING, 0.9, Origin.ON_DEVICE))
        assertEquals(Action.SILENCE, policy.resolve(Category.PHISHING, 0.99, Origin.HEURISTIC))
    }
}
