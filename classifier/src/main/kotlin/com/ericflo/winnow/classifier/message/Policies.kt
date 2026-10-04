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

/** Categories the on-device model only filters when the text has a hook (see [ActionPolicy.resolve]). */
val NEEDS_HOOK = setOf(Category.SCAM, Category.PHISHING)

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

    /**
     * @param hasHook for on-device verdicts: whether the text carries anything a fraudster could
     *   use. A hookless "scam" reads like a real person on a new number, and a hookless
     *   "phishing" text has nothing to phish with, so the model silences those instead of hiding
     *   them, and when it isn't sure, lets them through: in cross-validation that took the real
     *   texts (personal and transactional) losing their notification from 5.6% to 1.4%, for 2.7
     *   points fewer unwanted texts kept quiet, all of them without a link, money or a number.
     */
    fun resolve(category: Category, confidence: Double, origin: Origin = Origin.PROVIDER, hasHook: Boolean = true): Action {
        var action = forCategory(category)
        val unsure = confidence < if (origin == Origin.ON_DEVICE) onDeviceMinConfidence else minConfidence
        if (unsure) action = action.softened()
        if (origin == Origin.HEURISTIC && action > heuristicCeiling) action = heuristicCeiling
        if (origin == Origin.ON_DEVICE && category in NEEDS_HOOK && !hasHook) action = if (unsure) Action.ALLOW else minOf(action, Action.SILENCE)
        return action
    }
}
