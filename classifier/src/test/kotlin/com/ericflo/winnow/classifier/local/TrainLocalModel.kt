package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.ActionPolicy
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.NEEDS_HOOK
import java.io.File
import java.util.Locale
import kotlin.math.ln

/**
 * Builds the bundled model and its metrics: `./gradlew :classifier:trainLocalModel`.
 *
 * 1. 5-fold cross-validation: every corpus message is scored by a model trained on the
 *    other four folds. The temperature is fit on those out-of-fold scores, and the metrics
 *    screen's numbers come from them.
 * 2. The shipped model is trained on the whole corpus with that temperature.
 * 3. The shipped model is also scored on `training/eval.tsv`, which no model ever trains on.
 */
fun main(args: Array<String>) {
    val (corpusDir, evalFile, modelFile, metricsFile, reportFile) = args.map(::File)
    val result = LocalModelBuild.run(corpusDir, evalFile)
    modelFile.parentFile.mkdirs()
    modelFile.outputStream().use(result.model::write)
    metricsFile.writeText(result.metricsJson)
    reportFile.writeText(result.report)
    println(result.report)
}

object LocalModelBuild {
    const val FOLDS = 5

    class Result(
        val model: LocalModel,
        val metrics: ClassifierMetrics,
        val mistakes: List<Pair<LabeledText, Scored>>,
        /** Wanted texts Winnow's own rule would have filtered, out of fold. */
        val wrongfullyFiltered: List<Pair<LabeledText, Scored>> = emptyList(),
    ) {
        val metricsJson: String get() = ClassifierMetrics.json.encodeToString(ClassifierMetrics.serializer(), metrics) + "\n"
        val report: String get() = Report.render(metrics, mistakes, wrongfullyFiltered)
    }

    fun trainer() = LocalModelTrainer(Corpus.classes, bags = BAGS)

    /** Bootstrap models averaged into the shipped one; see [LocalModelTrainer]. */
    const val BAGS = 5

    val unwanted: Set<Int> = Category.entries.filter { it.defaultAction == Action.FILTER }.map { Corpus.classes.indexOf(it.key) }.toSet()

    fun run(corpusDir: File, evalFile: File): Result {
        val corpus = Corpus.load(corpusDir)
        val classes = Corpus.classes

        // Out-of-fold scores (logits) for every message.
        val outOfFold = arrayOfNulls<DoubleArray>(corpus.size)
        Corpus.folds(corpus, FOLDS).forEach { testIndices ->
            val held = testIndices.toSet()
            val model = trainer().train(corpus.filterIndexed { i, _ -> i !in held }.map { it.example(classes) })
            testIndices.forEach { i -> outOfFold[i] = model.scores(model.indices(corpus[i].example(classes).features)) }
        }
        val logits = outOfFold.map { it!! }
        val temperature = (5..60).map { it / 10f }.minBy { t ->
            logits.indices.sumOf { -ln(LocalModel.softmax(logits[it], t.toDouble())[classes.indexOf(corpus[it].category.key)].coerceAtLeast(1e-12)) }
        }
        val lures = corpus.map { Featurizer.hasHook(it.example(classes).features) }
        val rows = logits.mapIndexed { i, s -> Scored(classes.indexOf(corpus[i].category.key), LocalModel.softmax(s, temperature.toDouble()), lures[i]) }

        val full = trainer().train(corpus.map { it.example(classes) })
        val model = LocalModel(classes, full.buckets, full.weights, full.bias, temperature)

        val evaluation = loadEval(evalFile).map { t ->
            val features = Featurizer.features(Featurizer.Input(t.sender, t.body))
            Scored(classes.indexOf(t.category.key), model.predict(features), Featurizer.hasHook(features))
        }
        val metrics = MetricsCalculator.compute(
            model = OnDeviceClassifier.MODEL_NAME,
            method = "$FOLDS-fold cross-validation over ${corpus.size} labeled texts: each was scored by a model that never saw it.",
            classes = classes,
            rows = rows,
            unwanted = unwanted,
            filterAt = ActionPolicy().onDeviceMinConfidence,
            evaluation = MetricsCalculator.summary(classes, evaluation, unwanted).takeIf { evaluation.isNotEmpty() },
            hookless = NEEDS_HOOK.map { classes.indexOf(it.key) }.toSet(),
            quiet = Category.entries.filter { it.defaultAction != Action.ALLOW }.map { classes.indexOf(it.key) }.toSet(),
        )
        val mistakes = corpus.indices.filter { rows[it].predicted != rows[it].label }.map { corpus[it] to rows[it] }
        val hookless = NEEDS_HOOK.map { classes.indexOf(it.key) }.toSet()
        val filterAt = ActionPolicy().onDeviceMinConfidence
        val wrongfullyFiltered = corpus.indices.filter { i ->
            val r = rows[i]
            r.label !in unwanted && r.predicted in unwanted && r.confidence >= filterAt && (r.predicted !in hookless || r.hasHook)
        }.map { corpus[it] to rows[it] }
        return Result(model, metrics, mistakes, wrongfullyFiltered)
    }

    /** `category<TAB>sender<TAB>body` lines, never used for training. */
    fun loadEval(file: File): List<LabeledText> =
        file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val (category, sender, body) = line.split('\t', limit = 3)
            LabeledText(Category.fromKey(category) ?: error("Unknown category $category"), sender, body)
        }
}

private object Report {
    fun render(m: ClassifierMetrics, mistakes: List<Pair<LabeledText, Scored>>, wrongfullyFiltered: List<Pair<LabeledText, Scored>>): String = buildString {
        appendLine("# On-device model report")
        appendLine()
        appendLine("Generated by `./gradlew :classifier:trainLocalModel`. Do not edit by hand.")
        appendLine()
        appendLine("${m.method} The corpus is hand-written, so treat these as an upper bound for real traffic.")
        appendLine()
        appendLine("| Accuracy | Macro F1 | Cohen's κ | MCC | Spam ROC AUC | Avg. precision | Log loss | ECE |")
        appendLine("|---|---|---|---|---|---|---|---|")
        appendLine("| ${pct(m.accuracy)} | ${f(m.macroF1)} | ${f(m.kappa)} | ${f(m.mcc)} | ${f(m.unwanted.auc, 3)} | ${f(m.unwanted.averagePrecision, 3)} | ${f(m.logLoss)} | ${f(m.ece, 3)} |")
        appendLine()
        appendLine("## Per category")
        appendLine()
        appendLine("| Category | Precision | Recall | F1 | ROC AUC | Count |")
        appendLine("|---|---|---|---|---|---|")
        m.perCategory.forEach { c ->
            appendLine("| ${Category.fromKey(c.key)?.label ?: c.key} | ${pct(c.precision)} | ${pct(c.recall)} | ${f(c.f1)} | ${f(c.auc, 3)} | ${c.support} |")
        }
        appendLine()
        appendLine("## Filtering (political, phishing, scam and spam vs. everything else)")
        appendLine()
        appendLine("| Rule | Flagged | Precision | Recall | False positive rate | F1 | MCC | κ |")
        appendLine("|---|---|---|---|---|---|---|---|")
        (m.unwanted.thresholds + m.unwanted.operatingPoint).forEach { r ->
            appendLine("| ${r.label} | ${pct(r.flagged)} | ${pct(r.precision)} | ${pct(r.recall)} | ${pct(r.falsePositiveRate)} | ${f(r.f1)} | ${f(r.mcc)} | ${f(r.kappa)} |")
        }
        appendLine()
        appendLine("Winnow's rule filters an unwanted category at ≥85% confidence, except a scam or phishing text with no hook (a link off the company's real site, money, a number to call, payment or code talk), which it only silences. " +
            "Unwanted texts that never buzzed the phone (filtered or silenced): ${pct(m.unwanted.unwantedQuieted)}. " +
            "Personal and transactional texts that lost their notification: ${pct(m.unwanted.importantMuted)}.")
        if (wrongfullyFiltered.isNotEmpty()) {
            appendLine()
            appendLine("Wanted texts the rule would filter:")
            appendLine()
            wrongfullyFiltered.forEach { (t, s) -> appendLine("- ${t.category.key} → ${m.classes[s.predicted]} (${pct(s.confidence)}): ${t.body.take(100)}") }
        }
        appendLine()
        appendLine("## When it's sure")
        appendLine()
        appendLine("| Confidence at least | Share of texts | Correct |")
        appendLine("|---|---|---|")
        m.coverage.forEach { appendLine("| ${pct(it.threshold)} | ${pct(it.coverage)} | ${pct(it.accuracy)} |") }
        m.evaluation?.let { e ->
            appendLine()
            appendLine("## Evaluation set (`training/eval.tsv`, never trained on; shipped model)")
            appendLine()
            appendLine("${e.examples} texts: accuracy ${pct(e.accuracy)}, macro F1 ${f(e.macroF1)}, κ ${f(e.kappa)}, MCC ${f(e.mcc)}, spam ROC AUC ${f(e.auc, 3)}.")
        }
        appendLine()
        appendLine("## Cross-validation mistakes")
        appendLine()
        mistakes.forEach { (t, s) -> appendLine("- ${t.category.key} → ${m.classes[s.predicted]} (${pct(s.confidence)}): ${t.body.take(100)}") }
    }

    private fun pct(x: Double) = String.format(Locale.US, "%.1f%%", x * 100)
    private fun f(x: Double, digits: Int = 2) = String.format(Locale.US, "%.${digits}f", x)
}
