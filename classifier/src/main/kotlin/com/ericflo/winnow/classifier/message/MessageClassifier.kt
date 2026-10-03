package com.ericflo.winnow.classifier.message

import com.ericflo.winnow.classifier.Choice
import com.ericflo.winnow.classifier.DecisionProvider
import com.ericflo.winnow.classifier.DecisionRequest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decides what to do with an incoming message.
 *
 * 1. Local rules (sender rules, contacts, verification codes) decide on the phone.
 * 2. Otherwise each provider the [privacy] policy allows is tried in order, with the
 *    redacted message, until one answers within [timeoutMillis].
 * 3. If none answers, the offline heuristic decides, capped so it can silence but not hide.
 *
 * Providers are interchangeable [DecisionProvider]s; nothing here knows which vendor is behind one.
 */
class MessageClassifier(
    private val providers: List<DecisionProvider>,
    private val privacy: PrivacyPolicy = PrivacyPolicy(),
    private val actions: ActionPolicy = ActionPolicy(),
    private val timeoutMillis: Long = 8_000,
) {

    suspend fun classify(message: InboundMessage): Verdict {
        LocalRules.decide(message, privacy)?.let { return it }

        val eligible = providers.filter { it.descriptor.dataHandling in privacy.allowedDataHandling }
        if (eligible.isEmpty()) {
            return heuristic(message, if (providers.isEmpty()) "No provider configured" else "No provider fits your privacy settings")
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
                action = actions.resolve(category, answer.confidence, fromHeuristic = false),
                source = VerdictSource.Provider(id, response.model),
                distribution = distribution,
                costUsd = response.usage.costUsd,
            )
        }
        return heuristic(message, "Provider unavailable (${failures.joinToString("; ")})")
    }

    private fun heuristic(message: InboundMessage, reason: String): Verdict {
        val distribution = HeuristicScorer.score(message)
        val (category, confidence) = distribution.maxBy { it.value }
        return Verdict(
            category = category,
            confidence = confidence,
            action = actions.resolve(category, confidence, fromHeuristic = true),
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
