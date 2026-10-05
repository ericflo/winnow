package com.ericflo.winnow.classifier.message

import com.ericflo.winnow.classifier.Choice
import com.ericflo.winnow.classifier.DecisionProvider
import com.ericflo.winnow.classifier.DecisionRequest
import com.ericflo.winnow.classifier.local.LocalPrediction
import com.ericflo.winnow.classifier.local.OnDeviceClassifier
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
) {

    /**
     * Whether [message] is decided on the phone and never reaches a provider: a sender rule, a
     * contact, someone the user has written to, a code (as [privacy] says), or a filtered phrase.
     * The same checks [classify] starts with, so a caller planning what to send agrees with it.
     */
    fun staysOnPhone(message: InboundMessage): Boolean {
        if (LocalRules.decide(message, privacy) != null) return true
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
        if (local != null && decideOnDeviceAbove != null && local.confidence >= decideOnDeviceAbove) return onDeviceVerdict(local, null)

        val eligible = providers.filter { it.descriptor.dataHandling in privacy.allowedDataHandling }
        if (eligible.isEmpty()) {
            if (providers.isEmpty()) return local?.let { onDeviceVerdict(it, null) } ?: heuristic(message, "No provider configured")
            return fallback(message, local, "No provider fits your privacy settings", contacted = false)
        }

        val request = buildRequest(message, privacy)
        val failures = mutableListOf<String>()
        for (provider in eligible) {
            val id = provider.descriptor.id
            val response = try {
                withTimeoutOrNull(timeoutMillis) { provider.decide(request) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "$id: ${e.message}"
                continue
            }
            val answer = response?.answers?.get(QUESTION_KEY)
            if (answer == null) {
                failures += if (response == null) "$id: timed out" else "$id: no category answer"
                continue
            }
            val distribution = answer.probabilities.mapNotNull { (k, p) -> Category.fromKey(k)?.let { it to p } }.toMap()
            val category = Category.fromKey(answer.top) ?: Category.SPAM
            return Verdict(
                category = category,
                confidence = answer.confidence,
                action = actions.resolve(category, answer.confidence, Origin.PROVIDER),
                source = VerdictSource.Provider(id, response.model),
                distribution = distribution,
                costUsd = response.usage.costUsd,
                providerContacted = true,
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
    )

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
        const val QUESTION_KEY = "category"

        val QUESTION = Choice(
            instructions = "This text message just arrived on the user's phone. What kind of message is it? " +
                "Judge by its content and sender. Scams often imitate legitimate transactional messages.",
            options = Category.entries.associate { it.key to it.rubric },
        )

        /** The exact state a provider sees: redacted text and coarse sender facts. */
        fun buildRequest(message: InboundMessage, privacy: PrivacyPolicy): DecisionRequest {
            val state = buildJsonObject {
                put("message", Redactor.redact(message.body, privacy.redaction))
                putJsonObject("sender") {
                    put("kind", SenderKind.of(message.sender).wire)
                    if (privacy.shareSenderAddress) put("address", message.sender)
                    put("in_contacts", message.senderInContacts)
                    put("user_has_messaged_sender", message.userHasMessagedSender)
                }
            }
            return DecisionRequest(state, mapOf(QUESTION_KEY to QUESTION))
        }
    }
}
