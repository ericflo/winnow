package com.ericflo.winnow.classifier.local

import kotlin.math.sqrt

/**
 * One thing the user taught Winnow: a message's feature buckets (never its text) and the
 * category it should have been.
 */
data class Correction(val buckets: IntArray, val label: Int) {
    override fun equals(other: Any?) = other is Correction && label == other.label && buckets.contentEquals(other.buckets)
    override fun hashCode() = buckets.contentHashCode() * 31 + label
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
 * correction is added or removed: corrections are few, so it takes milliseconds.
 */
object Personalizer {
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
        val weights = HashMap<Int, DoubleArray>()
        val squares = HashMap<Int, DoubleArray>()
        corrections.forEach { c -> c.buckets.forEach { weights.getOrPut(it) { DoubleArray(k) }; squares.getOrPut(it) { DoubleArray(k) { 1e-8 } } } }

        repeat(epochs) {
            corrections.forEachIndexed { n, correction ->
                val value = LocalModel.featureValue(correction.buckets.size)
                val scores = baseScores[n].copyOf()
                for (i in correction.buckets) { val row = weights.getValue(i); for (c in 0 until k) scores[c] += row[c] * value }
                val p = LocalModel.softmax(scores, base.temperature.toDouble())
                for (c in 0 until k) {
                    val g = p[c] - if (c == correction.label) 1.0 else 0.0
                    for (i in correction.buckets) {
                        val row = weights.getValue(i)
                        val sq = squares.getValue(i)
                        val gi = g * value + l2 * row[c]
                        sq[c] += gi * gi
                        row[c] -= learningRate * gi / sqrt(sq[c])
                    }
                }
            }
        }
        return Adjustments(weights.mapValues { (_, row) -> FloatArray(k) { row[it].toFloat() } })
    }
}
