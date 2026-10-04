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
class OnDeviceClassifier(private val model: LocalModel = LocalModel.bundled, val name: String = MODEL_NAME) {

    fun classify(message: InboundMessage): LocalPrediction {
        val features = Featurizer.features(
            Featurizer.Input(message.sender, message.body, message.senderInContacts, message.userHasMessagedSender),
        )
        val p = model.predict(features)
        val best = p.indices.maxBy { p[it] }
        return LocalPrediction(
            category = Category.fromKey(model.classes[best]) ?: Category.SPAM,
            confidence = p[best],
            distribution = model.classes.withIndex().mapNotNull { (i, key) -> Category.fromKey(key)?.let { it to p[i] } }.toMap(),
            reasons = model.explain(features, best),
            model = name,
        )
    }

    companion object {
        const val MODEL_NAME = "winnow-local-1"
    }
}
