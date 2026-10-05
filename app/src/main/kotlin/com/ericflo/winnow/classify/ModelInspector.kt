package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.local.Featurizer
import com.ericflo.winnow.classifier.local.OnDeviceClassifier
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage

/** Looks inside the on-device model: what it learned, and how it reads one text. */
object ModelInspector {
    /** A feature as words: “toll”, “pay now”, a link to an unusual domain. */
    fun name(feature: String): String = Featurizer.describe(feature)?.let(::plain) ?: when {
        feature.startsWith("w:") || feature.startsWith("b:") -> "“${plain(feature.substring(2))}”"
        feature.startsWith("h:") -> "a link mentioning “${feature.substring(2)}”"
        feature.startsWith("__len_") -> "length band ${feature.removePrefix("__len_").removeSuffix("__")}"
        feature.startsWith("__sender_") -> "sent from a ${feature.removePrefix("__sender_").removeSuffix("__").replace('_', ' ')}"
        else -> feature.trim('_').replace('_', ' ')
    }

    /** The featurizer's stand-ins for numbers, links, money and so on, as a person would write them. */
    private fun plain(words: String): String = words.split(' ').joinToString(" ") {
        when (it) {
            "zznumshort" -> "#"
            "zznumlong" -> "#####"
            "zzurl" -> "[link]"
            "zzmoney" -> "$"
            "zzphone" -> "[phone number]"
            "zzemail" -> "[email]"
            else -> it
        }
    }

    /** One feature the user's teaching moved, toward [category], by [weight] (in the model's own units). */
    data class Learned(val name: String, val weight: Double)

    /**
     * What the user's teaching pushed hardest toward each category: the feature buckets with the
     * biggest learned weight for it, named by the features of [taught] (the texts that taught
     * them; a bucket is a hash, so only a text that had it can say what it was). Buckets no
     * taught text names are left out. Pure, so it's unit-tested.
     */
    fun learned(classifier: OnDeviceClassifier, taught: List<InboundMessage>, perCategory: Int = 8): Map<Category, List<Learned>> {
        val model = classifier.model
        val names = HashMap<Int, MutableSet<String>>()
        taught.forEach { m -> classifier.features(m).forEach { f -> names.getOrPut(model.bucket(f)) { LinkedHashSet() } += f } }
        val adjustments = classifier.adjustments
        return model.classes.withIndex().mapNotNull { (c, key) ->
            val category = Category.fromKey(key) ?: return@mapNotNull null
            val top = adjustments.buckets.asSequence()
                .mapNotNull { b -> adjustments.row(b)?.let { row -> b to row[c].toDouble() } }
                .filter { (b, w) -> w > 0 && b in names }
                .sortedByDescending { it.second }
                .take(perCategory)
                .map { (b, w) -> Learned(names.getValue(b).take(2).joinToString(" / ") { name(it) }, w) }
                .toList()
            category to top
        }.toMap()
    }

    /** How the model reads one text: each feature's pull, and the odds before and after what it was taught. */
    data class Reading(
        val shipped: Map<Category, Double>,
        val taught: Map<Category, Double>,
        /** Strongest first: a feature, the category it pulls toward most, and its pull there from the shipped model and from teaching. */
        val features: List<Pull>,
    )

    data class Pull(val name: String, val toward: Category, val shipped: Double, val learned: Double)

    fun read(classifier: OnDeviceClassifier, message: InboundMessage, limit: Int = 20): Reading {
        val model = classifier.model
        val features = classifier.features(message)
        fun odds(p: DoubleArray) = model.classes.withIndex().mapNotNull { (i, k) -> Category.fromKey(k)?.let { it to p[i] } }.toMap()
        val pulls = model.contributions(features, classifier.adjustments).mapNotNull { c ->
            val best = model.classes.indices.maxBy { c.total(it) }
            val category = Category.fromKey(model.classes[best]) ?: return@mapNotNull null
            Pull(c.features.take(2).joinToString(" / ") { name(it) }, category, c.base[best], c.learned[best])
        }.sortedByDescending { kotlin.math.abs(it.shipped + it.learned) }.take(limit)
        return Reading(
            shipped = odds(model.predict(features)),
            taught = odds(model.predict(features, classifier.adjustments)),
            features = pulls,
        )
    }
}
