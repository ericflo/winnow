package com.ericflo.winnow.classifier.local

import kotlin.math.ln

/**
 * Pieces of words, four letters at a time, from a text's words (`w:` features of five letters or
 * more, marked where they start and end): "redelivery" gives "^red", "rede", … "ery$". Words that
 * share a stem, or a misspelling of one, then share these, so a model can carry what it learned
 * about one over to the other. Made from the features alone, so a model that learned from them
 * reads any text the same way.
 */
object WordPieces {
    fun expand(features: List<String>): List<String> = features + features.flatMap(::of)

    private fun of(feature: String): List<String> {
        if (!feature.startsWith("w:") || feature.length < 7) return emptyList()
        val word = feature.substring(2)
        // Placeholders ("zzurl", "zzmoney") already say what they are.
        if (word.startsWith("zz")) return emptyList()
        return "^$word$".windowed(4).map { "g:$it" }
    }
}

/** A model that learned from a text's context too ([ContextFeatures]): it's given them beside the words. */
class ContextPredictor(val inner: Predictor) : Predictor {
    override val classes get() = inner.classes
    override val readsContext get() = true
    override val readsShapes get() = inner.readsShapes
    override fun probabilities(features: List<String>) = inner.probabilities(features)
    override fun reasons(features: List<String>, classIndex: Int, limit: Int) = inner.reasons(features, classIndex, limit)
}

/**
 * A model with a lean toward or away from each category ([bias], added to its scores), at its
 * own [temperature]: [inner] answers at its own odds (temperature 1), and these sit on top.
 */
class BiasedPredictor(val inner: Predictor, val bias: List<Double>, var temperature: Float = 1f) : Predictor {
    override val classes get() = inner.classes
    override val readsContext get() = inner.readsContext
    override val readsShapes get() = inner.readsShapes
    override fun probabilities(features: List<String>): DoubleArray {
        val p = inner.probabilities(features)
        return LocalModel.softmax(DoubleArray(p.size) { ln(p[it].coerceAtLeast(1e-12)) + bias.getOrElse(it) { 0.0 } }, temperature.toDouble())
    }
    override fun reasons(features: List<String>, classIndex: Int, limit: Int) = inner.reasons(features, classIndex, limit)
}

/** A model that learned from a text's shapes too ([TextShapes]): it's given them beside the words. */
class ShapesPredictor(val inner: Predictor) : Predictor {
    override val classes get() = inner.classes
    override val readsContext get() = inner.readsContext
    override val readsShapes get() = true
    override fun probabilities(features: List<String>) = inner.probabilities(features)
    override fun reasons(features: List<String>, classIndex: Int, limit: Int) = inner.reasons(features, classIndex, limit)
}

/**
 * [inner], trained on other categories, answering over [classes]: one it knew that's gone since
 * (Reminder) is left out, the rest sharing its odds as they would have without it; one it never
 * learned gets none. A Lab model trained before Reminder was taken out runs this way until it's
 * trained again.
 */
class ClassesPredictor(val inner: Predictor, override val classes: List<String>) : Predictor {
    private val from = classes.map { inner.classes.indexOf(it) }
    override val readsContext get() = inner.readsContext
    override val readsShapes get() = inner.readsShapes
    override fun probabilities(features: List<String>): DoubleArray {
        val p = inner.probabilities(features)
        val kept = DoubleArray(classes.size) { i -> if (from[i] >= 0) p[from[i]] else 0.0 }
        val sum = kept.sum()
        return if (sum > 0) DoubleArray(kept.size) { kept[it] / sum } else DoubleArray(kept.size) { 1.0 / kept.size }
    }
    override fun reasons(features: List<String>, classIndex: Int, limit: Int): List<String> =
        from.getOrNull(classIndex)?.takeIf { it >= 0 }?.let { inner.reasons(features, it, limit) }.orEmpty()
}

/** [features] as [model] learned to read them: without context or shape features unless it learned from them. */
fun featuresFor(model: Predictor, features: List<String>): List<String> =
    features.filter { (model.readsContext || !ContextFeatures.isContext(it)) && (model.readsShapes || !TextShapes.isShape(it)) }

/**
 * Each word again, as from the kind of sender it came from ("x:short_code|appointment",
 * "x:phone_number+you|tonight", "+you" when the user texts with them or has them as a contact):
 * the same word can mean different things from a business, a stranger and a friend, and a linear
 * model weighs each feature one way. Made from the features alone, like [WordPieces].
 */
object SenderCrosses {
    private val KINDS = setOf("phone_number", "short_code", "alphanumeric", "email", "unknown")

    fun expand(features: List<String>): List<String> {
        val kind = features.firstNotNullOfOrNull { f -> f.removePrefix("__sender_").removeSuffix("__").takeIf { f.startsWith("__sender_") && it in KINDS } } ?: return features
        val group = kind + if ("__known__" in features || "__contact__" in features) "+you" else ""
        return features + features.filter { it.startsWith("w:") }.map { "x:$group|${it.substring(2)}" }
    }
}

/** A model that learned from [SenderCrosses] too: every text it reads gets them first. */
class CrossesPredictor(val inner: Predictor) : Predictor {
    override val classes get() = inner.classes
    override val readsContext get() = inner.readsContext
    override val readsShapes get() = inner.readsShapes
    override fun probabilities(features: List<String>) = inner.probabilities(SenderCrosses.expand(features))
    override fun reasons(features: List<String>, classIndex: Int, limit: Int) = inner.reasons(SenderCrosses.expand(features), classIndex, limit)
}

/** A model that learned from [WordPieces] too: every text it reads gets them first. */
class PiecesPredictor(val inner: Predictor) : Predictor {
    override val classes get() = inner.classes
    override val readsContext get() = inner.readsContext
    override val readsShapes get() = inner.readsShapes
    override fun probabilities(features: List<String>) = inner.probabilities(WordPieces.expand(features))
    override fun reasons(features: List<String>, classIndex: Int, limit: Int) = inner.reasons(WordPieces.expand(features), classIndex, limit)
}

/**
 * Several models' answers averaged, each counting by its weight: models that err differently
 * cancel some of each other's mistakes. [temperature] then sets how sure the blend says it is
 * (calibrated on labels it hadn't seen, as every Lab model is).
 */
class BlendPredictor(val members: List<Predictor>, weights: List<Double>, var temperature: Float = 1f) : Predictor {
    init {
        require(members.isNotEmpty() && weights.size == members.size)
    }

    private val shares = weights.sum().let { total -> weights.map { it / total } }
    override val classes get() = members.first().classes
    override val readsContext get() = members.any { it.readsContext }
    override val readsShapes get() = members.any { it.readsShapes }

    /** The members' class probabilities averaged, as logits (their log): a softmax of these at 1 gives the average back. */
    fun blendLogits(memberLogits: List<DoubleArray>): DoubleArray = blendLogits(memberLogits, shares)

    override fun probabilities(features: List<String>): DoubleArray {
        val k = classes.size
        val avg = DoubleArray(k)
        // Each member at its own odds before training's calibration: the blend is calibrated as a whole.
        members.forEachIndexed { m, member -> rawProbabilities(member, featuresFor(member, features)).forEachIndexed { c, p -> avg[c] += shares[m] * p } }
        return LocalModel.softmax(DoubleArray(k) { ln(avg[it].coerceAtLeast(1e-12)) }, temperature.toDouble())
    }

    override fun reasons(features: List<String>, classIndex: Int, limit: Int): List<String> =
        members.withIndex().sortedByDescending { shares[it.index] }.flatMap { it.value.reasons(featuresFor(it.value, features), classIndex, limit) }.distinct().take(limit)

    private fun rawProbabilities(member: Predictor, features: List<String>): DoubleArray = when (member) {
        is NeuralModel -> LocalModel.softmax(member.scores(member.indices(features)))
        is PiecesPredictor -> rawProbabilities(member.inner, WordPieces.expand(features))
        is ClustersPredictor -> rawProbabilities(member.inner, WordClusters.expand(features))
        is CrossesPredictor -> rawProbabilities(member.inner, SenderCrosses.expand(features))
        is ContextPredictor -> rawProbabilities(member.inner, features)
        is ShapesPredictor -> rawProbabilities(member.inner, features)
        else -> member.probabilities(features)
    }

    companion object {
        /** Logits from several models averaged as probabilities, by [shares] (summing to 1), as logits again. */
        fun blendLogits(memberLogits: List<DoubleArray>, shares: List<Double>): DoubleArray {
            val k = memberLogits.first().size
            val avg = DoubleArray(k)
            memberLogits.forEachIndexed { m, s -> LocalModel.softmax(s).forEachIndexed { c, p -> avg[c] += shares[m] * p } }
            return DoubleArray(k) { ln(avg[it].coerceAtLeast(1e-12)) }
        }
    }
}
