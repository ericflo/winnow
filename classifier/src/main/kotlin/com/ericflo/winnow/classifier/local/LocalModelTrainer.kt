package com.ericflo.winnow.classifier.local

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** One labeled message, already featurized. */
data class Example(val features: List<String>, val label: Int)

/**
 * Trains a [LocalModel] with AdaGrad on the softmax loss, weighting classes so small ones
 * count as much as big ones. Deterministic for a given seed, so the shipped model can be
 * rebuilt exactly from the corpus.
 */
class LocalModelTrainer(
    private val classes: List<String>,
    private val buckets: Int = 1 shl 15,
    private val epochs: Int = 30,
    private val learningRate: Double = 0.5,
    private val l2: Double = 1e-5,
    private val seed: Int = 42,
) {
    fun train(examples: List<Example>): LocalModel {
        val k = classes.size
        val shape = LocalModel(classes, buckets, FloatArray(buckets * k), FloatArray(k))
        val encoded = examples.map { shape.indices(it.features) to it.label }
        val counts = IntArray(k).also { c -> examples.forEach { c[it.label]++ } }
        val classWeight = DoubleArray(k) { if (counts[it] == 0) 0.0 else examples.size.toDouble() / (k * counts[it]) }

        val w = DoubleArray(buckets * k)
        val b = DoubleArray(k)
        val gw = DoubleArray(buckets * k) { 1e-8 }
        val gb = DoubleArray(k) { 1e-8 }
        val random = Random(seed)
        val order = encoded.indices.toMutableList()

        repeat(epochs) {
            order.shuffle(random)
            for (n in order) {
                val (indices, label) = encoded[n]
                val value = LocalModel.featureValue(indices.size)
                val scores = DoubleArray(k) { b[it] }
                for (i in indices) for (c in 0 until k) scores[c] += w[i * k + c] * value
                val p = LocalModel.softmax(scores)
                for (c in 0 until k) {
                    val g = (p[c] - if (c == label) 1.0 else 0.0) * classWeight[label]
                    gb[c] += g * g
                    b[c] -= learningRate * g / sqrt(gb[c])
                    for (i in indices) {
                        val j = i * k + c
                        val gj = g * value + l2 * w[j]
                        gw[j] += gj * gj
                        w[j] -= learningRate * gj / sqrt(gw[j])
                    }
                }
            }
        }
        return LocalModel(classes, buckets, FloatArray(w.size) { w[it].toFloat() }, FloatArray(k) { b[it].toFloat() })
    }

    companion object {
        /** The temperature that minimizes log loss on [heldOut], from a coarse grid. */
        fun calibrate(model: LocalModel, heldOut: List<Example>): Float {
            val scored = heldOut.map { model.scores(model.indices(it.features)) to it.label }
            return (5..60).map { it / 10f }.minBy { t ->
                scored.sumOf { (s, label) -> -ln(LocalModel.softmax(s, t.toDouble())[label].coerceAtLeast(1e-12)) }
            }
        }
    }
}
