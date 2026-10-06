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
    /** A provider was sent the (redacted) message, whether or not it answered. */
    val providerContacted: Boolean = false,
    /** The provider's fine-grained answer ([Subcategories]), when it gave one within [category]. */
    val subcategory: String? = null,
    /**
     * What the on-device model thought of the message, whoever decided: the same as the verdict
     * when it decided, its own opinion beside a provider's answer otherwise. Null when nothing
     * asked it (a rule decided first) or there's no model.
     */
    val onDevice: ModelOpinion? = null,
    /** How long the provider took to answer, when one decided. */
    val latencyMillis: Long? = null,
    /** The user's labeled texts sent with the question as examples of how they sort (see MessageClassifier.question). */
    val promptExamples: Int = 0,
) {
    companion object {
        internal fun rule(category: Category?, action: Action, reason: String) =
            Verdict(category, 1.0, action, VerdictSource.Rule(reason))
    }
}

/** The on-device model's opinion of a message: its likeliest category, how sure, and which model (see OnDeviceClassifier.version). */
data class ModelOpinion(val category: Category, val confidence: Double, val model: String)

sealed interface VerdictSource {
    /** A local rule decided. The message never left the phone. */
    data class Rule(val reason: String) : VerdictSource

    /** A [com.ericflo.winnow.classifier.DecisionProvider] answered. */
    data class Provider(val providerId: String, val model: String?) : VerdictSource

    /**
     * Winnow's on-device model decided. [reasons] are what it went on ("a .vip link",
     * "“unpaid toll”"); [fallbackReason] says why no provider decided instead, if one was meant to:
     * [SURE] when the model was sure enough not to ask (see MessageClassifier's decideOnDeviceAbove).
     */
    data class OnDevice(val model: String, val reasons: List<String>, val fallbackReason: String? = null) : VerdictSource {
        companion object {
            const val SURE = "Sure enough to decide without asking"
            /** The user's own labels of this sender decided it: theirs outweigh any service's (see SenderMemory). */
            const val YOUR_LABELS = "Your labels of this sender decided it"
        }
    }

    /** No provider or model could answer, so the offline keyword heuristic decided. */
    data class Heuristic(val reason: String) : VerdictSource
}
