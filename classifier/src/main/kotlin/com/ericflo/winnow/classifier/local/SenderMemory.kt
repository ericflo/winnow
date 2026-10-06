package com.ericflo.winnow.classifier.local

import kotlin.math.ln
import kotlin.math.pow

/**
 * What the user's own labels say about each sender, used with a model's answer: of a sender's
 * texts they've labeled, how many in each category. A model reads a text's words, and two texts
 * that read alike can be different things to the user because of who sent them (one pharmacy's
 * pickup texts a reminder, another's transactional); this is how the model follows them there.
 *
 * Two ways, by what the user has said. With [DECISIVE_AT_LEAST] labels of a sender or more, all
 * one way, that way decides ([decisive]): their labels are the answer key, whatever the words
 * suggest. One label the other way says the sender sends more than one kind and the user tells
 * them apart, so it's never outvoted: with mixed labels, or fewer, they nudge the model's answer as
 * a smoothed prior ([apply]): each category's chance times ((its labels + ½) / (all labels + k/2))
 * to the power [strength], then normalized. A sender with no labels changes nothing, and neither
 * does strength 0. Only the user's labels count, never a classifier service's. Pure, so it's
 * unit-tested.
 *
 * Deciding is for senders the user doesn't text with: a pharmacy, a short code, a spammer, whose
 * texts are one kind because of who sends them. Someone they text with sends every kind (a
 * reminder to grab milk, then dinner plans), so their labels of that person only nudge.
 */
class SenderMemory(
    /** Sender key (see [keyOf]) → labels per class, in [classes] order. */
    private val counts: Map<String, IntArray>,
    val classes: List<String>,
    val strength: Double = DEFAULT_STRENGTH,
) {
    /** How many senders it knows. */
    val senders: Int get() = counts.size

    /** The user's labels of [sender]'s texts, per class, or null if they've labeled none. */
    fun countsFor(sender: String): IntArray? = keyOf(sender)?.let(counts::get)

    /**
     * [p] (class chances, [classes] order) with what the user's labels of [sender] say; [p]
     * itself when there's nothing to add.
     */
    fun apply(p: DoubleArray, sender: String): DoubleArray {
        if (strength <= 0.0) return p
        val c = countsFor(sender) ?: return p
        val n = c.sum()
        if (n == 0) return p
        val k = p.size
        val out = DoubleArray(k) { i -> p[i] * ((c.getOrElse(i) { 0 } + ALPHA) / (n + ALPHA * k)).pow(strength) }
        val sum = out.sum()
        return if (sum <= 0.0 || !sum.isFinite()) p else DoubleArray(k) { out[it] / sum }
    }

    /**
     * The category the user's labels of [sender] settle, by index: enough of them, all one way.
     * Null when they don't, when strength is 0, or when the user texts with them
     * ([conversing]: their labels of a person only nudge).
     */
    fun decisive(sender: String, conversing: Boolean = false): Int? {
        if (strength <= 0.0 || conversing) return null
        val c = countsFor(sender) ?: return null
        val n = c.sum()
        val top = c.indices.maxByOrNull { c[it] } ?: return null
        return top.takeIf { n >= DECISIVE_AT_LEAST && c[top] == n }
    }

    /** How sure the user's labels make it, when [decisive]: 3 of 3 is 75%, 9 of 9 is 90%. */
    fun decisiveConfidence(sender: String, label: Int): Double {
        val c = countsFor(sender) ?: return 0.0
        return c.getOrElse(label) { 0 } / (c.sum() + 1.0)
    }

    /** What the user's labels make of a model's answer [p] for a text from [sender] (see [follow]). */
    data class Followed(val p: DoubleArray, val best: Int, val confidence: Double, val decided: Boolean) {
        /**
         * As class chances, for scoring: decided, the user's category at its confidence and the
         * rest shared out as the model had them; nudged, as nudged.
         */
        val distribution: DoubleArray get() {
            if (!decided) return p
            val rest = p.indices.filter { it != best }.sumOf { p[it] }
            return DoubleArray(p.size) { i -> if (i == best) confidence else if (rest <= 0) 0.0 else p[i] / rest * (1 - confidence) }
        }
    }

    /**
     * A model's answer [p] with the user's labels of [sender]: decided by them when [decisive],
     * nudged otherwise; [conversing] when the user has texted them. Used the same way on the
     * phone and in the Lab's scoring.
     */
    fun follow(p: DoubleArray, sender: String, conversing: Boolean = false): Followed {
        val nudged = apply(p, sender)
        val decided = decisive(sender, conversing)
        val best = decided ?: nudged.indices.maxBy { nudged[it] }
        val confidence = if (decided != null) maxOf(nudged[best], decisiveConfidence(sender, best)) else nudged[best]
        return Followed(nudged, best, confidence, decided != null)
    }

    /** The same memory counting for [strength] instead. */
    fun withStrength(strength: Double) = SenderMemory(counts, classes, strength)

    companion object {
        const val DEFAULT_STRENGTH = 1.0
        /** Labels of one sender, all one way, that decide on their own (see [decisive]). */
        const val DECISIVE_AT_LEAST = 3
        private const val ALPHA = 0.5

        val NONE = SenderMemory(emptyMap(), emptyList(), 0.0)

        /** A memory of [labels]: (sender, class index) pairs, the user's own. */
        fun of(labels: List<Pair<String, Int>>, classes: List<String>, strength: Double = DEFAULT_STRENGTH): SenderMemory {
            val counts = HashMap<String, IntArray>()
            for ((sender, label) in labels) {
                if (label !in classes.indices) continue
                val key = keyOf(sender) ?: continue
                counts.getOrPut(key) { IntArray(classes.size) }[label]++
            }
            return SenderMemory(counts, classes, strength)
        }

        /**
         * Who a sender is, for remembering: a phone number by its last ten digits (so +1 and
         * national forms agree), a short code by its digits, a named sender (AMAZON) or an
         * email by its lowercase text. Null when there's nothing to go by.
         */
        fun keyOf(sender: String): String? {
            val s = sender.trim()
            if (s.isEmpty()) return null
            if (s.any(Char::isLetter) || '@' in s) return s.lowercase()
            val digits = s.filter(Char::isDigit)
            return when {
                digits.length >= 7 -> digits.takeLast(10)
                digits.isNotEmpty() -> "short:$digits"
                else -> null
            }
        }

        /** How much [memory]'s prior moved [p] toward [label]: its log-odds change, for explaining. */
        fun pull(p: DoubleArray, adjusted: DoubleArray, label: Int): Double = ln(adjusted[label].coerceAtLeast(1e-12)) - ln(p[label].coerceAtLeast(1e-12))
    }
}
