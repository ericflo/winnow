package com.ericflo.winnow.classifier.message

import com.ericflo.winnow.classifier.Choice
import com.ericflo.winnow.classifier.DecisionProvider
import com.ericflo.winnow.classifier.DecisionRequest
import com.ericflo.winnow.classifier.local.LocalPrediction
import com.ericflo.winnow.classifier.local.OnDeviceClassifier
import com.ericflo.winnow.classifier.local.SenderMemory
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decides what to do with an incoming message.
 *
 * 1. Local rules (sender rules, contacts, verification codes) decide on the phone, and then
 *    so do the user's [filteredPhrases]: after the rules, so a contact saying one isn't filtered.
 * 2. If [decideOnDeviceAbove] is set and the [onDevice] model is at least that sure, it decides.
 * 3. Otherwise each provider the [privacy] policy allows is tried in order, with the
 *    redacted message, until one answers within [timeoutMillis].
 * 4. If none answers (or there are none), the on-device model decides. Without one, the
 *    offline keyword heuristic does, capped so it can silence but not hide.
 *
 * Providers are interchangeable [DecisionProvider]s; nothing here knows which vendor is behind one.
 */
class MessageClassifier(
    private val providers: List<DecisionProvider>,
    private val privacy: PrivacyPolicy = PrivacyPolicy(),
    private val actions: ActionPolicy = ActionPolicy(),
    private val timeoutMillis: Long = 8_000,
    private val onDevice: OnDeviceClassifier? = null,
    private val decideOnDeviceAbove: Double? = null,
    private val filteredPhrases: FilteredPhrases = FilteredPhrases(emptyList()),
    /**
     * Texts the user labeled, by category, sent with each request so the provider sorts the way
     * the user does rather than by its own idea of the categories (see [question]). Redacted like
     * the message. Empty for ordinary classification; a backlog run the user confirmed sets it.
     */
    private val examples: Map<Category, List<String>> = emptyMap(),
    /**
     * Why no message may leave the phone right now, or null when they may (as [privacy] says).
     * Set when the app can't tell a contact from a stranger: a contact's text must never reach
     * a provider, so none does, and each is decided here with this as the reason.
     */
    private val keepOnPhone: String? = null,
) {

    /**
     * Whether [message] is decided on the phone and never reaches a provider: a sender rule, a
     * contact, someone the user has written to, a code (as [privacy] says), or a filtered phrase.
     * The same checks [classify] starts with, so a caller planning what to send agrees with it.
     */
    fun staysOnPhone(message: InboundMessage): Boolean {
        if (keepOnPhone != null || LocalRules.decide(message, privacy) != null) return true
        val stranger = !message.senderInContacts && !message.userHasMessagedSender && !LocalRules.looksLikeVerificationCode(message.body)
        return stranger && filteredPhrases.find(message.body) != null
    }

    suspend fun classify(message: InboundMessage): Verdict {
        LocalRules.decide(message, privacy)?.let { return it }
        // Strangers only, whatever the privacy switches say: never a contact, someone the user has
        // texted, or a verification code (the switches let those reach a provider, not a phrase).
        val stranger = !message.senderInContacts && !message.userHasMessagedSender && !LocalRules.looksLikeVerificationCode(message.body)
        if (stranger) filteredPhrases.find(message.body)?.let { phrase -> return Verdict.rule(null, Action.FILTER, FilteredPhrases.reason(phrase)) }

        // The model is a fallback as much as a first opinion, so a failure here must not stop classification.
        val local = onDevice?.let { runCatching { it.classify(message) }.getOrNull() }
        // The user's own labels of this sender, enough of them and all one way: theirs
        // outweigh any service's, so it isn't asked, and the text goes where their label sends
        // it, as their own label of it would (no model's doubt softens it).
        if (local != null && local.yourLabelsDecide) {
            return onDeviceVerdict(local, VerdictSource.OnDevice.YOUR_LABELS).copy(action = actions.forCategory(local.category))
        }
        if (local != null && decideOnDeviceAbove != null && local.confidence >= decideOnDeviceAbove) {
            // Only where a provider could have been asked is being sure a reason not to ask it.
            return onDeviceVerdict(local, VerdictSource.OnDevice.SURE.takeIf { providers.isNotEmpty() && keepOnPhone == null })
        }

        if (keepOnPhone != null && providers.isNotEmpty()) return fallback(message, local, keepOnPhone, contacted = false)
        val eligible = providers.filter { it.descriptor.dataHandling in privacy.allowedDataHandling }
        if (eligible.isEmpty()) {
            if (providers.isEmpty()) return local?.let { onDeviceVerdict(it, null) } ?: heuristic(message, "No provider configured")
            return fallback(message, local, "No provider fits your privacy settings", contacted = false)
        }

        val request = buildRequest(message, privacy, examples)
        val failures = mutableListOf<String>()
        for (provider in eligible) {
            val id = provider.descriptor.id
            val asked = System.nanoTime()
            val response = try {
                withTimeoutOrNull(timeoutMillis) { provider.decide(request) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An answer that came but can't be used: asking again at once gets the same one, and
                // is paid for again. Said so, so a run sets the text aside (see Pacer.troubleOf).
                val unusable = e is com.ericflo.winnow.classifier.ProviderException && !e.retryable && "HTTP " !in e.message.orEmpty()
                failures += if (unusable) "$id: $UNUSABLE_ANSWER ${e.message}" else "$id: ${e.message}"
                continue
            }
            val answer = response?.answers?.get(QUESTION_KEY)
            if (answer == null) {
                failures += if (response == null) "$id: timed out" else "$id: no category answer"
                continue
            }
            // The provider picks a fine-grained kind; its probabilities add up into the six.
            val distribution = Subcategories.aggregate(answer.probabilities)
            val category = distribution.maxByOrNull { it.value }?.key
                ?: Subcategories.of(answer.top)?.parent ?: Category.fromKey(answer.top) ?: Category.SPAM
            val confidence = distribution[category] ?: answer.confidence
            // The user has labeled several of this sender's texts, never this way, and the model,
            // leaning on those labels, says one of theirs: their labels outweigh the service's answer.
            // It was asked and paid, and what it said is kept. Not for someone the user texts with,
            // who can send any kind (see SenderMemory.decisive).
            if (local != null && !message.userHasMessagedSender && local.senderLabelsTotal >= SenderMemory.DECISIVE_AT_LEAST &&
                category !in local.senderCategories && local.category in local.senderCategories
            ) {
                return onDeviceVerdict(local, "${VerdictSource.OnDevice.OVER_SERVICE} (it said ${category.label.lowercase()})").copy(
                    providerContacted = true,
                    costUsd = response.usage.costUsd,
                    latencyMillis = (System.nanoTime() - asked) / 1_000_000,
                    promptExamples = examples.values.sumOf { it.size },
                    serviceOpinion = ModelOpinion(category, confidence, response.model ?: id),
                )
            }
            return Verdict(
                category = category,
                confidence = confidence,
                action = actions.resolve(category, confidence, Origin.PROVIDER),
                subcategory = Subcategories.of(answer.top)?.takeIf { it.parent == category }?.key,
                source = VerdictSource.Provider(id, response.model),
                distribution = distribution,
                costUsd = response.usage.costUsd,
                providerContacted = true,
                onDevice = local?.opinion(),
                latencyMillis = (System.nanoTime() - asked) / 1_000_000,
                promptExamples = examples.values.sumOf { it.size },
            )
        }
        return fallback(message, local, "Provider unavailable (${failures.joinToString("; ")})", contacted = true)
    }

    private fun fallback(message: InboundMessage, local: LocalPrediction?, reason: String, contacted: Boolean): Verdict =
        (local?.let { onDeviceVerdict(it, reason) } ?: heuristic(message, reason)).copy(providerContacted = contacted)

    private fun onDeviceVerdict(p: LocalPrediction, fallbackReason: String?) = Verdict(
        category = p.category,
        confidence = p.confidence,
        action = actions.resolve(p.category, p.confidence, Origin.ON_DEVICE, p.hasHook),
        source = VerdictSource.OnDevice(p.model, p.reasons, fallbackReason),
        distribution = p.distribution,
        onDevice = p.opinion(),
    )

    private fun LocalPrediction.opinion() = ModelOpinion(category, confidence, model)

    private fun heuristic(message: InboundMessage, reason: String): Verdict {
        val distribution = HeuristicScorer.score(message)
        val (category, confidence) = distribution.maxBy { it.value }
        return Verdict(
            category = category,
            confidence = confidence,
            action = actions.resolve(category, confidence, Origin.HEURISTIC),
            source = VerdictSource.Heuristic(reason),
            distribution = distribution,
        )
    }

    companion object {
        /** In a failure's reason: the service answered, but not in a way Winnow can use. */
        const val UNUSABLE_ANSWER = "unusable answer:"

        const val QUESTION_KEY = "category"

        /** The six categories, as the question explains them: each option counts as one of these. */
        private val CATEGORIES = Category.entries.joinToString(" ") { "${it.label}: ${it.rubric}" }

        val QUESTION = Choice(
            instructions = "This text message just arrived on the user's phone. What kind of message is it? " +
                "Judge by its content and sender. Scams often imitate legitimate transactional messages. Pick the most " +
                "specific option; each counts as one of six categories, which are: $CATEGORIES",
            options = Subcategories.options(),
        )

        /** Most of an example's words, enough to show what it is; examples are many, and each one costs. */
        const val EXAMPLE_CHARS = 160

        /**
         * The question, with the user's own labeled [examples] (already chosen as fit to send)
         * under each category, redacted by [privacy]: the provider is asked to follow how the
         * user sorts. Without examples, the plain [QUESTION].
         */
        fun question(examples: Map<Category, List<String>>, privacy: PrivacyPolicy): Choice {
            if (examples.values.all { it.isEmpty() }) return QUESTION
            val mine = Category.entries.mapNotNull { c ->
                val texts = examples[c].orEmpty().map { Redactor.redact(it, privacy.redaction).replace('\n', ' ').take(EXAMPLE_CHARS) }
                if (texts.isEmpty()) null else "${c.label}: " + texts.joinToString("; ") { "\u201c$it\u201d" }
            }
            return Choice(
                instructions = QUESTION.instructions + " The user has labeled some of their own texts; sort the way they do. " +
                    "The user labels texts like these as follows. " + mine.joinToString(". ") + ".",
                options = QUESTION.options,
            )
        }

        /** The exact state a provider sees: redacted text and coarse sender facts. */
        fun buildRequest(message: InboundMessage, privacy: PrivacyPolicy, examples: Map<Category, List<String>> = emptyMap()): DecisionRequest {
            val state = buildJsonObject {
                put("message", Redactor.redact(message.body, privacy.redaction))
                putJsonObject("sender") {
                    put("kind", SenderKind.of(message.sender).wire)
                    if (privacy.shareSenderAddress) put("address", message.sender)
                    put("in_contacts", message.senderInContacts)
                    put("user_has_messaged_sender", message.userHasMessagedSender)
                }
            }
            return DecisionRequest(state, mapOf(QUESTION_KEY to question(examples, privacy)))
        }
    }
}
