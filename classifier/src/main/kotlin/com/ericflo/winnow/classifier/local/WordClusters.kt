package com.ericflo.winnow.classifier.local

import java.util.zip.GZIPInputStream

/**
 * Words that mean alike, in groups: each of the commonest English words belongs to one group,
 * made by clustering GloVe's word vectors (Stanford's, learned from Wikipedia and newswire text,
 * public domain under the ODC PDDL), so "sale", "discount" and "clearance" share one. A model that
 * learns from a text's groups as well as its words carries what one word taught it over to the
 * rest of its group, which counts most for a category with few labels: one "clearance" labeled
 * marketing teaches every word like it. Made from the features alone (their `w:` words), so a model
 * that learned from them reads any text the same way. The groups ship in Winnow; nothing is
 * looked up anywhere.
 */
object WordClusters {
    private const val RESOURCE = "/com/ericflo/winnow/classifier/local/word-clusters.txt.gz"

    /** Word → its groups (one per grouping, coarse to fine); empty if the groups weren't shipped. */
    val groups: Map<String, IntArray> by lazy {
        val stream = WordClusters::class.java.getResourceAsStream(RESOURCE) ?: return@lazy emptyMap()
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0].isEmpty()) null else parts[0] to parts.drop(1).mapNotNull(String::toIntOrNull).toIntArray()
            }.toMap()
        }
    }

    /** [features] with the groups of their words. */
    fun expand(features: List<String>): List<String> = features + of(features)

    /** The groups of [features]' words (`w:`), once each. */
    fun of(features: List<String>): List<String> =
        features.flatMap { f -> if (f.startsWith("w:")) groups[f.substring(2)]?.map { "$PREFIX$it" }.orEmpty() else emptyList() }.distinct()

    const val PREFIX = "k:"
}

/** A model that learned from [WordClusters] too: every text it reads gets its words' groups first. */
class ClustersPredictor(val inner: Predictor) : Predictor {
    override val classes get() = inner.classes
    override val readsContext get() = inner.readsContext
    override val readsShapes get() = inner.readsShapes
    override fun probabilities(features: List<String>) = inner.probabilities(WordClusters.expand(features))
    override fun reasons(features: List<String>, classIndex: Int, limit: Int) = inner.reasons(WordClusters.expand(features), classIndex, limit)
}
