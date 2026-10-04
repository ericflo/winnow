package com.ericflo.winnow.classifier.message

import com.ericflo.winnow.classifier.DataHandling

/** What may leave the phone, and in what form. Defaults are the conservative choice. */
data class PrivacyPolicy(
    /** Send messages from saved contacts to the provider. Off: contacts are always allowed locally. */
    val classifyContacts: Boolean = false,
    /** Send messages from people the user has texted before. */
    val classifyKnownConversations: Boolean = false,
    /** Send messages that look like verification codes. Off: they stay on the phone as transactional. */
    val classifyVerificationCodes: Boolean = false,
    /** Include the sender's address. Off: the provider only learns the kind of sender. */
    val shareSenderAddress: Boolean = false,
    val redaction: RedactionPolicy = RedactionPolicy(),
    /** Providers outside this set are skipped. `setOf(ON_DEVICE, REMOTE_ZERO_RETENTION)` is ZDR-only mode. */
    val allowedDataHandling: Set<DataHandling> = DataHandling.entries.toSet(),
)

data class RedactionPolicy(
    /** Replace runs of 4+ digits (codes, account and phone numbers) with `#`, keeping their length. */
    val maskDigitRuns: Boolean = true,
    val maskEmails: Boolean = true,
    /** Keep a link's host, which carries the phishing signal, and drop its path and query. */
    val stripUrlPaths: Boolean = true,
)

/** Who classified a message, which decides how much benefit of the doubt the sender gets. */
enum class Origin { PROVIDER, ON_DEVICE, HEURISTIC }

data class ActionPolicy(
    val byCategory: Map<Category, Action> = Category.entries.associateWith { it.defaultAction },
    /** Below this confidence, a provider verdict's action is softened one step (FILTER → SILENCE → ALLOW). */
    val minConfidence: Double = 0.7,
    /** The same for the on-device model, which is smaller than a provider's, so it must be surer. */
    val onDeviceMinConfidence: Double = 0.85,
    /** The most severe action the offline heuristic may take on its own. */
    val heuristicCeiling: Action = Action.SILENCE,
) {
    fun forCategory(category: Category): Action = byCategory[category] ?: category.defaultAction

    fun resolve(category: Category, confidence: Double, origin: Origin = Origin.PROVIDER): Action {
        var action = forCategory(category)
        if (confidence < if (origin == Origin.ON_DEVICE) onDeviceMinConfidence else minConfidence) action = action.softened()
        if (origin == Origin.HEURISTIC && action > heuristicCeiling) action = heuristicCeiling
        return action
    }
}
