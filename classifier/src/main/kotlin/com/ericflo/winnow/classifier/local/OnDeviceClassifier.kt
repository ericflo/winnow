package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage

/** What the on-device model thinks of one message, and the features that convinced it. */
data class LocalPrediction(
    val category: Category,
    val confidence: Double,
    val distribution: Map<Category, Double>,
    /** Human-readable reasons, strongest first: "a link to an unusual domain", "“toll”". */
    val reasons: List<String>,
    val model: String,
    /** The message has something a fraudster could use: see [Featurizer.hasHook]. */
    val hasHook: Boolean = true,
)

/**
 * Classifies on the phone with [LocalModel]. It sees the whole message, unredacted, because
 * nothing leaves the device.
 */
class OnDeviceClassifier(
    val model: LocalModel = LocalModel.bundled,
    /** What the user's corrections taught it, on top of [model]. */
    val adjustments: Adjustments = Adjustments.NONE,
    val name: String = MODEL_NAME,
    /**
     * Which fit of [adjustments] this is (see Learner, which names each by what it was taught),
     * or null for the model as it ships. Recorded with every opinion it gives, so a verdict
     * says which model made it.
     */
    val fit: String? = null,
    /**
     * A model the user trained on the phone in place of [model] and [adjustments] (see
     * RecipeTrainer), or null for the shipped model with what it was taught.
     */
    val custom: Predictor? = null,
) {
    /** The model and its fit, as verdicts record it: "winnow-local-1" as it ships, "winnow-local-1·3fa2c1" once taught. */
    val version: String get() = if (fit == null) name else "$name·$fit"

    fun classify(message: InboundMessage): LocalPrediction {
        val features = features(message)
        val classes = custom?.classes ?: model.classes
        val p = custom?.probabilities(features) ?: model.predict(features, adjustments)
        val best = p.indices.maxBy { p[it] }
        return LocalPrediction(
            category = Category.fromKey(classes[best]) ?: Category.SPAM,
            confidence = p[best],
            distribution = classes.withIndex().mapNotNull { (i, key) -> Category.fromKey(key)?.let { it to p[i] } }.toMap(),
            reasons = custom?.reasons(features, best) ?: model.explain(features, best, adjustments = adjustments),
            model = version,
            hasHook = Featurizer.hasHook(features),
        )
    }

    /** This classifier with different learned adjustments, the fit named [fit]. */
    fun withAdjustments(adjustments: Adjustments, fit: String? = this.fit) = OnDeviceClassifier(model, adjustments, name, fit, custom)

    /**
     * What to remember when the user corrects [message]: its feature buckets, labeled with the
     * most likely category among [acceptable] (the ones that get the action the user chose).
     */
    fun correction(message: InboundMessage, acceptable: Set<Category>): Correction? {
        if (acceptable.isEmpty()) return null
        val features = features(message)
        val p = model.predict(features, adjustments)
        val label = model.classes.indices.filter { Category.fromKey(model.classes[it]) in acceptable }.maxByOrNull { p[it] } ?: return null
        return Correction(model.indices(features), label)
    }

    /** What the model reads of [message]: its features, before they're hashed into buckets. */
    fun features(message: InboundMessage) = Featurizer.features(
        Featurizer.Input(message.sender, message.body, message.senderInContacts, message.userHasMessagedSender),
    )

    /** Trains adjustments from [corrections] against this classifier's base model. */
    fun learn(corrections: List<Correction>, stopped: () -> Boolean = { false }): OnDeviceClassifier =
        withAdjustments(Personalizer.train(model, corrections, stopped = stopped))

    /**
     * This classifier with [corrections] taught on top of what it has learned already, rather
     * than everything fitted again: a few answers' worth of work, not every label's. Close to a
     * full refit, not equal to one (what it learned before isn't weighed against the new ones),
     * so it's for a quick look (Train's guesses following the answers), not for keeping.
     */
    fun learnMore(corrections: List<Correction>, stopped: () -> Boolean = { false }): OnDeviceClassifier =
        // A model trained on the phone learns at its next training, not answer by answer.
        if (custom != null) this else withAdjustments(adjustments + Personalizer.train(model, corrections, stopped = stopped, prior = adjustments))

    companion object {
        const val MODEL_NAME = "winnow-local-1"
    }
}
