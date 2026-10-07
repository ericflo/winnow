package com.ericflo.winnow.classifier.local

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The user's own labeled texts, to find the ones a new text reads most like. Bulk texts come from
 * templates (a store's offers, a bank's alerts, a campaign's asks), and a text that's nearly one
 * the user has labeled is most likely what they called that one, whatever a model's weights,
 * fitted across every category at once, make of its words. Most of all for a small category whose
 * texts share words with a big one: a store's points-and-offers texts read like its order updates.
 *
 * A text is its feature buckets, as labels keep them (never the text). How alike two are is the
 * cosine of their buckets, each weighted by how rare it is among the labeled texts, so "your" and
 * "the" don't make texts alike. The [NEIGHBORS] most alike, if at least [MIN_LIKENESS] alike, vote
 * with their likeness squared, and the vote leans a model's answer as a prior: each category's
 * chance times (0.9 × its share of the vote + 0.1/k) to the power [strength], then normalized, so
 * a category none of them had can still win on its own words (as ConversationReading leans). A
 * text that's like none of them, or strength 0, changes nothing. Pure, so it's unit-tested.
 */
class TemplateMemory private constructor(
    private val labels: IntArray,
    /** Each labeled text's length, as a vector of its buckets' weights. */
    private val norms: DoubleArray,
    /** Bucket → the labeled texts that have it. */
    private val postings: Map<Int, IntArray>,
    /** Bucket → how rare it is among the labeled texts (rarer weighs more). */
    private val weights: Map<Int, Double>,
    /** The weight of a bucket no labeled text has. */
    private val unseen: Double,
    val classes: List<String>,
    val strength: Double,
) {
    /** How many labeled texts it holds. */
    val size: Int get() = labels.size

    fun withStrength(strength: Double) = TemplateMemory(labels, norms, postings, weights, unseen, classes, strength)

    /** One of the labeled texts a text reads like: its category (by index) and how alike, 0 to 1. */
    data class Neighbor(val label: Int, val likeness: Double)

    /** The labeled texts most like [buckets], most alike first: at most [NEIGHBORS], none under [MIN_LIKENESS]. */
    fun neighbors(buckets: IntArray): List<Neighbor> {
        if (size == 0 || buckets.isEmpty()) return emptyList()
        val query = buckets.distinct()
        val queryNorm = sqrt(query.sumOf { b -> (weights[b] ?: unseen).let { it * it } })
        if (queryNorm <= 0.0) return emptyList()
        val dot = HashMap<Int, Double>()
        for (b in query) {
            val w = weights[b] ?: continue
            for (e in postings[b] ?: continue) dot.merge(e, w * w, Double::plus)
        }
        return dot.entries.asSequence()
            .map { (e, d) -> Neighbor(labels[e], d / (queryNorm * norms[e])) }
            .filter { it.likeness >= MIN_LIKENESS }
            .sortedByDescending { it.likeness }
            .take(NEIGHBORS)
            .toList()
    }

    /** Each category's share of the vote of the texts [buckets] reads like, or null when it reads like none. */
    fun vote(buckets: IntArray): DoubleArray? = share(neighbors(buckets), classes.size)

    /** [p] (class chances, [classes] order) leaned toward what the texts like [buckets] were labeled; [p] itself when there's nothing to add. */
    fun follow(p: DoubleArray, buckets: IntArray): DoubleArray = if (strength <= 0.0) p else lean(p, vote(buckets), strength)

    companion object {
        /** How many of the most alike labeled texts vote. */
        const val NEIGHBORS = 5

        /** How alike a labeled text must be to vote: about two thirds of a text's weighed words in common. */
        const val MIN_LIKENESS = 0.6

        /** Strengths a sweep tries for free (see SweepScorer); 0 is off. */
        val STRENGTHS = listOf(0.0, 0.5, 1.0, 2.0, 3.0)

        val NONE = of(emptyList(), emptyList(), 0.0)

        /** Each of [k] categories' share of [near]'s vote, each voting with its likeness squared; null when there's no one to vote. */
        fun share(near: List<Neighbor>, k: Int): DoubleArray? {
            if (near.isEmpty()) return null
            val vote = DoubleArray(k)
            near.forEach { n -> if (n.label in 0 until k) vote[n.label] += n.likeness * n.likeness }
            val total = vote.sum()
            return if (total <= 0.0) null else DoubleArray(k) { vote[it] / total }
        }

        /** [p] leaned toward [share] (a [vote]) at [strength]; [p] itself when there's no vote. */
        fun lean(p: DoubleArray, share: DoubleArray?, strength: Double): DoubleArray {
            if (strength <= 0.0 || share == null) return p
            val k = p.size
            val out = DoubleArray(k) { c -> p[c] * (share.getOrElse(c) { 0.0 } * 0.9 + 0.1 / k).pow(strength) }
            val sum = out.sum()
            return if (sum <= 0.0 || !sum.isFinite()) p else DoubleArray(k) { out[it] / sum }
        }

        /** From [examples] (each a labeled text's buckets and its category, by index into [classes]). */
        fun of(examples: List<Pair<IntArray, Int>>, classes: List<String>, strength: Double): TemplateMemory {
            val sets = examples.map { it.first.distinct() }
            val n = sets.size
            val df = HashMap<Int, Int>()
            sets.forEach { s -> s.forEach { df.merge(it, 1, Int::plus) } }
            val weights = df.mapValues { (_, d) -> ln(1.0 + n.toDouble() / d) }
            val postings = HashMap<Int, MutableList<Int>>()
            sets.forEachIndexed { e, s -> s.forEach { postings.getOrPut(it) { ArrayList() } += e } }
            val norms = DoubleArray(n) { e -> sqrt(sets[e].sumOf { b -> weights.getValue(b).let { it * it } }).coerceAtLeast(1e-12) }
            return TemplateMemory(
                IntArray(n) { examples[it].second }, norms, postings.mapValues { it.value.toIntArray() }, weights,
                unseen = ln(1.0 + n.coerceAtLeast(1)), classes = classes, strength = strength,
            )
        }
    }
}
