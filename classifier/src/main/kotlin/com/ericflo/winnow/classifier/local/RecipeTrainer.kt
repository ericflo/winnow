package com.ericflo.winnow.classifier.local

import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** What kind of model a [Recipe] trains. */
enum class RecipeKind(val label: String) {
    /** The shipped model with a personal layer on top: what Winnow does by default. */
    PERSONAL("Personal layer"),

    /** A linear model trained from scratch on the phone: the shipped examples and your labels. */
    LINEAR("Linear, retrained"),

    /** A neural network trained from scratch on the phone (see [NeuralModel]). */
    NEURAL("Neural network"),
}

/**
 * How to train an on-device model: its kind and capacity, how it's fitted, and what it learns
 * from, with how much each counts. Every number the user can change, and all of it kept with
 * the model it made, so a result always says exactly what produced it.
 */
@Serializable
data class Recipe(
    val kind: RecipeKind = RecipeKind.PERSONAL,
    /** Feature buckets (a power of two): more is wider, fewer words sharing one. Fixed for the personal layer. */
    val buckets: Int = 1 shl 15,
    /** Neural only: the embedding's width, then each hidden layer's. More layers is deeper. */
    val layers: List<Int> = listOf(64),
    /** Neural only: a linear part beside the network ("wide & deep"). */
    val wide: Boolean = true,
    /** Passes over the training texts. */
    val epochs: Int = Personalizer.EPOCHS,
    /** The step size (AdaGrad's, for every kind). */
    val learningRate: Double = Personalizer.LEARNING_RATE,
    /** The pull of every weight toward zero. */
    val l2: Double = Personalizer.L2,
    /** Neural only: the share of hidden units left out of each step, against memorizing. */
    val dropout: Double = 0.0,
    /** Linear only: models trained on resamples and averaged. */
    val bags: Int = 1,
    /** Retrained kinds: learn from the texts the shipped model learned from too, and how much each counts. */
    val includeCorpus: Boolean = true,
    val corpusWeight: Double = 1.0,
    /** How much each of the user's labels counts (the personal layer always takes them at 1). */
    val userWeight: Double = 1.0,
    /** How much each of the classifier service's labels counts; 0 leaves them out. */
    val serviceWeight: Double = 0.35,
    /** Weight the categories so a rare one counts as much as a common one. */
    val balance: Boolean = true,
    /**
     * How much the user's labels of a sender count with the model's answer for their next texts
     * (see SenderMemory): 0 leaves them out. Enough of them, one way, decide.
     */
    val senderMemory: Double = SenderMemory.DEFAULT_STRENGTH,
    val seed: Int = 42,
) {
    /** What's wrong with it, if anything, in words; null when it can be trained. */
    fun problem(): String? = when {
        buckets < 1 shl 10 || buckets > 1 shl 18 || buckets and (buckets - 1) != 0 -> "Buckets must be a power of two from 1,024 to 262,144."
        kind == RecipeKind.NEURAL && layers.isEmpty() -> "A network needs at least its embedding layer."
        kind == RecipeKind.NEURAL && layers.any { it !in 2..512 } -> "Each layer can be 2 to 512 wide."
        kind == RecipeKind.NEURAL && layers.size > 4 -> "At most four layers."
        // Weights and their step sizes, 8 bytes each: past this a phone runs short of memory.
        kind == RecipeKind.NEURAL && buckets.toLong() * layers[0] > MAX_EMBEDDING -> "Buckets × embedding width can be at most ${MAX_EMBEDDING / 1_000_000}M: fewer buckets, or a narrower embedding."
        epochs !in 1..500 -> "Passes must be 1 to 500."
        learningRate <= 0 || learningRate > 10 -> "The step must be above 0 and at most 10."
        l2 < 0 || l2 > 1 -> "L2 must be 0 to 1."
        dropout < 0 || dropout >= 0.9 -> "Dropout must be 0 to 0.9."
        bags !in 1..10 -> "Bags must be 1 to 10."
        userWeight <= 0 || userWeight > 100 || serviceWeight < 0 || serviceWeight > 100 || corpusWeight < 0 || corpusWeight > 100 -> "Weights must be 0 to 100 (yours above 0)."
        senderMemory < 0 || senderMemory > 4 -> "Who sent it must count 0 to 4."
        else -> null
    }

    /** A short name for what it is: "Neural 64→32, 65,536 buckets". */
    fun describe(): String = when (kind) {
        RecipeKind.PERSONAL -> "Personal layer, $epochs passes, step $learningRate, L2 $l2"
        RecipeKind.LINEAR -> "Linear, ${"%,d".format(buckets)} buckets, $epochs passes" + (if (bags > 1) ", $bags bags" else "")
        RecipeKind.NEURAL -> "Neural ${layers.joinToString("→")}" + (if (wide) " + wide" else "") + ", ${"%,d".format(buckets)} buckets, $epochs passes"
    }

    companion object {
        const val MAX_EMBEDDING = 4_194_304L

        // In the order they did on the hand-written corpus with labels that words alone don't
        // separate (`:classifier:labCeilingExperiment`): one layer did as well as more, and deeper worse.
        val PRESETS = listOf(
            "As Winnow does now" to Recipe(),
            "Retrained, wider" to Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 16, epochs = 60, learningRate = 0.2, l2 = 1e-5, userWeight = 3.0),
            "Neural, wider" to Recipe(kind = RecipeKind.NEURAL, buckets = 1 shl 16, layers = listOf(64), wide = true, epochs = 20, learningRate = 0.05, l2 = 1e-6, userWeight = 3.0),
            "Neural, deeper" to Recipe(kind = RecipeKind.NEURAL, layers = listOf(64, 32), wide = true, epochs = 20, learningRate = 0.05, l2 = 1e-6, dropout = 0.1, userWeight = 3.0),
        )
    }
}

/**
 * One text to learn from: its features (or, for one whose text is gone, its buckets in the shipped
 * model's space), its class, how much it counts, and its conversation (-1 for the shipped
 * examples), so cross-validation keeps a conversation's texts together. [key] is its message (so
 * a classifier service's label on a text held out isn't trained on), [sender] who sent it (for
 * the user's labels of each sender, see SenderMemory, [conversing] when the user texts with
 * them), and [at] when it came.
 */
class TrainingItem(
    val features: List<String>?,
    val baseIndices: IntArray?,
    val label: Int,
    val weight: Double,
    val group: Long = -1,
    val key: String? = null,
    val sender: String? = null,
    val at: Long = 0,
    val conversing: Boolean = false,
)

/** Trains, scores and calibrates models from [Recipe]s. Pure and deterministic. */
object RecipeTrainer {
    /**
     * A model trained on [items] as [recipe] says. [base] is the shipped model, which the
     * personal layer sits on (and whose class order every kind keeps). [onProgress] hears each
     * pass; [stopped] ends a training nobody wants any more, with a CancellationException.
     */
    fun train(
        recipe: Recipe,
        base: LocalModel,
        items: List<TrainingItem>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        stopped: () -> Boolean = { false },
    ): Predictor {
        recipe.problem()?.let { throw IllegalArgumentException(it) }
        return when (recipe.kind) {
            RecipeKind.PERSONAL -> {
                val corrections = items.mapNotNull { it.indicesIn(base.buckets)?.let { idx -> Correction(idx, it.label, it.weight.coerceAtMost(1.0)) } }
                onProgress(0, 1)
                LinearPredictor(base, Personalizer.train(base, corrections, recipe.epochs, recipe.learningRate, recipe.l2, stopped))
            }
            RecipeKind.LINEAR -> LinearPredictor(trainLinear(recipe, base.classes, items, onProgress, stopped))
            RecipeKind.NEURAL -> trainNeural(recipe, base.classes, items, onProgress, stopped)
        }
    }

    /** The class scores [predictor] gives one item, before the softmax (with [Predictor]'s own temperature undone where it has one). */
    fun logits(predictor: Predictor, item: TrainingItem): DoubleArray? = when (predictor) {
        is LinearPredictor -> item.indicesIn(predictor.model.buckets)?.let { predictor.model.scoresOf(it, predictor.adjustments) }
        is NeuralModel -> item.indicesIn(predictor.buckets)?.let { predictor.scores(it) }
        else -> null
    }

    /** The temperature that makes [scored] (logits and the true class) most likely, from a coarse grid. */
    fun calibrate(scored: List<Pair<DoubleArray, Int>>): Float {
        if (scored.size < 10) return 1f
        return (3..60).map { it / 10f }.minBy { t -> scored.sumOf { (s, label) -> -ln(LocalModel.softmax(s, t.toDouble())[label].coerceAtLeast(1e-12)) } }
    }

    /**
     * Each of [scoredItems] scored by a model trained without its conversation (as [recipe] says,
     * on everything else too: [others] always train), as logits with its index. [folds]
     * conversations-wise, in a fixed order, so a re-run scores the same.
     */
    fun crossValidate(
        recipe: Recipe,
        base: LocalModel,
        scoredItems: List<TrainingItem>,
        others: List<TrainingItem>,
        folds: Int = 5,
        onFold: (Int, Int) -> Unit = { _, _ -> },
        stopped: () -> Boolean = { false },
    ): List<Pair<Int, DoubleArray>> {
        val foldOf = foldsOf(scoredItems, folds) ?: return emptyList()
        val k = foldOf.max() + 1
        return (0 until k).flatMap { fold ->
            onFold(fold, k)
            val held = scoredItems.indices.filter { foldOf[it] == fold }
            if (held.isEmpty()) return@flatMap emptyList()
            // Not a service's label on a text being held out either: that would be training on the answer.
            val heldKeys = held.mapNotNullTo(HashSet()) { scoredItems[it].key }
            val train = scoredItems.filterIndexed { i, _ -> foldOf[i] != fold } + others.filter { it.key == null || it.key !in heldKeys }
            val model = train(recipe, base, train, stopped = stopped)
            held.mapNotNull { i -> logits(model, scoredItems[i])?.let { i to it } }
        }
    }

    /**
     * Each of [scoredItems]'s fold, conversations kept together, in a fixed order ([crossValidate]
     * scores each fold by a model trained without it); null when there are fewer than two conversations.
     */
    fun foldsOf(scoredItems: List<TrainingItem>, folds: Int = 5): IntArray? {
        val groups = scoredItems.map { it.group }.distinct().sorted()
        if (groups.size < 2) return null
        val k = folds.coerceAtMost(groups.size)
        val foldOf = groups.withIndex().associate { (i, g) -> g to Math.floorMod(LocalModel.fnv1a(g.toString()) + i, k) }
        return IntArray(scoredItems.size) { foldOf.getValue(scoredItems[it].group) }
    }

    /**
     * The newest [share] of [scoredItems] (by [TrainingItem.at]; at least [atLeast]) scored by a
     * model trained on the older ones (and [others], less any on the same texts): how it does on
     * texts that come after what it learned from, senders it has seen among them. Their indices
     * and logits, or empty when there are too few to say.
     */
    fun scoreNewest(
        recipe: Recipe,
        base: LocalModel,
        scoredItems: List<TrainingItem>,
        others: List<TrainingItem>,
        share: Double = 0.2,
        atLeast: Int = 10,
        stopped: () -> Boolean = { false },
    ): List<Pair<Int, DoubleArray>> {
        val n = maxOf(atLeast, (scoredItems.size * share).toInt())
        if (scoredItems.size < n * 2) return emptyList()
        val byTime = scoredItems.indices.sortedWith(compareBy({ scoredItems[it].at }, { it }))
        val newest = byTime.takeLast(n)
        val newestSet = newest.toHashSet()
        val newestKeys = newest.mapNotNullTo(HashSet()) { scoredItems[it].key }
        val train = scoredItems.filterIndexed { i, _ -> i !in newestSet } + others.filter { it.key == null || it.key !in newestKeys }
        val model = train(recipe, base, train, stopped = stopped)
        return newest.mapNotNull { i -> logits(model, scoredItems[i])?.let { i to it } }
    }

    private fun TrainingItem.indicesIn(buckets: Int): IntArray? = when {
        features != null -> features.mapTo(LinkedHashSet()) { LocalModel.fnv1a(it) and (buckets - 1) }.toIntArray()
        // Kept buckets are the shipped model's: they mean the same only to a model of its size.
        baseIndices != null && buckets == LocalModel.bundled.buckets -> baseIndices
        else -> null
    }

    private fun classWeights(items: List<Pair<IntArray, TrainingItem>>, k: Int, balance: Boolean): DoubleArray {
        if (!balance) return DoubleArray(k) { 1.0 }
        val total = DoubleArray(k).also { w -> items.forEach { w[it.second.label] += it.second.weight } }
        val sum = total.sum()
        return DoubleArray(k) { if (total[it] == 0.0) 0.0 else sum / (k * total[it]) }
    }

    private fun trainLinear(recipe: Recipe, classes: List<String>, items: List<TrainingItem>, onProgress: (Int, Int) -> Unit, stopped: () -> Boolean): LocalModel {
        val k = classes.size
        val b = recipe.buckets
        val encoded = items.mapNotNull { item -> item.indicesIn(b)?.takeIf { it.isNotEmpty() }?.let { it to item } }
        fun one(sample: List<Pair<IntArray, TrainingItem>>, seed: Int, bag: Int): Pair<DoubleArray, DoubleArray> {
            val classWeight = classWeights(sample, k, recipe.balance)
            val w = DoubleArray(b * k)
            val bias = DoubleArray(k)
            val gw = DoubleArray(b * k) { 1e-8 }
            val gb = DoubleArray(k) { 1e-8 }
            val random = Random(seed)
            val order = sample.indices.toMutableList()
            repeat(recipe.epochs) { epoch ->
                if (stopped()) throw java.util.concurrent.CancellationException("training no longer wanted")
                onProgress(bag * recipe.epochs + epoch, recipe.bags * recipe.epochs)
                order.shuffle(random)
                for (n in order) {
                    val (indices, item) = sample[n]
                    val value = LocalModel.featureValue(indices.size)
                    val scores = DoubleArray(k) { bias[it] }
                    for (i in indices) for (c in 0 until k) scores[c] += w[i * k + c] * value
                    val p = LocalModel.softmax(scores)
                    val scale = classWeight[item.label] * item.weight
                    for (c in 0 until k) {
                        val g = (p[c] - if (c == item.label) 1.0 else 0.0) * scale
                        gb[c] += g * g
                        bias[c] -= recipe.learningRate * g / sqrt(gb[c])
                        for (i in indices) {
                            val j = i * k + c
                            val gj = g * value + recipe.l2 * w[j]
                            gw[j] += gj * gj
                            w[j] -= recipe.learningRate * gj / sqrt(gw[j])
                        }
                    }
                }
            }
            return w to bias
        }
        val runs = (0 until recipe.bags).map { bag ->
            val sample = if (recipe.bags == 1) encoded else Random(recipe.seed * 1000 + bag).let { r -> List(encoded.size) { encoded[r.nextInt(encoded.size)] } }
            one(sample, recipe.seed + bag, bag)
        }
        val weights = FloatArray(b * k) { i -> (runs.sumOf { it.first[i] } / runs.size).toFloat() }
        val bias = FloatArray(k) { c -> (runs.sumOf { it.second[c] } / runs.size).toFloat() }
        return LocalModel(classes, b, weights, bias)
    }

    private fun trainNeural(recipe: Recipe, classes: List<String>, items: List<TrainingItem>, onProgress: (Int, Int) -> Unit, stopped: () -> Boolean): NeuralModel {
        val m = NeuralModel.fresh(classes, recipe.buckets, recipe.layers, recipe.wide, recipe.seed)
        val k = classes.size
        val layers = recipe.layers
        val e = layers[0]
        val encoded = items.mapNotNull { item -> item.indicesIn(recipe.buckets)?.takeIf { it.isNotEmpty() }?.let { it to item } }
        val classWeight = classWeights(encoded, k, recipe.balance)
        val lr = recipe.learningRate
        val l2 = recipe.l2
        // AdaGrad's running sums, one per weight.
        val gEmb = FloatArray(m.embedding.size) { 1e-8f }
        val gEmbBias = DoubleArray(e) { 1e-8 }
        val gDense = m.dense.map { FloatArray(it.size) { 1e-8f } }
        val gDenseBias = m.denseBias.map { DoubleArray(it.size) { 1e-8 } }
        val gOut = DoubleArray(m.out.size) { 1e-8 }
        val gOutBias = DoubleArray(k) { 1e-8 }
        val gWide = FloatArray(m.wideWeights.size) { 1e-8f }
        val random = Random(recipe.seed)
        val order = encoded.indices.toMutableList()
        val keepScale = 1.0 / (1.0 - recipe.dropout)
        repeat(recipe.epochs) { epoch ->
            if (stopped()) throw java.util.concurrent.CancellationException("training no longer wanted")
            onProgress(epoch, recipe.epochs)
            order.shuffle(random)
            for (n in order) {
                val (idx, item) = encoded[n]
                val v = LocalModel.featureValue(idx.size)
                val acts = m.forward(idx).map { it.copyOf() }
                // Dropout on every hidden layer, inverted so nothing changes when it's off.
                val masks = if (recipe.dropout > 0) acts.map { a -> BooleanArray(a.size) { random.nextDouble() >= recipe.dropout } } else null
                if (masks != null) {
                    // The network as this step sees it: dropped units out, the rest scaled up, layer by layer.
                    for (l in acts.indices) {
                        val a = acts[l]
                        for (j in a.indices) a[j] = if (masks[l][j]) a[j] * keepScale else 0.0
                        if (l + 1 < acts.size) {
                            val w = m.dense[l]
                            val nOut = layers[l + 1]
                            val next = DoubleArray(nOut) { m.denseBias[l][it].toDouble() }
                            for (q in a.indices) { val x = a[q]; if (x != 0.0) for (j in 0 until nOut) next[j] += w[q * nOut + j] * x }
                            for (j in 0 until nOut) acts[l + 1][j] = NeuralModel.relu(next[j])
                        }
                    }
                }
                val last = acts.last()
                val p = LocalModel.softmax(m.logits(idx, last))
                val scale = classWeight[item.label] * item.weight
                val d = DoubleArray(k) { c -> (p[c] - if (c == item.label) 1.0 else 0.0) * scale }
                // Back through the output layer, before it changes.
                var delta = DoubleArray(last.size) { a -> if (last[a] <= 0) 0.0 else (0 until k).sumOf { c -> d[c] * m.out[a * k + c] } }
                for (c in 0 until k) {
                    gOutBias[c] += d[c] * d[c]
                    m.outBias[c] -= (lr * d[c] / sqrt(gOutBias[c])).toFloat()
                    for (a in last.indices) {
                        if (last[a] == 0.0) continue
                        val q = a * k + c
                        val g = d[c] * last[a] + l2 * m.out[q]
                        gOut[q] += g * g
                        m.out[q] -= (lr * g / sqrt(gOut[q])).toFloat()
                    }
                    if (m.wide) for (i in idx) {
                        val q = i * k + c
                        val g = d[c] * v + l2 * m.wideWeights[q]
                        gWide[q] += (g * g).toFloat()
                        m.wideWeights[q] -= (lr * g / sqrt(gWide[q].toDouble())).toFloat()
                    }
                }
                // Back through the hidden layers, last to first.
                for (l in acts.lastIndex downTo 1) {
                    masks?.let { mk -> for (j in delta.indices) delta[j] = if (mk[l][j]) delta[j] * keepScale else 0.0 }
                    val prev = acts[l - 1]
                    val w = m.dense[l - 1]
                    val nOut = layers[l]
                    val back = DoubleArray(prev.size) { a -> if (prev[a] <= 0) 0.0 else (0 until nOut).sumOf { j -> delta[j] * w[a * nOut + j] } }
                    for (j in 0 until nOut) {
                        if (delta[j] == 0.0) continue
                        gDenseBias[l - 1][j] += delta[j] * delta[j]
                        m.denseBias[l - 1][j] -= (lr * delta[j] / sqrt(gDenseBias[l - 1][j])).toFloat()
                        for (a in prev.indices) {
                            if (prev[a] == 0.0) continue
                            val q = a * nOut + j
                            val g = delta[j] * prev[a] + l2 * w[q]
                            gDense[l - 1][q] += (g * g).toFloat()
                            w[q] -= (lr * g / sqrt(gDense[l - 1][q].toDouble())).toFloat()
                        }
                    }
                    delta = back
                }
                masks?.let { mk -> for (j in delta.indices) delta[j] = if (mk[0][j]) delta[j] * keepScale else 0.0 }
                // Into the embeddings of this text's buckets only.
                for (j in 0 until e) {
                    if (delta[j] == 0.0) continue
                    gEmbBias[j] += delta[j] * delta[j]
                    m.embeddingBias[j] -= (lr * delta[j] / sqrt(gEmbBias[j])).toFloat()
                    for (i in idx) {
                        val q = i * e + j
                        val g = delta[j] * v + l2 * m.embedding[q]
                        gEmb[q] += (g * g).toFloat()
                        m.embedding[q] -= (lr * g / sqrt(gEmb[q].toDouble())).toFloat()
                    }
                }
            }
        }
        return m
    }
}
