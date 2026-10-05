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

    internal fun addTo(scores: DoubleArray, indices: IntArray, value: Double) {
        for (i in indices) {
            val row = weights[i] ?: continue
            for (c in scores.indices) scores[c] += row[c] * value
        }
    }

    companion object {
        val NONE = Adjustments(emptyMap())
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
    private const val LIGHT_FLOOR = 0.6

    fun train(base: LocalModel, corrections: List<Correction>, epochs: Int = 40, learningRate: Double = 0.5, l2: Double = 1e-3): Adjustments {
        // Corrections come from storage and backups: drop any that don't fit this model.
        @Suppress("NAME_SHADOWING")
        val corrections = corrections
            .filter { c -> c.label in base.classes.indices }
            .map { c -> c.copy(buckets = c.buckets.filter { it in 0 until base.buckets }.toIntArray()) }
            .filter { it.buckets.isNotEmpty() }
        if (corrections.isEmpty()) return Adjustments.NONE
        val k = base.classes.size
        val baseScores = corrections.map { base.scores(it.buckets) }
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
