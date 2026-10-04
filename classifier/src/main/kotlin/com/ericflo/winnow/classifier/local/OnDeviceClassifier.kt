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
)

/**
 * Classifies on the phone with [LocalModel]. It sees the whole message, unredacted, because
 * nothing leaves the device.
 */
class OnDeviceClassifier(
    private val model: LocalModel = LocalModel.bundled,
    /** What the user's corrections taught it, on top of [model]. */
    private val adjustments: Adjustments = Adjustments.NONE,
    val name: String = MODEL_NAME,
) {

    fun classify(message: InboundMessage): LocalPrediction {
        val features = features(message)
        val p = model.predict(features, adjustments)
        val best = p.indices.maxBy { p[it] }
        return LocalPrediction(
            category = Category.fromKey(model.classes[best]) ?: Category.SPAM,
            confidence = p[best],
            distribution = model.classes.withIndex().mapNotNull { (i, key) -> Category.fromKey(key)?.let { it to p[i] } }.toMap(),
            reasons = model.explain(features, best, adjustments = adjustments),
            model = name,
        )
    }

    /** This classifier with different learned adjustments. */
    fun withAdjustments(adjustments: Adjustments) = OnDeviceClassifier(model, adjustments, name)

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

    private fun features(message: InboundMessage) = Featurizer.features(
        Featurizer.Input(message.sender, message.body, message.senderInContacts, message.userHasMessagedSender),
    )

    /** Trains adjustments from [corrections] against this classifier's base model. */
    fun learn(corrections: List<Correction>): OnDeviceClassifier = withAdjustments(Personalizer.train(model, corrections))

    companion object {
        const val MODEL_NAME = "winnow-local-1"
    }
}
