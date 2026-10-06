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
        val provider = FakeProvider { mapOf("toll_phishing" to 0.95, "other_junk" to 0.05) }
        val verdict = MessageClassifier(listOf(provider)).classify(stranger)
        assertEquals(Category.SPAM, verdict.category)
        assertEquals(Action.FILTER, verdict.action)
        assertEquals(VerdictSource.Provider("fake", "fake-1"), verdict.source)
    }

    @Test
    fun `low confidence softens the action one step`() = runTest {
        val provider = FakeProvider { mapOf("wrong_number_opener" to 0.55, "friend_chat" to 0.45) }
        assertEquals(Action.SILENCE, MessageClassifier(listOf(provider)).classify(stranger).action)
    }

    @Test
    fun `contacts, prior conversations and codes never reach the provider`() = runTest {
        val provider = FakeProvider { mapOf("wrong_number_opener" to 1.0) }
        val classifier = MessageClassifier(listOf(provider))
        assertEquals(Action.ALLOW, classifier.classify(stranger.copy(senderInContacts = true)).action)
        assertEquals(Action.ALLOW, classifier.classify(stranger.copy(userHasMessagedSender = true)).action)
        val code = classifier.classify(InboundMessage("72975", "Your verification code is 482913. Don't share it."))
        assertEquals(Category.TRANSACTIONAL, code.category)
        assertTrue(provider.seen.isEmpty())
    }

    @Test
    fun `someone from an rcs chat never reaches the provider, a business on rcs can`() = runTest {
        val provider = FakeProvider { mapOf("friend_chat" to 1.0) }
        val classifier = MessageClassifier(listOf(provider))
        val family = InboundMessage("3f9a0c1d2e4b5a69@rcs.google.com", "Dinner at Grandma's Sunday?")
        assertTrue(classifier.staysOnPhone(family))
        val verdict = classifier.classify(family)
        assertEquals(VerdictSource.Rule(LocalRules.RCS_CHAT), verdict.source)
        assertEquals(Action.ALLOW, verdict.action)
        assertEquals(0, provider.seen.size)
        // A business's RCS messages (RCS Business Messaging) are sorted like any other.
        assertFalse(classifier.staysOnPhone(InboundMessage("acme@rbm.goog", "20% off today only")))
        classifier.classify(InboundMessage("acme@rbm.goog", "20% off today only"))
        assertEquals(1, provider.seen.size)
        // An ordinary email address isn't an RCS id.
        assertFalse(RcsIds.isRcs("someone@rcsmail.com"))
        // Known conversations may be sent, but contacts mustn't be: an RCS person may be either, so stays.
        val knownOnly = MessageClassifier(listOf(provider), PrivacyPolicy(classifyKnownConversations = true))
        assertTrue(knownOnly.staysOnPhone(family))
        // Both allowed: then they may go.
        assertFalse(MessageClassifier(listOf(provider), PrivacyPolicy(classifyKnownConversations = true, classifyContacts = true)).staysOnPhone(family))
    }

    @Test
    fun `sender rules override everything without classifying`() = runTest {
        val classifier = MessageClassifier(listOf(FakeProvider { mapOf("friend_chat" to 1.0) }))
        val filtered = classifier.classify(stranger.copy(senderInContacts = true, senderRule = SenderRule.ALWAYS_FILTER))
        assertEquals(Action.FILTER, filtered.action)
        assertNull(filtered.category)
    }

    @Test
    fun `what stays on the phone is exactly what classify keeps from the provider`() = runTest {
        val provider = FakeProvider { mapOf("wrong_number_opener" to 1.0) }
        val classifier = MessageClassifier(listOf(provider), filteredPhrases = FilteredPhrases(listOf("toll")))
        val kept = listOf(
            stranger.copy(senderRule = SenderRule.ALWAYS_ALLOW),
            stranger.copy(senderRule = SenderRule.ALWAYS_FILTER),
            stranger.copy(senderInContacts = true),
            stranger.copy(userHasMessagedSender = true),
            InboundMessage("72975", "Your verification code is 482913. Don't share it."),
            stranger, // has the filtered word "toll"
        )
        kept.forEach { m ->
            assertTrue(classifier.staysOnPhone(m), "$m")
            classifier.classify(m)
        }
        assertTrue(provider.seen.isEmpty(), "none of them reached the provider")
        val sent = InboundMessage("+15555550124", "Hi, is this Dana?")
        assertFalse(classifier.staysOnPhone(sent))
        classifier.classify(sent)
        assertEquals(1, provider.seen.size)
    }

    @Test
    fun `the user's examples go with the question, redacted and trimmed`() = runTest {
        val provider = FakeProvider { mapOf("other_junk" to 1.0) }
        val examples = mapOf(
            Category.SPAM to listOf("Win a FREE cruise! Call 8885550123 now", "x".repeat(400)),
            Category.REMINDER to listOf("please test the heater before winter"),
        )
        MessageClassifier(listOf(provider), examples = examples).classify(stranger)
        val question = provider.seen.single().questions.getValue(MessageClassifier.QUESTION_KEY)
        val text = question.instructions
        assertTrue("Spam: \u201cWin a FREE cruise! Call ##########" in text && "8885550123" !in text, text)
        assertTrue("x".repeat(MessageClassifier.EXAMPLE_CHARS) in text && "x".repeat(MessageClassifier.EXAMPLE_CHARS + 1) !in text, text)
        assertTrue("Reminder: \u201cplease test the heater before winter\u201d" in text, text)
        assertEquals(MessageClassifier.QUESTION.options, question.options)
        // Without examples, the question is exactly the plain one.
        MessageClassifier(listOf(provider)).classify(stranger)
        assertEquals(MessageClassifier.QUESTION, provider.seen.last().questions.getValue(MessageClassifier.QUESTION_KEY))
    }

    @Test
    fun `fine-grained answers add up into the six`() = runTest {
        val provider = FakeProvider { mapOf("toll_phishing" to 0.4, "bank_phishing" to 0.3, "friend_chat" to 0.3) }
        val verdict = MessageClassifier(listOf(provider)).classify(stranger)
        assertEquals(Category.SPAM, verdict.category)
        assertEquals(0.7, verdict.confidence, 1e-9)
        assertEquals("toll_phishing", verdict.subcategory)
        assertEquals(0.3, verdict.distribution.getValue(Category.PERSONAL), 1e-9)
        // Every option the question offers is one of the six's.
        assertTrue(MessageClassifier.QUESTION.options.keys.all { Subcategories.of(it) != null })
        assertEquals(Category.entries.toSet(), Subcategories.all.map { it.parent }.toSet())
    }

    @Test
    fun `provider sees redacted text and no sender address by default`() = runTest {
        val provider = FakeProvider { mapOf("wrong_number_opener" to 1.0) }
        MessageClassifier(listOf(provider)).classify(stranger)
        val state = provider.seen.single().state.jsonObject
        assertEquals("Toll balance unpaid, pay at ezpass-help.top/…", state["message"]!!.jsonPrimitive.content)
        val sender = state["sender"]!!.jsonObject
        assertEquals("phone_number", sender["kind"]!!.jsonPrimitive.content)
        assertFalse("address" in sender)
    }

    @Test
    fun `ZDR-only mode skips providers that retain data`() = runTest {
        val retains = FakeProvider("retains", DataHandling.REMOTE) { mapOf("friend_chat" to 1.0) }
        val zdr = FakeProvider("zdr", DataHandling.REMOTE_ZERO_RETENTION) { mapOf("wrong_number_opener" to 1.0) }
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
        assertEquals(Category.SPAM, offline.category)
        assertEquals(Action.SILENCE, offline.action, "the heuristic alone may silence but not filter")
    }

    private val model = OnDeviceClassifier()

    @Test
    fun `with no provider the on-device model decides, and says why`() = runTest {
        val verdict = MessageClassifier(emptyList(), onDevice = model).classify(stranger)
        val source = assertIs<VerdictSource.OnDevice>(verdict.source)
        assertNull(source.fallbackReason)
        assertTrue(source.reasons.isNotEmpty())
        assertEquals(Category.SPAM, verdict.category)
        assertFalse(verdict.providerContacted)
    }

    @Test
    fun `when nothing may leave the phone, no text reaches the provider and each says why`() = runTest {
        val provider = FakeProvider { mapOf("friend_chat" to 1.0) }
        val held = MessageClassifier(listOf(provider), onDevice = model, keepOnPhone = "Can't see your contacts")
        assertTrue(held.staysOnPhone(stranger))
        val verdict = held.classify(stranger)
        assertEquals(0, provider.seen.size)
        assertEquals("Can't see your contacts", assertIs<VerdictSource.OnDevice>(verdict.source).fallbackReason)
        assertFalse(verdict.providerContacted)
        // The model still sorts it: spam is still caught on the phone.
        assertEquals(Category.SPAM, verdict.category)
        // Without one, the same text goes to the provider as before.
        MessageClassifier(listOf(provider), onDevice = model).classify(stranger)
        assertEquals(1, provider.seen.size)
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
        val sure = FakeProvider("sure") { mapOf("friend_chat" to 1.0) }
        val local = MessageClassifier(listOf(sure), onDevice = model, decideOnDeviceAbove = 0.0).classify(stranger)
        assertIs<VerdictSource.OnDevice>(local.source)
        assertFalse(local.providerContacted)
        assertTrue(sure.seen.isEmpty())

        val asked = MessageClassifier(listOf(sure), onDevice = model, decideOnDeviceAbove = 1.01).classify(stranger)
        assertEquals(VerdictSource.Provider("sure", "fake-1"), asked.source)
        assertTrue(asked.providerContacted)
    }

    @Test
    fun `every verdict says what the on-device model thought, and why it decided when it did`() = runTest {
        val provider = FakeProvider { mapOf("friend_chat" to 1.0) }
        // The provider decided: the model's own opinion is kept beside its answer, with how long the answer took.
        val asked = MessageClassifier(listOf(provider), onDevice = model).classify(stranger)
        assertEquals(Category.PERSONAL, asked.category)
        val opinion = asked.onDevice!!
        assertEquals(Category.SPAM, opinion.category)
        assertEquals(model.version, opinion.model)
        assertTrue(asked.latencyMillis!! >= 0)
        assertEquals(0, asked.promptExamples)
        // Sure enough not to ask: said so, where there was a provider to ask.
        val sure = MessageClassifier(listOf(provider), onDevice = model, decideOnDeviceAbove = 0.0).classify(stranger)
        assertEquals(VerdictSource.OnDevice.SURE, assertIs<VerdictSource.OnDevice>(sure.source).fallbackReason)
        assertEquals(sure.category, sure.onDevice!!.category)
        // With nothing to ask, being sure is no reason: the model is simply the one that decides.
        val alone = MessageClassifier(emptyList(), onDevice = model, decideOnDeviceAbove = 0.0).classify(stranger)
        assertNull(assertIs<VerdictSource.OnDevice>(alone.source).fallbackReason)
        // A rule decides before any model is asked.
        assertNull(MessageClassifier(listOf(provider), onDevice = model).classify(stranger.copy(senderInContacts = true)).onDevice)
        // The examples sent with a question are counted.
        val examples = mapOf(Category.SPAM to listOf("Win a prize now", "Unpaid toll"), Category.PERSONAL to listOf("Dinner at 7?"))
        assertEquals(3, MessageClassifier(listOf(provider), onDevice = model, examples = examples).classify(stranger).promptExamples)
    }

    @Test
    fun `a taught model names its fit in what it decides`() {
        assertEquals(OnDeviceClassifier.MODEL_NAME, model.version)
        val taught = model.withAdjustments(model.adjustments, fit = "3fa2c1")
        assertEquals("${OnDeviceClassifier.MODEL_NAME}·3fa2c1", taught.version)
        assertEquals(taught.version, taught.classify(stranger).model)
    }

    @Test
    fun `the on-device model silences spam that has nothing to hook you with`() = runTest {
        val policy = ActionPolicy()
        // Sure, but no link, money or number: silenced, never hidden.
        assertEquals(Action.SILENCE, policy.resolve(Category.SPAM, 0.99, Origin.ON_DEVICE, hasHook = false))
        assertEquals(Action.FILTER, policy.resolve(Category.SPAM, 0.99, Origin.ON_DEVICE, hasHook = true))
        // Other unwanted categories don't need a hook.
        assertEquals(Action.FILTER, policy.resolve(Category.POLITICAL, 0.99, Origin.ON_DEVICE, hasHook = false))
        // Providers are judged as they are.
        assertEquals(Action.FILTER, policy.resolve(Category.SPAM, 0.99, Origin.PROVIDER, hasHook = false))
        // Quite unsure, and nothing to defraud with: it rings like any text ("sorry I missed your call").
        assertEquals(Action.ALLOW, policy.resolve(Category.SPAM, 0.55, Origin.ON_DEVICE, hasHook = false))
        // Unsure, but not that unsure: kept quiet.
        assertEquals(Action.SILENCE, policy.resolve(Category.SPAM, 0.7, Origin.ON_DEVICE, hasHook = false))
        // With a hook, an unsure one is still kept quiet.
        assertEquals(Action.SILENCE, policy.resolve(Category.SPAM, 0.6, Origin.ON_DEVICE, hasHook = true))

        val opener = MessageClassifier(emptyList(), onDevice = model).classify(InboundMessage("+14155550199", "Hi, is this David? This is Amy from yoga"))
        assertTrue(opener.action <= Action.SILENCE, "$opener")
    }

    @Test
    fun `the on-device model must be surer than a provider to filter`() {
        val policy = ActionPolicy()
        assertEquals(Action.FILTER, policy.resolve(Category.SPAM, 0.8, Origin.PROVIDER))
        assertEquals(Action.SILENCE, policy.resolve(Category.SPAM, 0.8, Origin.ON_DEVICE))
        assertEquals(Action.FILTER, policy.resolve(Category.SPAM, 0.9, Origin.ON_DEVICE))
        assertEquals(Action.SILENCE, policy.resolve(Category.SPAM, 0.99, Origin.HEURISTIC))
    }

    @Test
    fun `the user's labels of a sender decide before any service is asked`() = runTest {
        val provider = FakeProvider { mapOf("friend_chat" to 1.0) }
        val classes = com.ericflo.winnow.classifier.local.LocalModel.bundled.classes
        val memory = com.ericflo.winnow.classifier.local.SenderMemory.of(List(4) { stranger.sender to classes.indexOf(Category.TRANSACTIONAL.key) }, classes)
        val classifier = MessageClassifier(listOf(provider), onDevice = OnDeviceClassifier().withMemory(memory))
        val verdict = classifier.classify(stranger)
        assertEquals(Category.TRANSACTIONAL, verdict.category)
        assertTrue(provider.seen.isEmpty(), "the service wasn't asked")
        val source = assertIs<VerdictSource.OnDevice>(verdict.source)
        assertEquals(VerdictSource.OnDevice.YOUR_LABELS, source.fallbackReason)
        // Another sender still goes to the service.
        classifier.classify(stranger.copy(sender = "+15555550199"))
        assertEquals(1, provider.seen.size)
    }

    @Test
    fun `a service's answer the user has never given a sender they've labeled is outweighed, and kept`() = runTest {
        // A pharmacy labeled both ways (so its labels don't decide), and never personal.
        val pharmacy = InboundMessage(sender = "+12395550160", body = "Your prescription is ready for pickup at the Main St pharmacy")
        val classes = com.ericflo.winnow.classifier.local.LocalModel.bundled.classes
        val labels = List(3) { pharmacy.sender to classes.indexOf(Category.TRANSACTIONAL.key) } + listOf(pharmacy.sender to classes.indexOf(Category.REMINDER.key))
        val memory = com.ericflo.winnow.classifier.local.SenderMemory.of(labels, classes)
        val onDevice = OnDeviceClassifier().withMemory(memory)
        val local = onDevice.classify(pharmacy)
        assertTrue(local.category in setOf(Category.TRANSACTIONAL, Category.REMINDER), "${local.category}")
        // The service calls it personal: asked and paid, but outweighed.
        val outweighed = MessageClassifier(listOf(FakeProvider { mapOf("friend_chat" to 1.0) }), onDevice = onDevice).classify(pharmacy)
        assertEquals(local.category, outweighed.category)
        val source = assertIs<VerdictSource.OnDevice>(outweighed.source)
        assertEquals("${VerdictSource.OnDevice.OVER_SERVICE} (it said personal)", source.fallbackReason)
        assertTrue(outweighed.providerContacted)
        assertTrue(outweighed.decidedByYourLabels)
        assertEquals(Category.PERSONAL, outweighed.serviceOpinion?.category)
        // One the user has given them: the service decides, as ever.
        val theirs = MessageClassifier(listOf(FakeProvider { mapOf("school_notice" to 1.0) }), onDevice = onDevice).classify(pharmacy)
        assertEquals(Category.REMINDER, theirs.category)
        assertIs<VerdictSource.Provider>(theirs.source)
        assertFalse(theirs.decidedByYourLabels)
        // Someone the user texts with can send any kind: the service decides.
        val known = PrivacyPolicy(classifyKnownConversations = true)
        assertIs<VerdictSource.Provider>(MessageClassifier(listOf(FakeProvider { mapOf("friend_chat" to 1.0) }), known, onDevice = onDevice).classify(pharmacy.copy(userHasMessagedSender = true)).source)
        // Too few labels of a sender to say what they send: the service decides.
        val few = OnDeviceClassifier().withMemory(com.ericflo.winnow.classifier.local.SenderMemory.of(labels.take(2), classes))
        assertIs<VerdictSource.Provider>(MessageClassifier(listOf(FakeProvider { mapOf("friend_chat" to 1.0) }), onDevice = few).classify(pharmacy).source)
    }

    @Test
    fun `a text the user's labels decide goes where their label sends it, service or none`() = runTest {
        // A hookless opener from a number the user has labeled spam three times: the model alone,
        // unsure and with no hook, would only silence it, or let it through.
        val opener = InboundMessage(sender = "+12395550171", body = "Hey are you around this weekend?")
        val classes = com.ericflo.winnow.classifier.local.LocalModel.bundled.classes
        val memory = com.ericflo.winnow.classifier.local.SenderMemory.of(List(3) { opener.sender to classes.indexOf(Category.SPAM.key) }, classes)
        val onDevice = OnDeviceClassifier().withMemory(memory)
        val withService = MessageClassifier(listOf(FakeProvider { mapOf("friend_chat" to 1.0) }), onDevice = onDevice).classify(opener)
        assertEquals(Category.SPAM, withService.category)
        assertEquals(Action.FILTER, withService.action)
        assertTrue(withService.decidedByYourLabels)
        // No service set up: still their labels that decided, and said so.
        val alone = MessageClassifier(emptyList(), onDevice = onDevice).classify(opener)
        assertEquals(Action.FILTER, alone.action)
        assertEquals(VerdictSource.OnDevice.YOUR_LABELS, assertIs<VerdictSource.OnDevice>(alone.source).fallbackReason)
    }

    @Test
    fun `an answer that came but can't be used is said so, apart from a service that's down`() = runTest {
        val unusable = object : DecisionProvider {
            override val descriptor = ProviderDescriptor("broken", "broken", DataHandling.REMOTE)
            override suspend fun decide(request: DecisionRequest): DecisionResponse =
                throw com.ericflo.winnow.classifier.ProviderException("response has no answers: {}", retryable = false)
        }
        val down = object : DecisionProvider {
            override val descriptor = ProviderDescriptor("down", "down", DataHandling.REMOTE)
            override suspend fun decide(request: DecisionRequest): DecisionResponse =
                throw com.ericflo.winnow.classifier.ProviderException("down: timeout", retryable = true)
        }
        val said = MessageClassifier(listOf(unusable)).classify(stranger)
        assertTrue(said.source.toString().contains(MessageClassifier.UNUSABLE_ANSWER), said.source.toString())
        val notSaid = MessageClassifier(listOf(down)).classify(stranger)
        assertFalse(notSaid.source.toString().contains(MessageClassifier.UNUSABLE_ANSWER))
    }
}
