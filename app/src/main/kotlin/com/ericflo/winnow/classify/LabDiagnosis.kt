package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.message.Category

/**
 * How well a model follows the user's labels, from one scoring's results, and what would help it
 * follow them better. The user's labels are the answer key: the model is what falls short, never
 * them. So this says where its mistakes go, where it can't separate two of the user's categories
 * (a limit of what it reads, not of the labels), which of its mistakes it was surest of, and which
 * nearly identical texts the user labeled differently, for a neutral review (pick one, or keep
 * both). Pure, so it's unit-tested; the Lab and Evaluate show it.
 *
 * What it rests on (measured with the Lab's own training on the hand-written corpus,
 * `:classifier:labCeilingExperiment`, and never shown as if it were the user's): every design fits
 * the labels it learns from completely, so a score well below that is about carrying over to texts
 * it hasn't seen, and a bigger model doesn't help. A model that reads words can't follow labels
 * that the words don't separate: with texts that read alike labeled apart, every design scored in
 * the 80s where it scored in the 90s otherwise. What helps is more examples, and signals beyond
 * the words.
 */
object LabDiagnosis {
    /** One scored text: the user's label, what the model said, and how sure it was. */
    data class Scored(val key: String, val label: Category, val predicted: Category, val confidence: Double)

    /** Of [label]'s texts, how many the model put in [predicted] instead. */
    data class Confusion(val label: Category, val predicted: Category, val count: Int, val ofLabel: Int)

    /** A category: the user's labels of it, and how the model did on them and with it. */
    data class CategoryRow(val category: Category, val labels: Int, val recall: Double, val precision: Double?)

    /** Two of the user's labeled texts that read nearly alike but got different labels from them. */
    data class Pair2(val a: LabeledText, val b: LabeledText, val similarity: Double) {
        /** The same pair whichever way round, for remembering the user's answer. */
        val id: String get() = listOf(a.key, b.key).sorted().joinToString("|")
    }

    data class LabeledText(val key: String, val text: String, val label: Category)

    /** A thing that would help the model follow the user's labels, most useful first. */
    data class Suggestion(val title: String, val detail: String)

    /** Where its mistakes went, biggest first: "of your reminders, 12 it called transactional". */
    fun confusions(items: List<Scored>): List<Confusion> {
        val perLabel = items.groupingBy { it.label }.eachCount()
        return items.filter { it.label != it.predicted }
            .groupingBy { it.label to it.predicted }.eachCount()
            .map { (pair, n) -> Confusion(pair.first, pair.second, n, perLabel[pair.first] ?: n) }
            .sortedWith(compareByDescending<Confusion> { it.count }.thenBy { it.label.ordinal })
    }

    fun categories(items: List<Scored>): List<CategoryRow> = Category.entries.mapNotNull { c ->
        val mine = items.filter { it.label == c }
        val said = items.filter { it.predicted == c }
        if (mine.isEmpty() && said.isEmpty()) return@mapNotNull null
        CategoryRow(
            c, mine.size,
            recall = if (mine.isEmpty()) 0.0 else mine.count { it.predicted == c }.toDouble() / mine.size,
            precision = if (said.isEmpty()) null else said.count { it.label == c }.toDouble() / said.size,
        )
    }

    /** The model's mistakes it was surest of: where what it learned misleads it most. Surest first. */
    fun surestMistakes(items: List<Scored>, atLeast: Double = SURE): List<Scored> =
        items.filter { it.label != it.predicted && it.confidence >= atLeast }.sortedByDescending { it.confidence }

    /**
     * The user's labeled texts that read nearly alike (most of their words shared, numbers and
     * links aside) but that they labeled differently: for them to look at together, and either
     * pick one label or keep both. Most alike first.
     */
    fun labeledApart(texts: List<LabeledText>, atLeast: Double = ALIKE): List<Pair2> {
        val words = texts.map { wordsOf(it.text) }
        val out = mutableListOf<Pair2>()
        for (i in texts.indices) {
            if (words[i].size < MIN_WORDS) continue
            for (j in i + 1 until texts.size) {
                if (texts[i].label == texts[j].label || words[j].size < MIN_WORDS) continue
                val shared = words[i].count { it in words[j] }
                val similarity = shared.toDouble() / (words[i].size + words[j].size - shared)
                if (similarity >= atLeast) out += Pair2(texts[i], texts[j], similarity)
            }
        }
        return out.sortedByDescending { it.similarity }
    }

    /** A text's words for comparing: lowercase, numbers as one token, links as one, one-letter words dropped. */
    fun wordsOf(text: String): Set<String> = text.lowercase()
        .replace(Regex("""https?://\S+|\b\S+\.(com|net|org|ly|co|io|top|xyz|info)\S*"""), " link ")
        .split(Regex("""[^\p{L}\p{N}]+"""))
        .filter { it.length > 1 }
        .map { if (it.any(Char::isDigit)) "#" else it }
        .toSet()

    /**
     * What would help the model follow the user's labels, from a scoring's [items]: [toReview] are
     * texts labeled apart that the user hasn't looked at yet, [keptApart] how many such pairs they
     * chose to keep, [fitAccuracy] how well the model fits the labels it learns from (null if not
     * known), and the classifier service's weight in it and agreement with the user's labels.
     */
    fun suggestions(
        items: List<Scored>,
        toReview: Int,
        keptApart: Int,
        fitAccuracy: Double?,
        serviceWeight: Double? = null,
        serviceAgreement: Double? = null,
    ): List<Suggestion> {
        if (items.isEmpty()) return emptyList()
        val accuracy = items.count { it.label == it.predicted }.toDouble() / items.size
        val out = mutableListOf<Suggestion>()
        if (toReview > 0) {
            out += Suggestion(
                "Similar texts, different labels from you",
                "$toReview ${if (toReview == 1) "pair reads" else "pairs read"} nearly alike but got different labels from you. Look at them side by side: " +
                    "pick one label for both, or keep both if they're different to you. Kept apart, they show where the model needs more than the words to follow you.",
            )
        }
        // The worst pair of categories it can't tell apart: a limit of what it reads.
        val apart = confusions(items).firstOrNull { it.count >= 3 && it.count.toDouble() / it.ofLabel >= 0.15 }
        if (apart != null) {
            out += Suggestion(
                "It can't yet tell your ${apart.label.label.lowercase()} from ${apart.predicted.label.lowercase()}",
                "Of your ${apart.ofLabel} ${apart.label.label.lowercase()} texts it called ${apart.count} ${apart.predicted.label.lowercase()}. " +
                    "It reads a text's words and a few signals (links, money, the kind of sender, whether you've written back); " +
                    "where your ${apart.label.label.lowercase()} and ${apart.predicted.label.lowercase()} texts use similar words, what makes them different to you " +
                    "(who sent them, when, what came before) is something it doesn't see yet. More examples of both help it find any difference that is in the words." +
                    if (keptApart > 0) " You kept $keptApart ${if (keptApart == 1) "pair" else "pairs"} of alike texts apart: those it can't separate from the words at all." else "",
            )
        }
        val few = categories(items).filter { it.labels in 1 until FEW && it.category != apart?.label }.minByOrNull { it.labels }
        if (few != null) {
            out += Suggestion(
                "Give it more ${few.category.label.lowercase()} to learn from",
                "Only ${few.labels} of your labels are ${few.category.label.lowercase()}, and it gets ${Math.round(few.recall * few.labels)} of them. " +
                    "It learns your sense of a category from your examples of it: a few more of these help it most.",
            )
        }
        if (fitAccuracy != null) {
            out += if (fitAccuracy >= accuracy + 0.06 && fitAccuracy >= 0.95) {
                Suggestion(
                    "A bigger model won't help",
                    "It follows ${pct(fitAccuracy)} of the labels it learns from, but ${pct(accuracy)} of ones it hasn't seen: what holds it back is carrying your labels over to new texts, not room to learn. " +
                        "A deeper or wider network only follows the labels it learns from more closely, which it already does; more of your examples, and signals beyond the words, are what carry over. " +
                        "Some L2 or dropout can help a little.",
                )
            } else {
                Suggestion(
                    "It can't follow all of your labels even while learning them",
                    "Even on the labels it learns from it follows ${pct(fitAccuracy)}. " +
                        (if (toReview + keptApart > 0) "${toReview + keptApart} ${if (toReview + keptApart == 1) "pair" else "pairs"} of nearly identical texts carry different labels from you, and from the words alone it can't follow both: that's a signal it doesn't have yet. " else "") +
                        "Beyond that it may be held back too hard, or too small: try more passes, less L2, or a wider model.",
                )
            }
        }
        if (serviceWeight != null && serviceWeight > 0 && serviceAgreement != null && serviceAgreement < 0.8) {
            out += Suggestion(
                "Your labels outweigh the service's",
                "The classifier service labels your texts differently from you ${pct(1 - serviceAgreement)} of the time, and each of its labels counts ×$serviceWeight here. " +
                    "Where it differs it pulls the model away from you: count its labels for less (×0.1), or leave them out, so your labels set the model.",
            )
        }
        return out
    }

    private fun pct(x: Double) = "${Math.round(x * 100)}%"

    /** Sure enough that a mistake says something about what the model learned. */
    const val SURE = 0.8
    /** Most words shared: the same text, or one template with different details. */
    const val ALIKE = 0.75
    private const val MIN_WORDS = 4
    /** Fewer labels of a category than this, and the model is short of examples of it. */
    const val FEW = 15
}
