package com.ericflo.winnow.classifier.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * How well a classifier sorts messages, from predictions on texts it never trained on.
 * Generated with the bundled model (see `TrainLocalModel`) and shown on the metrics screen.
 *
 * "Unwanted" is the binary question behind filtering: is this a category Winnow filters by
 * default (political, phishing, scam, spam)? Its score is the summed probability of those
 * categories.
 */
@Serializable
data class ClassifierMetrics(
    val model: String,
    /** How the predictions were made, in a sentence. */
    val method: String,
    val classes: List<String>,
    val examples: Int,
    val accuracy: Double,
    val macroF1: Double,
    val weightedF1: Double,
    val kappa: Double,
    val mcc: Double,
    val logLoss: Double,
    val brier: Double,
    /** Expected calibration error of the top category's confidence. */
    val ece: Double,
    val unwanted: BinaryMetrics,
    val perCategory: List<CategoryMetrics>,
    val calibration: List<CalibrationBin>,
    val coverage: List<CoveragePoint>,
    /** Counts, [actual][predicted], in [classes] order. */
    val confusion: List<List<Int>>,
    /** The same model on a separate set it never trained on, if there is one. */
    val evaluation: EvaluationSummary? = null,
) {
    companion object {
        private const val RESOURCE = "/com/ericflo/winnow/classifier/local/winnow-local-metrics.json"
        val json = Json { prettyPrint = true; encodeDefaults = true }

        /** The metrics generated alongside the bundled model. */
        val bundled: ClassifierMetrics by lazy {
            val text = (ClassifierMetrics::class.java.getResourceAsStream(RESOURCE) ?: error("$RESOURCE is missing")).use { it.readBytes().decodeToString() }
            Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), text)
        }
    }
}

/** A curve point; [t] is the score threshold that produces it. */
@Serializable
data class CurvePoint(val x: Double, val y: Double, val t: Double)

@Serializable
data class BinaryMetrics(
    val positives: Int,
    val negatives: Int,
    val auc: Double,
    val averagePrecision: Double,
    /** False positive rate (x) against true positive rate (y). */
    val roc: List<CurvePoint>,
    /** Recall (x) against precision (y). */
    val pr: List<CurvePoint>,
    val thresholds: List<ThresholdRow>,
    /** Where Winnow actually operates with its default policy. */
    val operatingPoint: ThresholdRow,
    /** Unwanted texts that never buzzed the phone: filtered, or silenced when the model was less sure. */
    val unwantedQuieted: Double = 0.0,
    /** Personal and transactional texts that lost their notification (silenced or filtered). */
    val importantMuted: Double = 0.0,
)

@Serializable
data class ThresholdRow(
    val label: String,
    /** Share of all messages flagged. */
    val flagged: Double,
    val precision: Double,
    val recall: Double,
    val falsePositiveRate: Double,
    val f1: Double,
    val mcc: Double,
    val kappa: Double,
)

@Serializable
data class CategoryMetrics(val key: String, val support: Int, val precision: Double, val recall: Double, val f1: Double, val auc: Double)

@Serializable
data class CalibrationBin(val from: Double, val to: Double, val count: Int, val confidence: Double, val accuracy: Double)

/** Of messages whose top category is at least [threshold] sure: what share, and how many right. */
@Serializable
data class CoveragePoint(val threshold: Double, val coverage: Double, val accuracy: Double)

@Serializable
data class EvaluationSummary(val examples: Int, val accuracy: Double, val macroF1: Double, val kappa: Double, val mcc: Double, val auc: Double)

/** One prediction: the true class index, the predicted probabilities, and whether the text has a hook. */
class Scored(val label: Int, val probabilities: DoubleArray, val hasHook: Boolean = true) {
    val predicted: Int get() = probabilities.indices.maxBy { probabilities[it] }
    val confidence: Double get() = probabilities[predicted]
}

object MetricsCalculator {
    /**
     * @param unwanted class indices that count as "should be filtered".
     * @param filterAt the top-category confidence Winnow needs before it filters on its own.
     * @param hookless classes Winnow won't filter without a hook (see [Featurizer.hasHook]).
     */
    fun compute(
        model: String,
        method: String,
        classes: List<String>,
        rows: List<Scored>,
        unwanted: Set<Int>,
        filterAt: Double,
        evaluation: EvaluationSummary? = null,
        hookless: Set<Int> = emptySet(),
        /** Classes whose texts arrive without a notification by default (filtered or silenced). */
        quiet: Set<Int> = unwanted,
        /** Whether a scored text would still notify under the real policy; by default, unless its top class is [quiet]. */
        notifies: (Scored) -> Boolean = { it.predicted !in quiet },
    ): ClassifierMetrics {
        val k = classes.size
        val confusion = confusion(rows, k)
        val perCategory = classes.indices.map { c ->
            val tp = confusion[c][c]
            val predicted = (0 until k).sumOf { confusion[it][c] }
            val actual = confusion[c].sum()
            val precision = ratio(tp, predicted)
            val recall = ratio(tp, actual)
            CategoryMetrics(classes[c], actual, precision, recall, f1(precision, recall), auc(rows.map { it.probabilities[c] to (it.label == c) }))
        }
        val n = rows.size.toDouble()
        val calibration = (0 until 10).map { b ->
            val from = b / 10.0
            val to = (b + 1) / 10.0
            val inBin = rows.filter { it.confidence >= from && (it.confidence < to || b == 9) }
            CalibrationBin(from, to, inBin.size, inBin.map { it.confidence }.averageOr0(), inBin.map { if (it.predicted == it.label) 1.0 else 0.0 }.averageOr0())
        }
        return ClassifierMetrics(
            model = model,
            method = method,
            classes = classes,
            examples = rows.size,
            accuracy = accuracy(rows),
            macroF1 = perCategory.map { it.f1 }.average(),
            weightedF1 = perCategory.sumOf { it.f1 * it.support } / n,
            kappa = kappa(confusion),
            mcc = mcc(confusion),
            logLoss = rows.sumOf { -ln(it.probabilities[it.label].coerceAtLeast(1e-12)) } / n,
            brier = rows.sumOf { r -> r.probabilities.indices.sumOf { c -> val y = if (c == r.label) 1.0 else 0.0; (r.probabilities[c] - y) * (r.probabilities[c] - y) } } / n,
            ece = calibration.sumOf { it.count / n * abs(it.accuracy - it.confidence) },
            unwanted = binary(rows, unwanted, filterAt, hookless).let { b ->
                val important = rows.filter { it.label !in quiet }
                b.copy(
                    unwantedQuieted = rows.filter { it.label in unwanted }.let { u -> if (u.isEmpty()) 0.0 else u.count { !notifies(it) }.toDouble() / u.size },
                    importantMuted = if (important.isEmpty()) 0.0 else important.count { !notifies(it) }.toDouble() / important.size,
                )
            },
            perCategory = perCategory,
            calibration = calibration,
            coverage = listOf(0.5, 0.7, 0.85, 0.9, 0.95).map { t ->
                val sure = rows.filter { it.confidence >= t }
                CoveragePoint(t, sure.size / n, accuracy(sure))
            },
            confusion = confusion.map { it.toList() },
            evaluation = evaluation,
        )
    }

    fun summary(classes: List<String>, rows: List<Scored>, unwanted: Set<Int>): EvaluationSummary {
        val confusion = confusion(rows, classes.size)
        val f1s = classes.indices.map { c ->
            f1(ratio(confusion[c][c], (classes.indices).sumOf { confusion[it][c] }), ratio(confusion[c][c], confusion[c].sum()))
        }
        return EvaluationSummary(rows.size, accuracy(rows), f1s.average(), kappa(confusion), mcc(confusion), auc(unwantedScores(rows, unwanted)))
    }

    private fun binary(rows: List<Scored>, unwanted: Set<Int>, filterAt: Double, hookless: Set<Int>): BinaryMetrics {
        val scored = unwantedScores(rows, unwanted)
        val positives = scored.count { it.second }
        val negatives = scored.size - positives
        fun row(label: String, flag: (Int) -> Boolean): ThresholdRow {
            var tp = 0; var fp = 0; var fn = 0; var tn = 0
            scored.forEachIndexed { i, (_, positive) ->
                val flagged = flag(i)
                when {
                    flagged && positive -> tp++
                    flagged -> fp++
                    positive -> fn++
                    else -> tn++
                }
            }
            val precision = ratio(tp, tp + fp)
            val recall = ratio(tp, tp + fn)
            val matrix = arrayOf(intArrayOf(tn, fp), intArrayOf(fn, tp))
            return ThresholdRow(label, (tp + fp).toDouble() / scored.size, precision, recall, ratio(fp, fp + tn), f1(precision, recall), mcc(matrix), kappa(matrix))
        }
        val thresholds = listOf(0.3, 0.5, 0.7, 0.85, 0.95).map { t -> row("≥ ${(t * 100).toInt()}%") { scored[it].first >= t } }
        // Winnow's actual rule: the top category is a filtered one, it's at least filterAt sure,
        // and a spam text has a hook.
        val operating = row("Winnow's rule") {
            val r = rows[it]
            r.predicted in unwanted && r.confidence >= filterAt && (r.predicted !in hookless || r.hasHook)
        }
        return BinaryMetrics(positives, negatives, auc(scored), averagePrecision(scored), downsample(roc(scored)), downsample(pr(scored)), thresholds, operating)
    }

    private fun unwantedScores(rows: List<Scored>, unwanted: Set<Int>) = rows.map { r -> unwanted.sumOf { r.probabilities[it] } to (r.label in unwanted) }

    /** ROC points from the strictest threshold to the loosest; ties share a point. */
    fun roc(scored: List<Pair<Double, Boolean>>): List<CurvePoint> {
        val p = scored.count { it.second }.coerceAtLeast(1)
        val n = (scored.size - scored.count { it.second }).coerceAtLeast(1)
        val points = mutableListOf(CurvePoint(0.0, 0.0, 1.0))
        var tp = 0; var fp = 0
        scored.sortedByDescending { it.first }.groupBy { it.first }.forEach { (t, group) ->
            group.forEach { if (it.second) tp++ else fp++ }
            points += CurvePoint(fp.toDouble() / n, tp.toDouble() / p, t)
        }
        return points
    }

    fun auc(scored: List<Pair<Double, Boolean>>): Double {
        if (scored.none { it.second } || scored.all { it.second }) return Double.NaN
        return roc(scored).zipWithNext().sumOf { (a, b) -> (b.x - a.x) * (a.y + b.y) / 2 }
    }

    fun pr(scored: List<Pair<Double, Boolean>>): List<CurvePoint> {
        val p = scored.count { it.second }.coerceAtLeast(1)
        val points = mutableListOf<CurvePoint>()
        var tp = 0; var flagged = 0
        scored.sortedByDescending { it.first }.groupBy { it.first }.forEach { (t, group) ->
            group.forEach { flagged++; if (it.second) tp++ }
            points += CurvePoint(tp.toDouble() / p, tp.toDouble() / flagged, t)
        }
        return points
    }

    /** Step-wise average precision: precision at each recall step, weighted by the step. */
    fun averagePrecision(scored: List<Pair<Double, Boolean>>): Double {
        var previousRecall = 0.0
        return pr(scored).sumOf { point -> ((point.x - previousRecall) * point.y).also { previousRecall = point.x } }
    }

    fun kappa(confusion: Array<IntArray>): Double {
        val n = confusion.sumOf { it.sum() }.toDouble()
        if (n == 0.0) return Double.NaN
        val observed = confusion.indices.sumOf { confusion[it][it] } / n
        val expected = confusion.indices.sumOf { c -> confusion[c].sum() / n * confusion.sumOf { it[c] } / n }
        return if (expected == 1.0) Double.NaN else (observed - expected) / (1 - expected)
    }

    /** Matthews correlation, generalized to many classes (Gorodkin's R_K). */
    fun mcc(confusion: Array<IntArray>): Double {
        val s = confusion.sumOf { it.sum() }.toDouble()
        val c = confusion.indices.sumOf { confusion[it][it] }.toDouble()
        val t = confusion.map { it.sum().toDouble() }
        val p = confusion.indices.map { k -> confusion.sumOf { it[k] }.toDouble() }
        val denominator = sqrt((s * s - p.sumOf { it * it }) * (s * s - t.sumOf { it * it }))
        return if (denominator == 0.0) 0.0 else (c * s - t.indices.sumOf { p[it] * t[it] }) / denominator
    }

    private fun confusion(rows: List<Scored>, k: Int) = Array(k) { IntArray(k) }.also { m -> rows.forEach { m[it.label][it.predicted]++ } }

    private fun accuracy(rows: List<Scored>) = if (rows.isEmpty()) 0.0 else rows.count { it.predicted == it.label }.toDouble() / rows.size

    private fun ratio(a: Int, b: Int) = if (b == 0) 0.0 else a.toDouble() / b

    private fun f1(precision: Double, recall: Double) = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)

    private fun List<Double>.averageOr0() = if (isEmpty()) 0.0 else average()

    /** At most [max] points for drawing, always keeping both ends. */
    private fun downsample(points: List<CurvePoint>, max: Int = 120): List<CurvePoint> {
        if (points.size <= max) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return (0 until max).map { points[(it * step).toInt().coerceAtMost(points.size - 1)] }.distinct() + listOf(points.last()).filter { it != points.last() }
    }
}
