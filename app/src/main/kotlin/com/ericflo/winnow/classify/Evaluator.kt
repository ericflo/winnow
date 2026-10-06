package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.local.Adjustments
import com.ericflo.winnow.classifier.local.ClassifierMetrics
import com.ericflo.winnow.classifier.local.Correction
import com.ericflo.winnow.classifier.local.Featurizer
import com.ericflo.winnow.classifier.local.LocalModel
import com.ericflo.winnow.classifier.local.MetricsCalculator
import com.ericflo.winnow.classifier.local.PersonalEvaluation
import com.ericflo.winnow.classifier.local.Personalizer
import com.ericflo.winnow.classifier.local.Scored
import com.ericflo.winnow.classifier.local.SenderMemory
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.ActionPolicy
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.data.db.CorrectionEntity
import com.ericflo.winnow.data.db.EvalEntity
import com.ericflo.winnow.data.db.RunAnswerEntity
import com.ericflo.winnow.data.db.VerdictEntity

/** Something that can be scored on the user's labels (see [Evaluator]). */
sealed interface EvalSubject {
    /** How it's stored (EvalEntity.model). */
    val key: String
    val label: String

    /** The model as it is now: the user's labels, their corrections and the service's labels, as Learner fits them. */
    data object Now : EvalSubject {
        override val key = "now"
        override val label = "Winnow's own now"
    }

    /** The model as it ships, before anything was taught. */
    data object Shipped : EvalSubject {
        override val key = EvalEntity.MODEL_BASE
        override val label = "As it ships"
    }

    /** Fitted on the user's labels and corrections alone, nothing from the service. */
    data object YoursOnly : EvalSubject {
        override val key = "variant:yours-only"
        override val label = "Your labels only"
    }

    /** Fitted as now, but with the service's labels counting [weight] of one of the user's. */
    data class ServiceWeight(val weight: Double) : EvalSubject {
        override val key = "variant:service-weight:$weight"
        override val label = "${(weight * 100).toInt()}% weight for the service's labels"
    }

    /** A kept fit of the model (see ModelSnapshots), scored on the labels made since it. */
    data class Kept(val fit: String, val fittedAt: Long, val adjustments: Adjustments, val name: String? = null) : EvalSubject {
        override val key = "fit:$fit"
        override val label = name ?: "Fit $fit"
    }

    /** The classifier service's answers, as recorded: on texts it was asked about and the user has labeled. */
    data class Service(val name: String) : EvalSubject {
        override val key = "provider:recorded"
        override val label = "$name's answers"
    }
}

/** One labeled text an evaluation scored: what it was, and what the subject said. */
data class EvalItem(val key: String, val threadId: Long?, val label: Category, val predicted: Category, val confidence: Double)

data class EvalResult(
    val subject: EvalSubject,
    /** EvalEntity.METHOD_*. */
    val method: String,
    /** How it was scored, in a sentence. */
    val how: String,
    /** Null when there was nothing to score. */
    val metrics: ClassifierMetrics?,
    val items: List<EvalItem>,
)

/** One point of the learning curve: with [taught] of the user's labels learned, how it did on the next [tested]. */
data class CurvePoint(val taught: Int, val tested: Int, val right: Int, val shippedRight: Int, val until: Long)

/**
 * What evaluations read: the user's labels (the answer key), what else trains the model, and the
 * service's recorded answers. Built from the database rows (see [of]).
 */
class EvalData(
    val labels: List<Labeled>,
    /** The user's corrections of whole conversations ("Not spam"), which train every fit and are scored by none. */
    val corrections: List<Dated>,
    /** The service's labels, which train at a weight (see Learner.PROVIDER_WEIGHT). */
    val provider: List<Dated>,
    /** The service's newest recorded answer per text: its category and how sure. */
    val service: Map<String, Pair<Category, Double>>,
) {
    /** One of the user's labels; [sender] is who sent the text, when Winnow has it, and [conversing] whether the user texts with them. */
    class Labeled(val key: String, val threadId: Long, val buckets: IntArray, val label: Int, val createdAt: Long, val sender: String? = null, val conversing: Boolean = false)
    /** [key] is the message it labels, when it's one message's. */
    class Dated(val buckets: IntArray, val label: Int, val createdAt: Long, val key: String? = null)

    companion object {
        /** From what's stored; labels made by an older featurizer mean nothing to this model and are left out. */
        fun of(
            rows: List<CorrectionEntity>,
            verdicts: List<VerdictEntity>,
            answers: List<RunAnswerEntity>,
            model: LocalModel = LocalModel.bundled,
            /** Conversations the user has written in. */
            conversing: Set<Long> = emptySet(),
        ): EvalData {
            fun buckets(e: CorrectionEntity) = e.buckets.split(',').mapNotNull(String::toIntOrNull).toIntArray()
            val current = rows.filter { it.featurizerVersion == Featurizer.VERSION && model.classes.indexOf(it.label) >= 0 }
            val (labels, rest) = current.partition { !it.fromProvider && it.messageKey != null && it.threadId != null && !it.messageKey.startsWith(BackupLabelPrefix) }
            val service = HashMap<String, Pair<Category, Double>>()
            answers.sortedBy { it.answeredAt }.forEach { a -> Category.fromKey(a.category)?.let { service[a.messageKey] = it to a.confidence } }
            verdicts.filter { it.sourceKind == VerdictEntity.KIND_PROVIDER }.forEach { v -> v.category?.let(Category::fromKey)?.let { service[v.messageKey] = it to v.confidence } }
            val senders = verdicts.filter { it.address.isNotBlank() }.associate { it.messageKey to it.address }
            return EvalData(
                labels = labels.map { Labeled(it.messageKey!!, it.threadId!!, buckets(it), model.classes.indexOf(it.label), it.createdAt, senders[it.messageKey], it.threadId in conversing) },
                corrections = rest.filter { !it.fromProvider }.map { Dated(buckets(it), model.classes.indexOf(it.label), it.createdAt) },
                provider = rest.filter { it.fromProvider }.map { Dated(buckets(it), model.classes.indexOf(it.label), it.createdAt, it.messageKey) },
                service = service,
            )
        }

        private const val BackupLabelPrefix = "restored:"
    }
}

/**
 * Scores models on the user's own labels, honestly: a model that learns from them is scored by
 * cross-validation (each conversation's labels by a refit without them); a kept fit only on the
 * labels made after it; the shipped model on all of them (it never saw any); the service on its
 * recorded answers. Pure and deterministic, so the same data scores the same way every time.
 */
class Evaluator(
    private val model: LocalModel = LocalModel.bundled,
    private val policy: ActionPolicy = ActionPolicy(),
    /** How much one of the service's labels counts as the model is fitted now (see WinnowSettings.providerWeight). */
    private val providerWeight: Double = Learner.PROVIDER_WEIGHT,
    /** How much the user's labels of each sender count with the model's answer (see WinnowSettings.senderMemory); 0 for not at all. */
    private val senderMemory: Double = SenderMemory.DEFAULT_STRENGTH,
) {
    private val classes = model.classes
    private val unwanted = Category.entries.filter { it.defaultAction == Action.FILTER }.map { classes.indexOf(it.key) }.filter { it >= 0 }.toSet()

    fun evaluate(subject: EvalSubject, data: EvalData, onProgress: (String) -> Unit = {}, stopped: () -> Boolean = { false }): EvalResult {
        val labels = data.labels.map { PersonalEvaluation.Label(it.buckets, it.label, it.threadId, it.key, it.sender, it.createdAt, it.conversing) }
        val provider = { weight: Double -> if (weight <= 0) emptyList() else data.provider }
        fun others(weight: Double): List<Correction> =
            data.corrections.map { Correction(it.buckets, it.label) } + provider(weight).map { Correction(it.buckets, it.label, weight) }
        fun crossValidated(weight: Double, how: String): EvalResult {
            val keys = data.corrections.map { null } + provider(weight).map { it.key }
            val words = PersonalEvaluation.crossValidateIndexed(model, labels, others(weight), onFold = { f, k -> onProgress("${subject.label}: part ${f + 1} of $k") }, stopped = stopped, othersKeys = keys)
            // Scored as the phone answers: with the user's labels of each sender, from the other conversations.
            val scored = PersonalEvaluation.withSenders(classes, labels, words, senderMemory)
            val leftOut = if (keys.any { it != null }) " The service's labels of the texts being scored are left out of their refit." else ""
            val changed = scored.zip(words).count { (a, b) -> a.second.predicted != b.second.predicted }
            val withSenders = if (senderMemory <= 0 || changed == 0) "" else
                " Your labels of each sender, from the other conversations, changed $changed of its answers, as they would on the phone."
            // Where who sent it counts most: a sender's texts are mostly one conversation, all held out together above.
            onProgress("${subject.label}: your newest labels")
            val newest = PersonalEvaluation.scoreNewest(model, labels, others(weight), keys, senderMemory, stopped = stopped)?.let { n ->
                fun pct(right: Int) = "${Math.round(100.0 * right / n.count)}%"
                " On your newest ${n.count} labels, refit on the ones you made before them: " +
                    when {
                        senderMemory <= 0 -> "${pct(n.words)}."
                        n.withSenders == n.words -> "${pct(n.words)}, the same with or without your labels of each sender."
                        else -> "${pct(n.withSenders)} with your labels of each sender, ${pct(n.words)} from the words alone."
                    }
            }.orEmpty()
            return result(subject, EvalEntity.METHOD_CROSS_VALIDATED, how + leftOut + withSenders + newest, scored.map { (i, s) -> data.labels[i] to s })
        }
        return when (subject) {
            EvalSubject.Now -> crossValidated(
                providerWeight,
                "Each of your labeled texts scored by the model refit without its conversation's labels, as it's fitted now: your labels, your corrections and the service's labels at ${(providerWeight * 100).toInt()}%.",
            )
            EvalSubject.YoursOnly -> crossValidated(0.0, "Cross-validated the same way, fitted on your labels and corrections alone, with nothing the service taught.")
            is EvalSubject.ServiceWeight -> crossValidated(
                subject.weight,
                "Cross-validated the same way, with each of the service's labels counting ${(subject.weight * 100).toInt()}% of one of yours instead of ${(providerWeight * 100).toInt()}%.",
            )
            EvalSubject.Shipped -> result(
                subject, METHOD_UNTAUGHT, "The model as it ships, which never saw any of your labels, on all of them.",
                PersonalEvaluation.scoreWith(model, labels, Adjustments.NONE).mapIndexed { i, s -> data.labels[i] to s },
            )
            is EvalSubject.Kept -> {
                val since = data.labels.withIndex().filter { it.value.createdAt > subject.fittedAt }
                if (since.isNotEmpty()) {
                    result(
                        subject, EvalEntity.METHOD_SINCE, "On the ${since.size} labels you made after this fit: texts it never learned from.",
                        since.map { (i, l) -> l to PersonalEvaluation.scoreWith(model, listOf(labels[i]), subject.adjustments).single() },
                    )
                } else {
                    result(
                        subject, EvalEntity.METHOD_TRAINED_ON, "No labels made since this fit, so on the ones it learned from: not a fair test, only how well it fits them.",
                        PersonalEvaluation.scoreWith(model, labels, subject.adjustments).mapIndexed { i, s -> data.labels[i] to s },
                    )
                }
            }
            is EvalSubject.Service -> result(
                subject, EvalEntity.METHOD_RECORDED,
                "Its answers as recorded, on the texts it was asked about that you've labeled: as texts arrived, and in runs. Each answer counts with how sure it said it was.",
                data.labels.mapNotNull { l -> data.service[l.key]?.let { (c, sure) -> l to pseudo(c, sure) } },
            )
        }
    }

    /**
     * How the model improved as the user labeled, replayed: the labels in the order they were
     * given, cut into [steps]; at each cut, the model fitted on everything taught until then (the
     * user's labels and corrections, and the service's at its weight) is scored on the labels
     * that came next, beside the shipped model on the same ones. Every score is on labels the
     * model hadn't seen. Pure, so it's unit-tested.
     */
    fun learningCurve(data: EvalData, steps: Int = 8, stopped: () -> Boolean = { false }): List<CurvePoint> {
        val ordered = data.labels.sortedBy { it.createdAt }
        if (ordered.size < steps * 2) return emptyList()
        val cuts = (1 until steps).map { it * ordered.size / steps } + ordered.size
        val temperature = model.temperature.toDouble()
        return cuts.zipWithNext().mapNotNull { (cut, next) ->
            if (stopped()) throw java.util.concurrent.CancellationException("curve no longer wanted")
            val until = ordered[cut - 1].createdAt
            val taught = ordered.take(cut).map { Correction(it.buckets, it.label) } +
                data.corrections.filter { it.createdAt <= until }.map { Correction(it.buckets, it.label) } +
                (if (providerWeight <= 0) emptyList() else data.provider.filter { it.createdAt <= until }.map { Correction(it.buckets, it.label, providerWeight) })
            val adjustments = Personalizer.train(model, taught, stopped = stopped)
            val test = ordered.subList(cut, next)
            if (test.isEmpty()) return@mapNotNull null
            fun right(a: Adjustments) = test.count { l -> LocalModel.softmax(model.scoresOf(l.buckets, a), temperature).let { p -> p.indices.maxBy { p[it] } } == l.label }
            CurvePoint(taught = cut, tested = test.size, right = right(adjustments), shippedRight = right(Adjustments.NONE), until = until)
        }
    }

    private fun pseudo(category: Category, sure: Double): Scored {
        val k = classes.size
        val at = classes.indexOf(category.key)
        val rest = ((1 - sure) / (k - 1)).coerceAtLeast(0.0)
        return Scored(-1, DoubleArray(k) { if (it == at) sure else rest })
    }

    private fun result(subject: EvalSubject, method: String, how: String, scored: List<Pair<EvalData.Labeled, Scored>>): EvalResult {
        val rows = scored.map { (l, s) -> Scored(l.label, s.probabilities) }
        val metrics = if (rows.isEmpty()) null else MetricsCalculator.compute(
            model = subject.label, method = how, classes = classes, rows = rows, unwanted = unwanted,
            filterAt = if (subject is EvalSubject.Service) policy.minConfidence else policy.onDeviceMinConfidence,
        )
        val items = scored.mapNotNull { (l, s) ->
            val predicted = Category.fromKey(classes[s.predicted]) ?: return@mapNotNull null
            EvalItem(l.key, l.threadId, Category.fromKey(classes[l.label]) ?: return@mapNotNull null, predicted, s.confidence)
        }
        return EvalResult(subject, method, how, metrics, items)
    }

    companion object {
        /** Scored by a model that never learned from any of the labels. */
        const val METHOD_UNTAUGHT = "untaught"
    }
}
