package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.local.LocalModel
import com.ericflo.winnow.classifier.local.SenderMemory
import com.ericflo.winnow.classifier.message.Category

/**
 * What the user's labels say about each sender they've labeled, and what that does to the
 * sender's next text (see SenderMemory): their labels decide it, lean the model's answer, or
 * (with "Who sent it" off) do nothing. Pure, so it's unit-tested; the model screen shows it.
 */
object SenderInsight {
    /** One labeled text: who sent it, the user's category, and its conversation. */
    data class Labeled(val address: String, val category: Category, val threadId: Long)

    /**
     * A sender, as the user has labeled them: [counts] of their texts per category, most first;
     * [decides] the category their labels settle, or null when they only lean; [conversing] when
     * the user texts with them (their labels then only lean). [address] and [threadId] are their
     * most recent labeled text's, for opening the conversation.
     */
    data class Sender(
        val key: String,
        val address: String,
        val threadId: Long,
        val counts: List<Pair<Category, Int>>,
        val decides: Category?,
        val conversing: Boolean,
    ) {
        val total: Int get() = counts.sumOf { it.second }
    }

    /**
     * Every sender in [labels] (newest last), deciders first, then by how many labels.
     * [conversing] are the conversations the user has written in; [strength] is "Who sent it".
     */
    fun of(labels: List<Labeled>, conversing: Set<Long>, strength: Double, classes: List<String> = LocalModel.bundled.classes): List<Sender> {
        val memory = SenderMemory.of(labels.map { it.address to classes.indexOf(it.category.key) }, classes, strength)
        return labels.groupBy { SenderMemory.keyOf(it.address) }.mapNotNull { (key, rows) ->
            key ?: return@mapNotNull null
            val last = rows.last()
            val texting = rows.any { it.threadId in conversing }
            Sender(
                key = key,
                address = last.address,
                threadId = last.threadId,
                counts = rows.groupingBy { it.category }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<Category, Int>> { it.value }.thenBy { it.key.ordinal }).map { it.key to it.value },
                decides = memory.decisive(last.address, conversing = texting)?.let { Category.fromKey(classes[it]) },
                conversing = texting,
            )
        }.sortedWith(compareByDescending<Sender> { it.decides != null }.thenByDescending { it.total }.thenBy { it.key })
    }
}
