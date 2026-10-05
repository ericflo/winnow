package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category

/**
 * How the on-device model does on the user's own labeled texts, honestly: cross-validated, so
 * each label is scored by a model refit without it. Labels are kept a conversation at a time
 * (a conversation's labels are several of its texts, often alike), so none is scored by a model
 * that saw another text of the same conversation. Corrections that aren't labels ("Not spam",
 * "Filter sender") are part of every refit and scored by none.
 */
object PersonalEvaluation {
    /** One of the user's labels: a message's feature buckets, its category's index, and its conversation. */
    class Label(val buckets: IntArray, val label: Int, val group: Long)

    /** Fewer labels than this, or fewer than two categories, and there's nothing worth charting. */
    const val MIN_LABELS = 20

    fun enough(labels: List<Label>): Boolean = labels.size >= MIN_LABELS && labels.map { it.label }.distinct().size >= 2

    /** Each label, scored by a model refit without its conversation. Pure and deterministic. */
    fun crossValidate(base: LocalModel, labels: List<Label>, others: List<Correction> = emptyList(), folds: Int = 5, stopped: () -> Boolean = { false }): List<Scored> =
        crossValidateIndexed(base, labels, others, folds, stopped = stopped).map { it.second }

    /**
     * [crossValidate], each score with its label's index in [labels], so it can be traced back to
     * its text. [weight] is how much a label counts when it trains the others' folds (1 for the
     * user's own); [epochs] and [l2] are the fit's, so a variant can be scored the same way.
     */
    fun crossValidateIndexed(
        base: LocalModel,
        labels: List<Label>,
        others: List<Correction> = emptyList(),
        folds: Int = 5,
        epochs: Int = Personalizer.EPOCHS,
        l2: Double = Personalizer.L2,
        onFold: (Int, Int) -> Unit = { _, _ -> },
        stopped: () -> Boolean = { false },
    ): List<Pair<Int, Scored>> {
        val groups = labels.map { it.group }.distinct().sorted()
        if (groups.size < 2) return emptyList()
        val k = folds.coerceAtMost(groups.size)
        // Conversations dealt round-robin into folds in a fixed order, so a re-run scores the same way.
        val foldOf = groups.withIndex().associate { (i, g) -> g to Math.floorMod(LocalModel.fnv1a(g.toString()) + i, k) }
        val temperature = base.temperature.toDouble()
        return (0 until k).flatMap { fold ->
            onFold(fold, k)
            val held = labels.withIndex().filter { foldOf.getValue(it.value.group) == fold }
            if (held.isEmpty()) return@flatMap emptyList()
            val train = labels.filter { foldOf.getValue(it.group) != fold }.map { Correction(it.buckets, it.label) } + others
            val adjustments = Personalizer.train(base, train, epochs = epochs, l2 = l2, stopped = stopped)
            held.map { (i, l) -> i to Scored(l.label, LocalModel.softmax(base.scores(l.buckets, adjustments), temperature)) }
        }
    }

    /** Each label scored by a model that already has [adjustments] (the shipped model, with none): no refitting. */
    fun scoreWith(base: LocalModel, labels: List<Label>, adjustments: Adjustments): List<Scored> {
        val temperature = base.temperature.toDouble()
        return labels.map { l -> Scored(l.label, LocalModel.softmax(base.scores(l.buckets, adjustments), temperature)) }
    }

    /** The metrics screen's numbers, from [labels]; null when there aren't [enough]. */
    fun metrics(base: LocalModel, labels: List<Label>, others: List<Correction>, filterAt: Double, stopped: () -> Boolean = { false }): ClassifierMetrics? {
        if (!enough(labels)) return null
        val rows = crossValidate(base, labels, others, stopped = stopped)
        if (rows.isEmpty()) return null
        val unwanted = Category.entries.filter { it.defaultAction == com.ericflo.winnow.classifier.message.Action.FILTER }
            .map { base.classes.indexOf(it.key) }.filter { it >= 0 }.toSet()
        return MetricsCalculator.compute(
            model = "your on-phone model",
            method = "Each of your labeled texts was scored by the model refit without its conversation's labels.",
            classes = base.classes,
            rows = rows,
            unwanted = unwanted,
            filterAt = filterAt,
        )
    }
}
