package com.ericflo.winnow.classifier.local

import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Wide & deep: the linear model's logits plus a one-hidden-layer network over the same hashed
 * features. Compared with the linear model by cross-validation:
 * `./gradlew :classifier:deepExperiment`. Dev aid; nothing here ships unless it wins.
 */
private class WideDeep(val k: Int, val buckets: Int, val hidden: Int, seed: Int) {
    val wide = FloatArray(buckets * k)
    val wideBias = FloatArray(k)
    val w1 = FloatArray(buckets * hidden)
    val b1 = FloatArray(hidden)
    val w2 = FloatArray(hidden * k)
    val b2 = FloatArray(k)

    init {
        val r = Random(seed)
        for (i in w1.indices) w1[i] = ((r.nextDouble() * 2 - 1) * 0.05).toFloat()
        for (i in w2.indices) w2[i] = ((r.nextDouble() * 2 - 1) * sqrt(2.0 / hidden)).toFloat()
    }

    fun forward(idx: IntArray, h: DoubleArray): DoubleArray {
        val v = LocalModel.featureValue(idx.size)
        for (j in 0 until hidden) {
            var a = b1[j].toDouble()
            for (i in idx) a += w1[i * hidden + j] * v
            h[j] = if (a > 0) a else 0.0
        }
        return DoubleArray(k) { c ->
            var s = wideBias[c].toDouble() + b2[c]
            for (i in idx) s += wide[i * k + c] * v
            for (j in 0 until hidden) s += w2[j * k + c] * h[j]
            s
        }
    }
}

private fun trainWideDeep(examples: List<Pair<IntArray, Int>>, k: Int, classWeight: DoubleArray, hidden: Int, epochs: Int, lr: Double, l2: Double, seed: Int): WideDeep {
    val m = WideDeep(k, 1 shl 15, hidden, seed)
    val gWide = FloatArray(m.wide.size) { 1e-8f }
    val gWideBias = DoubleArray(k) { 1e-8 }
    val gW1 = FloatArray(m.w1.size) { 1e-8f }
    val gB1 = DoubleArray(hidden) { 1e-8 }
    val gW2 = DoubleArray(m.w2.size) { 1e-8 }
    val gB2 = DoubleArray(k) { 1e-8 }
    val r = Random(seed)
    val order = examples.indices.toMutableList()
    val h = DoubleArray(hidden)
    fun step(g: Double, acc: Double): Pair<Double, Double> { val a = acc + g * g; return lr * g / sqrt(a) to a }
    repeat(epochs) {
        order.shuffle(r)
        for (n in order) {
            val (idx, label) = examples[n]
            val v = LocalModel.featureValue(idx.size)
            val p = LocalModel.softmax(m.forward(idx, h))
            val d = DoubleArray(k) { c -> (p[c] - if (c == label) 1.0 else 0.0) * classWeight[label] }
            // Back into the hidden layer before W2 changes.
            val dh = DoubleArray(hidden) { j -> if (h[j] <= 0) 0.0 else (0 until k).sumOf { c -> d[c] * m.w2[j * k + c] } }
            for (c in 0 until k) {
                val (s1, a1) = step(d[c], gWideBias[c]); gWideBias[c] = a1; m.wideBias[c] -= s1.toFloat()
                val (s2, a2) = step(d[c], gB2[c]); gB2[c] = a2; m.b2[c] -= s2.toFloat()
                for (i in idx) {
                    val j = i * k + c
                    val g = d[c] * v + l2 * m.wide[j]
                    gWide[j] += (g * g).toFloat(); m.wide[j] -= (lr * g / sqrt(gWide[j].toDouble())).toFloat()
                }
                for (j in 0 until hidden) {
                    val q = j * k + c
                    val g = d[c] * h[j] + l2 * m.w2[q]
                    gW2[q] += g * g; m.w2[q] -= (lr * g / sqrt(gW2[q])).toFloat()
                }
            }
            for (j in 0 until hidden) {
                if (dh[j] == 0.0) continue
                val (s, a) = step(dh[j], gB1[j]); gB1[j] = a; m.b1[j] -= s.toFloat()
                for (i in idx) {
                    val q = i * hidden + j
                    val g = dh[j] * v + l2 * m.w1[q]
                    gW1[q] += (g * g).toFloat(); m.w1[q] -= (lr * g / sqrt(gW1[q].toDouble())).toFloat()
                }
            }
        }
    }
    return m
}

fun main() {
    val corpus = Corpus.load(File("training/corpus"))
    val classes = Corpus.classes
    val k = classes.size
    val unwanted = LocalModelBuild.unwanted
    val shape = LocalModel(classes, 1 shl 15, FloatArray((1 shl 15) * k), FloatArray(k))
    val encoded = corpus.map { shape.indices(it.example(classes).features) to classes.indexOf(it.category.key) }
    val folds = Corpus.folds(corpus, LocalModelBuild.FOLDS)
    val labels = encoded.map { it.second }

    fun evaluate(name: String, logits: List<DoubleArray>) {
        val t = (5..60).map { it / 10.0 }.minBy { t -> labels.indices.sumOf { -ln(LocalModel.softmax(logits[it], t)[labels[it]].coerceAtLeast(1e-12)) } }
        val rows = logits.mapIndexed { i, s -> Scored(labels[i], LocalModel.softmax(s, t)) }
        val m = MetricsCalculator.compute("x", "", classes, rows, unwanted, 0.85)
        val op = m.unwanted.operatingPoint
        println(String.format(Locale.US, "%-34s acc %.3f  F1 %.3f  κ %.3f  AUC %.4f  logloss %.3f  rule FPR %.2f%% recall %.1f%%",
            name, m.accuracy, m.macroF1, m.kappa, m.unwanted.auc, m.logLoss, op.falsePositiveRate * 100, op.recall * 100))
    }

    val linear = arrayOfNulls<DoubleArray>(corpus.size)
    folds.forEach { test ->
        val held = test.toSet()
        val model = LocalModelBuild.trainer().train(corpus.filterIndexed { i, _ -> i !in held }.map { it.example(classes) })
        test.forEach { i -> linear[i] = model.scores(encoded[i].first) }
    }
    evaluate("linear (shipping)", linear.map { it!! })

    for ((hidden, epochs, lr) in listOf(Triple(16, 30, 0.1), Triple(32, 30, 0.1), Triple(32, 60, 0.05), Triple(64, 30, 0.1))) {
        val out = arrayOfNulls<DoubleArray>(corpus.size)
        folds.forEach { test ->
            val held = test.toSet()
            val train = encoded.filterIndexed { i, _ -> i !in held }
            val counts = IntArray(k).also { c -> train.forEach { c[it.second]++ } }
            val cw = DoubleArray(k) { train.size.toDouble() / (k * counts[it]) }
            val m = trainWideDeep(train, k, cw, hidden, epochs, lr, 1e-5, 42)
            val h = DoubleArray(hidden)
            test.forEach { i -> out[i] = m.forward(encoded[i].first, h) }
        }
        evaluate("wide&deep h=$hidden e=$epochs lr=$lr", out.map { it!! })
    }
}
