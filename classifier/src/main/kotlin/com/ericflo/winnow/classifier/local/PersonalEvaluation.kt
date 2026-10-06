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
    /**
     * One of the user's labels: a message's feature buckets, its category's index, and its
     * conversation; [key] is the message's, [sender] who sent it, when known, and [at] when it
     * was labeled.
     */
    class Label(
        val buckets: IntArray,
        val label: Int,
        val group: Long,
        val key: String? = null,
        val sender: String? = null,
        val at: Long = 0,
        /** The user has texted the sender (see SenderMemory.decisive). */
        val conversing: Boolean = false,
    )

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
        /**
         * The message each of [others] labels (null for none), in [others] order: one on a text
         * being scored is left out of that refit, so a service's label of a text never helps
         * score it.
         */
        othersKeys: List<String?> = emptyList(),
    ): List<Pair<Int, Scored>> {
        val foldOf = foldsOf(labels, folds) ?: return emptyList()
        val k = folds.coerceAtMost(foldOf.size)
        val temperature = base.temperature.toDouble()
        return (0 until k).flatMap { fold ->
            onFold(fold, k)
            val held = labels.withIndex().filter { foldOf.getValue(it.value.group) == fold }
            if (held.isEmpty()) return@flatMap emptyList()
            val heldKeys = held.mapNotNullTo(HashSet()) { it.value.key }
            val kept = if (heldKeys.isEmpty() || othersKeys.isEmpty()) others else others.filterIndexed { j, _ -> othersKeys.getOrNull(j) !in heldKeys }
            val train = labels.filter { foldOf.getValue(it.group) != fold }.map { Correction(it.buckets, it.label) } + kept
            val adjustments = Personalizer.train(base, train, epochs = epochs, l2 = l2, stopped = stopped)
            held.map { (i, l) -> i to Scored(l.label, LocalModel.softmax(base.scores(l.buckets, adjustments), temperature)) }
        }
    }

    /**
     * [scored] (from [crossValidateIndexed] with the same [labels] and [folds]) as the phone
     * answers: with the user's labels of each sender (see SenderMemory) at [strength], each
     * fold's memory built from the other folds' labels only. The same as [scored] at strength 0.
     */
    fun withSenders(classes: List<String>, labels: List<Label>, scored: List<Pair<Int, Scored>>, strength: Double, folds: Int = 5): List<Pair<Int, Scored>> {
        if (strength <= 0.0) return scored
        val foldOf = foldsOf(labels, folds) ?: return scored
        val memories = foldOf.values.distinct().associateWith { fold ->
            SenderMemory.of(labels.filter { foldOf.getValue(it.group) != fold }.mapNotNull { l -> l.sender?.let { it to l.label } }, classes, strength)
        }
        return scored.map { (i, s) ->
            val l = labels[i]
            val memory = memories.getValue(foldOf.getValue(l.group))
            i to (l.sender?.let { Scored(s.label, memory.follow(s.probabilities, it, l.conversing).distribution, s.hasHook) } ?: s)
        }
    }

    /** Of the newest [count] labels, how many a refit on the older ones follows: from the words alone, and with the user's labels of each sender. */
    class Newest(val count: Int, val words: Int, val withSenders: Int)

    /**
     * The newest [share] of [labels] (by [Label.at]; at least [atLeast]) scored by a refit on the
     * older ones (and [others], less any on the same texts), the way the phone meets a text: the
     * model learned from what came before, and the user's labels of each sender among them at
     * [strength]. Null when there are too few to say.
     */
    fun scoreNewest(
        base: LocalModel,
        labels: List<Label>,
        others: List<Correction> = emptyList(),
        othersKeys: List<String?> = emptyList(),
        strength: Double = SenderMemory.DEFAULT_STRENGTH,
        share: Double = 0.2,
        atLeast: Int = 10,
        epochs: Int = Personalizer.EPOCHS,
        l2: Double = Personalizer.L2,
        stopped: () -> Boolean = { false },
    ): Newest? {
        val n = maxOf(atLeast, (labels.size * share).toInt())
        if (labels.size < n * 2) return null
        val byTime = labels.indices.sortedWith(compareBy({ labels[it].at }, { it }))
        val newest = byTime.takeLast(n)
        val older = byTime.dropLast(n).map(labels::get)
        val newestKeys = newest.mapNotNullTo(HashSet()) { labels[it].key }
        val kept = others.filterIndexed { j, _ -> othersKeys.getOrNull(j) !in newestKeys }
        val adjustments = Personalizer.train(base, older.map { Correction(it.buckets, it.label) } + kept, epochs = epochs, l2 = l2, stopped = stopped)
        val memory = SenderMemory.of(older.mapNotNull { l -> l.sender?.let { it to l.label } }, base.classes, strength)
        val temperature = base.temperature.toDouble()
        var words = 0
        var followed = 0
        for (i in newest) {
            val l = labels[i]
            val p = LocalModel.softmax(base.scores(l.buckets, adjustments), temperature)
            if (p.indices.maxBy { p[it] } == l.label) words++
            if ((l.sender?.let { memory.follow(p, it, l.conversing).best } ?: p.indices.maxBy { p[it] }) == l.label) followed++
        }
        return Newest(n, words, followed)
    }

    /** Each conversation's fold, dealt round-robin in a fixed order so a re-run scores the same way; null with fewer than two. */
    internal fun foldsOf(labels: List<Label>, folds: Int): Map<Long, Int>? {
        val groups = labels.map { it.group }.distinct().sorted()
        if (groups.size < 2) return null
        val k = folds.coerceAtMost(groups.size)
        return groups.withIndex().associate { (i, g) -> g to Math.floorMod(LocalModel.fnv1a(g.toString()) + i, k) }
    }

    /** Each label scored by a model that already has [adjustments] (the shipped model, with none): no refitting. */
    fun scoreWith(base: LocalModel, labels: List<Label>, adjustments: Adjustments): List<Scored> {
        val temperature = base.temperature.toDouble()
        return labels.map { l -> Scored(l.label, LocalModel.softmax(base.scores(l.buckets, adjustments), temperature)) }
    }

    /** The metrics screen's numbers, from [labels]; null when there aren't [enough]. */
    fun metrics(
        base: LocalModel,
        labels: List<Label>,
        others: List<Correction>,
        filterAt: Double,
        stopped: () -> Boolean = { false },
        /** See [crossValidateIndexed]. */
        othersKeys: List<String?> = emptyList(),
        /** How much the user's labels of each sender count (see [withSenders]); 0 for the words alone. */
        senderMemory: Double = 0.0,
    ): ClassifierMetrics? {
        if (!enough(labels)) return null
        val rows = withSenders(base.classes, labels, crossValidateIndexed(base, labels, others, stopped = stopped, othersKeys = othersKeys), senderMemory).map { it.second }
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
