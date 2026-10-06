package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.SenderKind
import kotlin.math.floor

/**
 * Every text a model scored on the user's labels and got wrong, broken down: by what the user
 * said and what it said, what each kind has in common (a few conversations, one kind of sender,
 * how sure it was), and how far it is to a target share right, with the fewest kinds that would
 * get there. The user's labels are the answer: these are where the model doesn't yet follow them.
 * Pure, so it's unit-tested.
 */
object MissBreakdown {
    /** One scored text: the user's label, the model's answer and how sure it was, and where it came from. */
    data class Item(
        val key: String,
        val threadId: Long?,
        val sender: String?,
        val label: Category,
        val predicted: Category,
        val confidence: Double,
        val text: String? = null,
    )

    /** One kind of miss: what the user said, what it said. */
    data class Group(
        val label: Category,
        val predicted: Category,
        val misses: List<Item>,
        /** How many of the user's labels are [label], and so what share of them this is. */
        val ofLabel: Int,
        /** How many conversations they come from, and the most from any one. */
        val conversations: Int,
        val mostFromOne: Int,
        /** How many came from each kind of sender. */
        val senderKinds: Map<SenderKind, Int>,
        /** How many it was sure of (its words say the other category), and how many were close calls. */
        val sure: Int,
        val close: Int,
    ) {
        val count: Int get() = misses.size
    }

    data class Result(
        val total: Int,
        val misses: Int,
        val target: Double,
        /** The most misses the target allows, and how many more it must get right to reach it (0 when it's there). */
        val allowed: Int,
        val needed: Int,
        /** Biggest first. */
        val groups: List<Group>,
        /** The fewest kinds of miss that, all right, reach the target: each with how many of it that takes. */
        val path: List<Pair<Group, Int>>,
        /** Misses it was under [CLOSE] sure of, across every kind. */
        val closeCalls: Int,
    )

    const val SURE = 0.8
    const val CLOSE = 0.55

    fun of(items: List<Item>, target: Double = 0.9): Result {
        val total = items.size
        val wrong = items.filter { it.label != it.predicted }
        val perLabel = items.groupingBy { it.label }.eachCount()
        val groups = wrong.groupBy { it.label to it.predicted }.map { (pair, misses) ->
            val byThread = misses.mapNotNull { it.threadId }.groupingBy { it }.eachCount()
            Group(
                label = pair.first,
                predicted = pair.second,
                misses = misses.sortedByDescending { it.confidence },
                ofLabel = perLabel[pair.first] ?: misses.size,
                conversations = byThread.size,
                mostFromOne = byThread.values.maxOrNull() ?: 0,
                senderKinds = misses.mapNotNull { m -> m.sender?.let(SenderKind::of) }.groupingBy { it }.eachCount(),
                sure = misses.count { it.confidence >= SURE },
                close = misses.count { it.confidence < CLOSE },
            )
        }.sortedWith(compareByDescending<Group> { it.count }.thenBy { it.label.ordinal }.thenBy { it.predicted.ordinal })
        val allowed = floor(total * (1 - target) + 1e-9).toInt()
        val needed = (wrong.size - allowed).coerceAtLeast(0)
        // Biggest kinds first, the last only as much of it as it takes.
        val path = mutableListOf<Pair<Group, Int>>()
        var left = needed
        for (g in groups) {
            if (left <= 0) break
            val take = minOf(g.count, left)
            path += g to take
            left -= take
        }
        return Result(total, wrong.size, target, allowed, needed, groups, path, wrong.count { it.confidence < CLOSE })
    }
}
