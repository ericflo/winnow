package com.ericflo.winnow.ui.runs

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.data.db.RunEntity

/** One of a run's answers, as its results show it. */
data class AnswerRow(
    val key: String,
    val threadId: Long,
    val address: String,
    val name: String,
    /** What the text said; null if it's gone from the phone since. */
    val text: String?,
    /** The service's answer, its fine-grained kind, and how sure it was. */
    val said: Category,
    val subcategory: String?,
    val confidence: Double,
    /** Sure enough to teach the on-device model. */
    val taught: Boolean,
    /** What the on-device model made of it just before the answer taught it. */
    val before: Category?,
    val beforeConfidence: Double?,
    /** What the on-device model makes of it now. */
    val now: Category?,
    val nowConfidence: Double?,
    /** The user's own label on it, if they've given one. */
    val mine: Category?,
    /** The service's earlier answer, when this run asked again. */
    val previous: Category?,
)

/** What a run's answers add up to. Pure, so it's unit-tested. */
data class RunSummary(
    val answered: Int,
    /** Every category with how many answers said it, in the categories' order. */
    val byCategory: List<Pair<Category, Int>>,
    /** Answers the on-device model had an opinion on just before, and how many it agreed with. */
    val beforeCompared: Int,
    val beforeAgreed: Int,
    /** The same, for the on-device model as it is now. */
    val nowCompared: Int,
    val nowAgreed: Int,
    /** Answers on texts the user has labeled themselves, and how many matched their label. */
    val mineCompared: Int,
    val mineAgreed: Int,
    /** Texts the service had answered before (a redo), and how many it answered differently this time. */
    val askedBefore: Int,
    val changed: Int,
    /** Where the model (before) and the service differed most: (model said, service said, how often), commonest first. */
    val disagreements: List<Triple<Category, Category, Int>>,
) {
    companion object {
        fun of(rows: List<AnswerRow>): RunSummary {
            val counts = rows.groupingBy { it.said }.eachCount()
            val before = rows.filter { it.before != null }
            val now = rows.filter { it.now != null }
            val mine = rows.filter { it.mine != null }
            val again = rows.filter { it.previous != null }
            return RunSummary(
                answered = rows.size,
                byCategory = Category.entries.map { it to (counts[it] ?: 0) },
                beforeCompared = before.size,
                beforeAgreed = before.count { it.before == it.said },
                nowCompared = now.size,
                nowAgreed = now.count { it.now == it.said },
                mineCompared = mine.size,
                mineAgreed = mine.count { it.mine == it.said },
                askedBefore = again.size,
                changed = again.count { it.previous != it.said },
                disagreements = before.filter { it.before != it.said }
                    .groupingBy { it.before!! to it.said }.eachCount()
                    .entries.sortedWith(compareByDescending<Map.Entry<Pair<Category, Category>, Int>> { it.value }.thenBy { it.key.first.ordinal }.thenBy { it.key.second.ordinal })
                    .map { (pair, n) -> Triple(pair.first, pair.second, n) },
            )
        }
    }
}

/** Ways to narrow a run's answers. */
enum class AnswerFilter(val label: String) {
    ALL("All"),
    MODEL_DISAGREED("Model disagreed"),
    STILL_DISAGREES("Model still disagrees"),
    YOU_DISAGREE("Differs from your label"),
    UNSURE("Too unsure to teach"),
    CHANGED("Answer changed"),
    ;

    fun test(row: AnswerRow): Boolean = when (this) {
        ALL -> true
        MODEL_DISAGREED -> row.before != null && row.before != row.said
        STILL_DISAGREES -> row.now != null && row.now != row.said
        YOU_DISAGREE -> row.mine != null && row.mine != row.said
        UNSURE -> !row.taught
        CHANGED -> row.previous != null && row.previous != row.said
    }
}

/** Where a run stands, in a word or two. */
enum class RunState { RUNNING, FINISHED, STOPPED, INTERRUPTED }

/** [run]'s state; [running] when it's the run going on right now. A run with no end that isn't running ended with Winnow. */
fun stateOf(run: RunEntity, running: Boolean): RunState = when {
    running -> RunState.RUNNING
    run.finishedAt == null -> RunState.INTERRUPTED
    run.stopped -> RunState.STOPPED
    else -> RunState.FINISHED
}

/** A run's headline: what it did, in the words its card and results use. */
fun headline(run: RunEntity, state: RunState): String {
    val provider = run.provider.substringBefore(" (")
    return when (state) {
        RunState.RUNNING -> "$provider is labeling: ${run.done} of ${run.planned} texts"
        RunState.FINISHED -> if (run.kind == RunEntity.KIND_REDO) "$provider answered again about ${plural(run.labeled + run.unsure, "text")}"
            else "$provider labeled ${plural(run.labeled, "text")}"
        RunState.STOPPED -> "Stopped after ${run.done} of ${plural(run.planned, "text")}"
        RunState.INTERRUPTED -> "Ended with Winnow after ${run.done} of ${plural(run.planned, "text")}"
    }
}

internal fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "${com.ericflo.winnow.ui.insight.count(n)} ${noun}s"
