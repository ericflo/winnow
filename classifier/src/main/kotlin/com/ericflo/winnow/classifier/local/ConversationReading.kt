package com.ericflo.winnow.classifier.local

import kotlin.math.pow

/**
 * What a text's conversation reads like, leaning the model's answer for it: the model reads the
 * texts before it in the conversation too (unlabeled: what's there when it arrives), and the
 * average of their answers counts with this one's, raised to [strength]. A pharmacy's thread
 * reads as reminders, a friend's as personal, so a text that could be either leans the way its
 * conversation does. Its first text, with nothing before it, is left as the model said.
 */
object ConversationReading {
    /** [p] (class chances) with [earlier] (each earlier text's), at [strength]; [p] itself when there's nothing to add. */
    fun lean(p: DoubleArray, earlier: List<DoubleArray>, strength: Double): DoubleArray {
        if (strength <= 0 || earlier.isEmpty()) return p
        val k = p.size
        val mean = DoubleArray(k) { c -> earlier.sumOf { it.getOrElse(c) { 0.0 } } / earlier.size }
        // A little of every class, so one the earlier texts never chose can still win on its own words.
        val out = DoubleArray(k) { c -> p[c] * (mean[c] * 0.9 + 0.1 / k).pow(strength) }
        val sum = out.sum()
        return if (sum <= 0 || !sum.isFinite()) p else DoubleArray(k) { out[it] / sum }
    }

    /** The class [earlier] lean toward most, by index; null when there's nothing before. */
    fun leaning(earlier: List<DoubleArray>): Int? {
        if (earlier.isEmpty()) return null
        val k = earlier.first().size
        val mean = DoubleArray(k) { c -> earlier.sumOf { it.getOrElse(c) { 0.0 } } }
        return mean.indices.maxBy { mean[it] }
    }
}
