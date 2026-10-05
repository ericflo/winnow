package com.ericflo.winnow.classifier.local

import kotlin.math.sqrt

/**
 * One thing Winnow was taught: a message's feature buckets (never its text) and the category it
 * should have been. [weight] is how much it counts: 1 for the user's own, less for a label a
 * classifier service gave (see Learner), so the user's always outweigh it.
 */
data class Correction(val buckets: IntArray, val label: Int, val weight: Double = 1.0) {
    override fun equals(other: Any?) = other is Correction && label == other.label && weight == other.weight && buckets.contentEquals(other.buckets)
    override fun hashCode() = (buckets.contentHashCode() * 31 + label) * 31 + weight.hashCode()
}

/**
 * Per-bucket nudges learned from the user's corrections, added to the bundled model's
 * scores. Only buckets that appeared in a correction have one, so a handful of corrections
 * is a handful of small arrays. There's no bias term: a correction should move messages that
 * look like the one corrected, not every message.
 */
class Adjustments(internal val weights: Map<Int, FloatArray>) {
    val size: Int get() = weights.size

    /** Buckets with something learned. */
    val buckets: Set<Int> get() = weights.keys

    /** What was learned for [bucket], per class (in the model's class order); null when nothing was. */
    fun row(bucket: Int): FloatArray? = weights[bucket]?.copyOf()

    internal fun addTo(scores: DoubleArray, indices: IntArray, value: Double) {
        for (i in indices) {
            val row = weights[i] ?: continue
            for (c in scores.indices) scores[c] += row[c] * value
        }
    }

    /** These and [other] together: a bucket in both gets the sum. */
    operator fun plus(other: Adjustments): Adjustments {
        if (other.weights.isEmpty()) return this
        if (weights.isEmpty()) return other
        val sum = HashMap<Int, FloatArray>(weights.size + other.weights.size)
        for ((bucket, row) in weights) sum[bucket] = row.copyOf()
        for ((bucket, row) in other.weights) {
            val into = sum[bucket]
            if (into == null) sum[bucket] = row.copyOf() else for (c in row.indices) into[c] += row[c]
        }
        return Adjustments(sum)
    }

    /** Writes these compactly, to keep them between runs of the app (see [readFrom]). */
    fun writeTo(out: java.io.DataOutputStream) {
        out.writeInt(weights.size)
        out.writeInt(weights.values.firstOrNull()?.size ?: 0)
        for ((bucket, row) in weights) {
            out.writeInt(bucket)
            for (v in row) out.writeFloat(v)
        }
    }

    companion object {
        val NONE = Adjustments(emptyMap())

        /** What [writeTo] wrote. */
        fun readFrom(input: java.io.DataInputStream): Adjustments {
            val count = input.readInt()
            val width = input.readInt()
            require(count >= 0 && width in 0..64) { "not adjustments" }
            val weights = HashMap<Int, FloatArray>(count * 2)
            repeat(count) { weights[input.readInt()] = FloatArray(width) { input.readFloat() } }
            return Adjustments(weights)
        }
    }
}

/**
 * Fits [Adjustments] so each corrected message lands in its corrected category, with an L2
 * pull toward zero so the rest of the model barely moves. Retrained from scratch whenever a
 * correction or label is added or removed. Labels made that thousands of rows, and the first
 * text after a restart waits for this, so the weights live in flat arrays indexed once up front,
 * not maps looked up in the innermost loop: more than twice as fast, with the same arithmetic in
 * the same order, so the same result (PersonalizerTest checks it against the plain version).
 */
object Personalizer {
    /** A label of weight w is pulled toward probability LIGHT_FLOOR + (1 - LIGHT_FLOOR) * w: 0.74 at 0.35, certainty at 1. */
    const val LIGHT_FLOOR = 0.6

    /** Passes over the labels a fit makes, its step size, and its pull toward changing nothing. */
    const val EPOCHS = 40
    const val LEARNING_RATE = 0.5
    const val L2 = 1e-3

    /**
     * [stopped] is asked between epochs: a fit nobody wants any more (the user answered again,
     * the screen moved on) ends there with a CancellationException instead of running out its
     * second or two on a phone.
     */
    fun train(
        base: LocalModel,
        corrections: List<Correction>,
        epochs: Int = EPOCHS,
        learningRate: Double = LEARNING_RATE,
        l2: Double = L2,
        stopped: () -> Boolean = { false },
        /**
         * Already learned, and taken as part of the model: what's returned is only what
         * [corrections] add on top (see OnDeviceClassifier.learnMore).
         */
        prior: Adjustments = Adjustments.NONE,
    ): Adjustments {
        // Corrections come from storage and backups: drop any that don't fit this model.
        @Suppress("NAME_SHADOWING")
        val corrections = corrections
            .filter { c -> c.label in base.classes.indices }
            .map { c -> c.copy(buckets = c.buckets.filter { it in 0 until base.buckets }.toIntArray()) }
            .filter { it.buckets.isNotEmpty() }
        if (corrections.isEmpty()) return Adjustments.NONE
        val k = base.classes.size
        val baseScores = corrections.map { base.scores(it.buckets, prior) }
        // Each bucket any correction uses gets a row; each correction, its buckets' rows.
        val rowOf = HashMap<Int, Int>()
        val rows = corrections.map { c -> IntArray(c.buckets.size) { j -> rowOf.getOrPut(c.buckets[j]) { rowOf.size } } }
        val values = DoubleArray(corrections.size) { LocalModel.featureValue(corrections[it].buckets.size) }
        val labels = IntArray(corrections.size) { corrections[it].label }
        val weightOf = DoubleArray(corrections.size) { corrections[it].weight }
        val weights = DoubleArray(rowOf.size * k)
        val squares = DoubleArray(rowOf.size * k) { 1e-8 }
        val scores = DoubleArray(k)
        val temperature = base.temperature.toDouble()
        // A lighter label (weight under 1, a classifier service's) is a soft target: its category
        // is pulled toward a probability short of certainty, the rest keep the base model's
        // proportions. The user's own (weight 1) are pulled all the way, so where the two meet
        // the user's wins, and alone a lighter one still teaches less.
        val targets = corrections.mapIndexed { n, c ->
            if (c.weight >= 1.0) return@mapIndexed null
            val bp = LocalModel.softmax(baseScores[n], temperature)
            val t = LIGHT_FLOOR + (1 - LIGHT_FLOOR) * c.weight.coerceIn(0.0, 1.0)
            val rest = 1.0 - bp[c.label]
            DoubleArray(k) { j -> if (j == c.label) t else if (rest <= 1e-12) (1 - t) / (k - 1) else (1 - t) * bp[j] / rest }
        }

        repeat(epochs) {
            if (stopped()) throw java.util.concurrent.CancellationException("fit no longer wanted")
            for (n in corrections.indices) {
                val value = values[n]
                val own = rows[n]
                baseScores[n].copyInto(scores)
                for (r in own) { val at = r * k; for (c in 0 until k) scores[c] += weights[at + c] * value }
                val p = LocalModel.softmax(scores, temperature)
                for (c in 0 until k) {
                    val g = p[c] - (targets[n]?.get(c) ?: if (c == labels[n]) 1.0 else 0.0)
                    for (r in own) {
                        val at = r * k + c
                        val gi = g * value + l2 * weights[at]
                        squares[at] += gi * gi
                        // The weight scales the step, not the gradient AdaGrad normalizes by: scaling
                        // that would cancel out, and a lighter label would teach a feature only it
                        // has as fully as the user's own. At weight 1 this is the plain update.
                        val step = if (weightOf[n] == 1.0) gi else g * value * weightOf[n] + l2 * weights[at]
                        weights[at] -= learningRate * step / sqrt(squares[at])
                    }
                }
            }
        }
        return Adjustments(rowOf.mapValues { (_, r) -> FloatArray(k) { weights[r * k + it].toFloat() } })
    }
}
