package com.ericflo.winnow.classifier.local

import java.io.File
import java.util.Locale
import kotlin.math.ln

/** Grid-searches trainer settings by cross-validated macro F1 and log loss: `./gradlew :classifier:tuneLocalModel`. */
fun main() {
    val corpus = Corpus.load(File("training/corpus"))
    val classes = Corpus.classes
    val examples = corpus.map { it.example(classes) }
    val folds = Corpus.folds(corpus, LocalModelBuild.FOLDS)
    val results = mutableListOf<Triple<String, Double, Double>>()
    for (buckets in listOf(1 shl 15, 1 shl 17)) for (epochs in listOf(15, 30, 60)) for (lr in listOf(0.2, 0.5, 1.0)) for (l2 in listOf(0.0, 1e-6, 1e-5, 1e-4)) {
        val logits = arrayOfNulls<DoubleArray>(corpus.size)
        folds.forEach { test ->
            val held = test.toSet()
            val model = LocalModelTrainer(classes, buckets, epochs, lr, l2).train(examples.filterIndexed { i, _ -> i !in held })
            test.forEach { i -> logits[i] = model.scores(model.indices(examples[i].features)) }
        }
        val t = (5..60).map { it / 10.0 }.minBy { t -> examples.indices.sumOf { -ln(LocalModel.softmax(logits[it]!!, t)[examples[it].label].coerceAtLeast(1e-12)) } }
        val rows = examples.indices.map { Scored(examples[it].label, LocalModel.softmax(logits[it]!!, t)) }
        val m = MetricsCalculator.compute("tune", "", classes, rows, LocalModelBuild.unwanted, 0.85)
        val name = "buckets=2^${Integer.numberOfTrailingZeros(buckets)} epochs=$epochs lr=$lr l2=$l2"
        results += Triple(name, m.macroF1, m.logLoss)
        println(String.format(Locale.US, "%-45s F1 %.4f  logloss %.4f  acc %.4f", name, m.macroF1, m.logLoss, m.accuracy))
    }
    println("\nBest by F1:")
    results.sortedByDescending { it.second }.take(8).forEach { println(String.format(Locale.US, "%-45s F1 %.4f  logloss %.4f", it.first, it.second, it.third)) }
}
