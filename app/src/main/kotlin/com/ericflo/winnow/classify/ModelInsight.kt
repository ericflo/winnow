package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.data.db.RunAnswerEntity
import com.ericflo.winnow.data.db.VerdictEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How often two parties said the same category, of the texts both judged. */
data class Pairwise(val compared: Int, val agreed: Int) {
    val rate: Double get() = if (compared == 0) Double.NaN else agreed.toDouble() / compared

    operator fun plus(other: Pairwise) = Pairwise(compared + other.compared, agreed + other.agreed)

    companion object {
        val NONE = Pairwise(0, 0)
        fun of(pairs: List<Pair<Category, Category>>) = Pairwise(pairs.size, pairs.count { it.first == it.second })
    }
}

/**
 * How the three that judge texts line up, pair by pair, each only on texts both of a pair judged:
 * the user (their labels), the classifier service (its answers, as texts arrived and in runs),
 * and the on-device model (its own opinion at the time, before it learned from the answer).
 */
data class Agreement3(
    val youService: Pairwise,
    val youModel: Pairwise,
    val modelService: Pairwise,
    /** Where all three judged a text: how often the model sided with the user against the service, and the other way. */
    val modelWithYouAgainstService: Int = 0,
    val modelWithServiceAgainstYou: Int = 0,
)

/** One week's agreement between the on-device model and the service, as texts arrived. */
data class WeekAgreement(val start: LocalDate, val pair: Pairwise)

/** How the texts that arrived in a window were decided, by who decided. */
data class DeciderCounts(
    val rule: Int = 0,
    val service: Int = 0,
    /** The on-device model: as the only one, sure enough not to ask, standing in for a service that failed, or with nothing allowed to leave the phone. */
    val modelOnly: Int = 0,
    val modelSure: Int = 0,
    val modelFallback: Int = 0,
    val modelKept: Int = 0,
    val modelUnknown: Int = 0,
    val keywords: Int = 0,
    /** Of all of them, how many the user has since labeled or corrected. */
    val youSince: Int = 0,
) {
    val model: Int get() = modelOnly + modelSure + modelFallback + modelKept + modelUnknown
    val total: Int get() = rule + service + model + keywords
}

object ModelInsight {
    /**
     * The three-way agreement from every verdict and every run's answers. A text's service answer
     * is its verdict's when the service decided it, else the newest run's answer about it; the
     * model's opinion is what it said then (kept since verdicts recorded it, and with each run
     * answer). Pure, so it's unit-tested.
     */
    fun agreement(verdicts: List<VerdictEntity>, answers: List<RunAnswerEntity>): Agreement3 {
        val mine = verdicts.mapNotNull { v -> v.userCategory?.let(Category::fromKey)?.let { v.messageKey to it } }.toMap()
        val service = HashMap<String, Category>()
        val model = HashMap<String, Category>()
        // Oldest first, so the newest answer about a text wins.
        answers.sortedBy { it.answeredAt }.forEach { a ->
            Category.fromKey(a.category)?.let { service[a.messageKey] = it }
            a.modelCategory?.let(Category::fromKey)?.let { model[a.messageKey] = it }
        }
        verdicts.forEach { v ->
            if (v.sourceKind == VerdictEntity.KIND_PROVIDER) v.category?.let(Category::fromKey)?.let { service[v.messageKey] = it }
            val opinion = v.localCategory ?: v.category.takeIf { v.sourceKind == VerdictEntity.KIND_LOCAL }
            opinion?.let(Category::fromKey)?.let { if (v.messageKey !in model) model[v.messageKey] = it }
        }
        fun pairs(a: Map<String, Category>, b: Map<String, Category>) = a.mapNotNull { (k, x) -> b[k]?.let { x to it } }
        val all3 = mine.keys.filter { it in service && it in model }
        return Agreement3(
            youService = Pairwise.of(pairs(mine, service)),
            youModel = Pairwise.of(pairs(mine, model)),
            modelService = Pairwise.of(pairs(model, service)),
            modelWithYouAgainstService = all3.count { k -> model[k] == mine[k] && service[k] != mine[k] },
            modelWithServiceAgainstYou = all3.count { k -> model[k] == service[k] && mine[k] != service[k] },
        )
    }

    /**
     * Week by week, how often the on-device model's own opinion matched the service's answer on
     * texts the service decided as they arrived: whether it's coming to sort the way the service
     * does. Only weeks with something to compare. Pure, so it's unit-tested.
     */
    fun weekly(verdicts: List<VerdictEntity>, zone: ZoneId = ZoneId.systemDefault()): List<WeekAgreement> =
        verdicts.filter { it.sourceKind == VerdictEntity.KIND_PROVIDER && it.atArrival && it.localCategory != null && it.category != null }
            .groupBy { Instant.ofEpochMilli(it.decidedAt).atZone(zone).toLocalDate().let { d -> d.minusDays((d.dayOfWeek.value - 1).toLong()) } }
            .toSortedMap()
            .map { (week, rows) -> WeekAgreement(week, Pairwise(rows.size, rows.count { it.localCategory == it.category })) }

    /** Who decided the texts that arrived since [since] (see [DeciderCounts]). Pure, so it's unit-tested. */
    fun deciders(verdicts: List<VerdictEntity>, since: Long): DeciderCounts {
        var c = DeciderCounts()
        for (v in verdicts) {
            if (!v.atArrival || v.decidedAt < since) continue
            c = when (Provenance.decider(v)) {
                Decider.RULE, Decider.YOU -> c.copy(rule = c.rule + 1)
                Decider.PROVIDER -> c.copy(service = c.service + 1)
                Decider.FALLBACK -> c.copy(keywords = c.keywords + 1)
                Decider.MODEL -> when (Provenance.modelReason(v)) {
                    ModelReason.ONLY_ONE -> c.copy(modelOnly = c.modelOnly + 1)
                    ModelReason.SURE -> c.copy(modelSure = c.modelSure + 1)
                    ModelReason.PROVIDER_FAILED -> c.copy(modelFallback = c.modelFallback + 1)
                    ModelReason.KEPT_ON_PHONE -> c.copy(modelKept = c.modelKept + 1)
                    ModelReason.UNKNOWN, null -> c.copy(modelUnknown = c.modelUnknown + 1)
                }
            }
            if (v.userCategory != null || v.userAction != null) c = c.copy(youSince = c.youSince + 1)
        }
        return c
    }

    /** The start of the day [days] before today, for a window of that many days. */
    fun windowStart(days: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.now(zone).minus(days - 1, ChronoUnit.DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
}
