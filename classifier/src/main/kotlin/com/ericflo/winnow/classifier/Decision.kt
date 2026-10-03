package com.ericflo.winnow.classifier

import kotlinx.serialization.json.JsonElement

/**
 * A typed multiple-choice question: pick one of [options].
 *
 * [options] maps an option key to a rubric describing when it applies (null when the key
 * speaks for itself). Iteration order is display order.
 */
data class Choice(
    val instructions: String,
    val options: Map<String, String?>,
) {
    init {
        require(options.isNotEmpty()) { "a Choice needs at least one option" }
    }
}

/** A full probability distribution over a [Choice]'s options. Always normalized. */
class Distribution private constructor(val probabilities: Map<String, Double>) {

    /** The most likely option. */
    val top: String get() = probabilities.maxBy { it.value }.key

    /** Probability of [top]. */
    val confidence: Double get() = probabilities.getValue(top)

    operator fun get(option: String): Double = probabilities[option] ?: 0.0

    override fun equals(other: Any?) = other is Distribution && other.probabilities == probabilities
    override fun hashCode() = probabilities.hashCode()
    override fun toString() = "Distribution($probabilities)"

    companion object {
        /**
         * Builds a distribution over exactly [options] from possibly messy provider output:
         * unknown keys are dropped, missing or negative values become 0, and the result is
         * renormalized. An all-zero input becomes uniform.
         */
        fun of(raw: Map<String, Double>, options: Collection<String>): Distribution {
            require(options.isNotEmpty()) { "no options" }
            val cleaned = options.associateWith { o -> raw[o]?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0 }
            val total = cleaned.values.sum()
            val normalized = if (total <= 0.0) {
                options.associateWith { 1.0 / options.size }
            } else {
                cleaned.mapValues { it.value / total }
            }
            return Distribution(normalized)
        }

        /** A distribution that puts [confidence] on [choice] and spreads the rest evenly. */
        fun fromChoice(choice: String, confidence: Double, options: Collection<String>): Distribution {
            val c = confidence.coerceIn(0.0, 1.0)
            val rest = if (options.size > 1) (1.0 - c) / (options.size - 1) else 0.0
            return of(options.associateWith { if (it == choice) c else rest }, options)
        }
    }
}

/**
 * One decision call: a [state] (any JSON) and a set of keyed questions about it.
 * This is the "System One" decision shape. All questions about one state go out together.
 */
data class DecisionRequest(
    val state: JsonElement,
    val questions: Map<String, Choice>,
)

data class DecisionResponse(
    val answers: Map<String, Distribution>,
    val model: String? = null,
    val usage: Usage = Usage(),
)

data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double = 0.0,
)
