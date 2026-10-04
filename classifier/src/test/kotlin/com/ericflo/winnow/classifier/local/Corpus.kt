package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category
import java.io.File

/** One line of a training or evaluation file. */
data class LabeledText(val category: Category, val sender: String, val body: String) {
    fun example(classes: List<String>) = Example(Featurizer.features(Featurizer.Input(sender, body)), classes.indexOf(category.key))
}

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
            file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val (sender, body) = line.split('\t', limit = 2).also { require(it.size == 2) { "Bad line in ${file.name}: $line" } }
                LabeledText(category, expand(sender, body), body)
            }
        }

    private fun expand(sender: String, body: String): String {
        val h = (LocalModel.fnv1a(body).toLong() and 0xffffffffL)
        return when (sender) {
            "phone" -> "+1" + AREA_CODES[(h % AREA_CODES.size).toInt()] + "555" + "01" + (h / 7 % 100).toString().padStart(2, '0')
            "short" -> (20000 + h % 880000).toString()
            "tollfree" -> "+1" + listOf("800", "833", "844", "855", "866", "877", "888")[(h % 7).toInt()] + "555" + "01" + (h / 7 % 100).toString().padStart(2, '0')
            "intl" -> listOf("+44", "+234", "+63", "+92", "+855", "+62")[(h % 6).toInt()] + (7_000_000_000L + h % 999_999_999L).toString()
            else -> sender
        }
    }

    private val AREA_CODES = listOf("415", "212", "312", "213", "617", "206", "512", "303", "404", "702", "305", "818")

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
