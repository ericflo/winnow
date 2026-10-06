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
    /** Of the user's labels of this sender, how many are [category], and how many in all (see SenderMemory). */
    val senderLabels: Int = 0,
    val senderLabelsTotal: Int = 0,
    /** The categories the user has given this sender's texts. */
    val senderCategories: Set<Category> = emptySet(),
    /**
     * The user has labeled enough of this sender's texts, all one way, and this is that
     * way (see SenderMemory.decisive): their labels decide, before any classifier service is asked.
     */
    val yourLabelsDecide: Boolean = false,
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
    /**
     * What the user's own labels say about each sender (see [SenderMemory]), used with the
     * model's answer, so texts that read alike follow the user where who sent them is what
     * makes them different.
     */
    val memory: SenderMemory = SenderMemory.NONE,
) {
    /** The model and its fit, as verdicts record it: "winnow-local-1" as it ships, "winnow-local-1·3fa2c1" once taught. */
    val version: String get() = if (fit == null) name else "$name·$fit"

    fun classify(message: InboundMessage): LocalPrediction {
        val features = features(message)
        val classes = custom?.classes ?: model.classes
        // A model trained on the phone that learned from texts' context reads this one's too.
        val read = if (custom?.readsContext == true) features + ContextFeatures.of(message.context) else features
        val words = custom?.probabilities(read) ?: model.predict(features, adjustments)
        // The user's labels of this sender, where they've given any: enough of them, one way,
        // decide; fewer nudge.
        val followed = if (memory.classes == classes) memory.follow(words, message.sender, conversing = message.userHasMessagedSender) else null
        val p = followed?.p ?: words
        val best = followed?.best ?: p.indices.maxBy { p[it] }
        val fromWords = words.indices.maxBy { words[it] }
        val counts = if (followed != null && memory.strength > 0) memory.countsFor(message.sender) else null
        val said = counts?.takeIf { p !== words }
        val sender = said?.let { c ->
            val n = c.getOrElse(best) { 0 }
            val total = c.sum()
            // Said when the labels decided it, or backed it: "you labeled 3 of this sender's 4 texts transactional".
            if (n > 0) "you labeled $n of this sender's $total ${if (total == 1) "text" else "texts"} ${Category.fromKey(classes[best])?.label?.lowercase() ?: classes[best]}" else null
        }
        val wordReasons = custom?.reasons(read, best) ?: model.explain(features, best, adjustments = adjustments)
        return LocalPrediction(
            category = Category.fromKey(classes[best]) ?: Category.SPAM,
            confidence = followed?.confidence ?: p[best],
            distribution = classes.withIndex().mapNotNull { (i, key) -> Category.fromKey(key)?.let { it to p[i] } }.toMap(),
            reasons = when {
                sender == null -> wordReasons
                best != fromWords -> listOf(sender) + wordReasons
                else -> wordReasons + sender
            },
            model = version,
            hasHook = Featurizer.hasHook(features),
            senderLabels = counts?.getOrElse(best) { 0 } ?: 0,
            senderLabelsTotal = counts?.sum() ?: 0,
            senderCategories = counts?.let { c -> c.indices.filter { c[it] > 0 }.mapNotNullTo(HashSet()) { Category.fromKey(classes[it]) } }.orEmpty(),
            yourLabelsDecide = followed?.decided == true,
        )
    }

    /** This classifier with different learned adjustments, the fit named [fit]. */
    fun withAdjustments(adjustments: Adjustments, fit: String? = this.fit) = OnDeviceClassifier(model, adjustments, name, fit, custom, memory)

    /** This classifier with [memory] of the user's labels per sender. */
    fun withMemory(memory: SenderMemory) = OnDeviceClassifier(model, adjustments, name, fit, custom, memory)

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
