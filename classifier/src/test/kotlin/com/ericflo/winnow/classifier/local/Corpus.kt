package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category
import java.io.File

/**
 * Reads `<category>.tsv` files of `sender<TAB>body` lines. A sender of `phone`, `tollfree`,
 * `intl` or `short` becomes a fictional number of that kind (US ones in the 555-01xx range),
 * derived from the text so it's stable.
 */
object Corpus {
    val classes: List<String> = Category.entries.map { it.key }

    fun load(dir: File): List<LabeledText> =
        Category.entries.flatMap { category ->
            val file = File(dir, "${category.key}.tsv")
            if (!file.exists()) return@flatMap emptyList()
            ShippedCorpus.parse(category, file.readLines())
        }

    /** Stratified folds: indices into [items], each category spread evenly across [k] folds. */
    fun folds(items: List<LabeledText>, k: Int, seed: Int = 7): List<List<Int>> {
        val random = kotlin.random.Random(seed)
        val folds = List(k) { mutableListOf<Int>() }
        items.indices.groupBy { items[it].category }.values.forEach { group ->
            group.shuffled(random).forEachIndexed { n, i -> folds[n % k] += i }
        }
        return folds
    }

    /** A stratified split: [fraction] of each category goes to the second list. */
    fun split(items: List<LabeledText>, fraction: Double, seed: Int = 7): Pair<List<LabeledText>, List<LabeledText>> {
        val random = kotlin.random.Random(seed)
        val (train, test) = items.groupBy { it.category }.values.map { group ->
            val shuffled = group.shuffled(random)
            val n = (group.size * fraction).toInt()
            shuffled.drop(n) to shuffled.take(n)
        }.unzip()
        return train.flatten() to test.flatten()
    }
}
