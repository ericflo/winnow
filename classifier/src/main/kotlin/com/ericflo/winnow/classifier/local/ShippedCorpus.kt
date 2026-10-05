package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category

/** One line of a training or evaluation file: its category, its sender and its words. */
data class LabeledText(val category: Category, val sender: String, val body: String) {
    fun example(classes: List<String>) = Example(Featurizer.features(Featurizer.Input(sender, body)), classes.indexOf(category.key))
}

/**
 * The hand-written texts the shipped model learned from (training/corpus), bundled so a model
 * can be trained from scratch on the phone. `<category>.tsv` files of `sender<TAB>body` lines;
 * a sender of `phone`, `tollfree`, `intl` or `short` becomes a fictional number of that kind (US
 * ones in the 555-01xx range), derived from the text so it's stable.
 */
object ShippedCorpus {
    private const val RESOURCE = "/com/ericflo/winnow/classifier/local/corpus/"

    /** Every bundled text; empty if they weren't bundled. */
    val texts: List<LabeledText> by lazy {
        Category.entries.flatMap { category ->
            val stream = ShippedCorpus::class.java.getResourceAsStream("$RESOURCE${category.key}.tsv") ?: return@flatMap emptyList()
            stream.bufferedReader().use { parse(category, it.readLines()) }
        }
    }

    fun parse(category: Category, lines: List<String>): List<LabeledText> =
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val (sender, body) = line.split('\t', limit = 2).also { require(it.size == 2) { "Bad line: $line" } }
            LabeledText(category, expand(sender, body), body)
        }

    fun expand(sender: String, body: String): String {
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
}
