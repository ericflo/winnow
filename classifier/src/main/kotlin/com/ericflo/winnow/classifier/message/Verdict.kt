package com.ericflo.winnow.classifier.message

data class InboundMessage(
    /** The address as received: an E.164 number, a short code, or an alphanumeric sender id. */
    val sender: String,
    val body: String,
    val senderInContacts: Boolean = false,
    /** The user has sent this sender a message before. */
    val userHasMessagedSender: Boolean = false,
    val senderRule: SenderRule? = null,
)

data class Verdict(
    /** Null when a sender rule decided without classifying. */
    val category: Category?,
    /** Probability of [category]; 1.0 for rules. */
    val confidence: Double,
    val action: Action,
    val source: VerdictSource,
    val distribution: Map<Category, Double> = emptyMap(),
    val costUsd: Double = 0.0,
) {
    companion object {
        internal fun rule(category: Category?, action: Action, reason: String) =
            Verdict(category, 1.0, action, VerdictSource.Rule(reason))
    }
}

sealed interface VerdictSource {
    /** A local rule decided. The message never left the phone. */
    data class Rule(val reason: String) : VerdictSource

    /** A [com.ericflo.winnow.classifier.DecisionProvider] answered. */
    data class Provider(val providerId: String, val model: String?) : VerdictSource

    /** No provider could answer, so the offline keyword heuristic decided. */
    data class Heuristic(val reason: String) : VerdictSource
}
