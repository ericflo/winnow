package com.ericflo.winnow.classifier.local

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A trained on-device model that can sort a message: the shipped linear model with what the user
 * taught it ([LinearPredictor]), or one the user trained on the phone ([NeuralModel], or a
 * retrained [LocalModel]).
 */
interface Predictor {
    /** Class keys, in output order. */
    val classes: List<String>

    /** The probability of each class, in [classes] order. */
    fun probabilities(features: List<String>): DoubleArray

    /** What pushed hardest toward [classIndex], in words, strongest first. */
    fun reasons(features: List<String>, classIndex: Int, limit: Int = 3): List<String>
}

/** The shipped linear model (or a retrained one), with [adjustments] the user taught it on top. */
class LinearPredictor(val model: LocalModel, val adjustments: Adjustments = Adjustments.NONE) : Predictor {
    override val classes get() = model.classes
    override fun probabilities(features: List<String>) = model.predict(features, adjustments)
    override fun reasons(features: List<String>, classIndex: Int, limit: Int) = model.explain(features, classIndex, limit, adjustments)
}

/**
 * A small neural network over the same hashed features as [LocalModel]: each feature bucket has
 * an embedding (the first layer, of width [layers]`[0]`), summed over the text's features and
 * scaled as the linear model scales them, then dense ReLU layers of the widths that follow, then
 * a softmax over the classes. With [wide], the linear model's per-bucket weights are added to the
 * output too ("wide & deep"): the network learns what single words can't say, the wide part what
 * they can. Deeper: more layers; wider: more buckets and wider layers.
 */
class NeuralModel(
    override val classes: List<String>,
    val buckets: Int,
    /** Widths: the embedding first, then each hidden layer. */
    val layers: List<Int>,
    val wide: Boolean,
    /** Row-major [bucket * layers[0] + unit]. */
    internal val embedding: FloatArray,
    internal val embeddingBias: FloatArray,
    /** For each layer after the first: weights [in * out] (row-major by input) and biases. */
    internal val dense: List<FloatArray>,
    internal val denseBias: List<FloatArray>,
    /** Last hidden layer to classes: [last * k], and biases. */
    internal val out: FloatArray,
    internal val outBias: FloatArray,
    /** [bucket * k] when [wide]; empty otherwise. */
    internal val wideWeights: FloatArray,
    var temperature: Float = 1f,
) : Predictor {
    init {
        require(buckets > 0 && buckets and (buckets - 1) == 0) { "buckets must be a power of two" }
        require(layers.isNotEmpty() && layers.all { it > 0 })
    }

    private val k = classes.size

    /** How many numbers it holds. */
    val parameters: Long get() = embedding.size.toLong() + embeddingBias.size + dense.sumOf { it.size } + denseBias.sumOf { it.size } + out.size + outBias.size + wideWeights.size

    fun bucket(feature: String): Int = LocalModel.fnv1a(feature) and (buckets - 1)

    fun indices(features: List<String>): IntArray = features.mapTo(LinkedHashSet()) { bucket(it) }.toIntArray()

    /** The activations of every layer (last is the output's input), for [indices]. */
    internal fun forward(indices: IntArray, keep: BooleanArray? = null, dropoutScale: Double = 1.0): List<DoubleArray> {
        val v = LocalModel.featureValue(indices.size)
        val e = layers[0]
        val first = DoubleArray(e) { embeddingBias[it].toDouble() }
        for (i in indices) {
            val at = i * e
            for (j in 0 until e) first[j] += embedding[at + j] * v
        }
        for (j in 0 until e) first[j] = relu(first[j])
        val acts = mutableListOf(first)
        var prev = first
        for (l in 1 until layers.size) {
            val w = dense[l - 1]
            val b = denseBias[l - 1]
            val n = layers[l]
            val next = DoubleArray(n) { b[it].toDouble() }
            for (a in prev.indices) {
                val x = prev[a]
                if (x == 0.0) continue
                val at = a * n
                for (j in 0 until n) next[j] += w[at + j] * x
            }
            for (j in 0 until n) next[j] = relu(next[j])
            acts += next
            prev = next
        }
        return acts
    }

    /** Class scores before the softmax. */
    fun scores(indices: IntArray): DoubleArray = logits(indices, forward(indices).last())

    internal fun logits(indices: IntArray, last: DoubleArray): DoubleArray {
        val s = DoubleArray(k) { outBias[it].toDouble() }
        for (a in last.indices) {
            val x = last[a]
            if (x == 0.0) continue
            for (c in 0 until k) s[c] += out[a * k + c] * x
        }
        if (wide) {
            val v = LocalModel.featureValue(indices.size)
            for (i in indices) for (c in 0 until k) s[c] += wideWeights[i * k + c] * v
        }
        return s
    }

    override fun probabilities(features: List<String>): DoubleArray = LocalModel.softmax(scores(indices(features)), temperature.toDouble())

    /**
     * What pushed hardest toward [classIndex]: each describable feature taken out in turn, and how
     * much its absence lowers that class's score. A network has no per-feature weight to read off,
     * so this asks it directly.
     */
    override fun reasons(features: List<String>, classIndex: Int, limit: Int): List<String> {
        val unique = features.distinctBy { bucket(it) }
        val all = indices(unique)
        val base = scores(all)[classIndex]
        return unique.mapNotNull { f ->
            val description = Featurizer.describe(f) ?: return@mapNotNull null
            val without = all.filter { it != bucket(f) }.toIntArray()
            val drop = base - scores(without)[classIndex]
            (description to drop).takeIf { drop > 0 }
        }.sortedByDescending { it.second }.map { it.first }.distinct().take(limit)
    }

    fun write(output: OutputStream) {
        val o = DataOutputStream(output)
        o.writeInt(MAGIC)
        o.writeInt(FORMAT)
        o.writeInt(Featurizer.VERSION)
        o.writeInt(buckets)
        o.writeInt(k)
        classes.forEach(o::writeUTF)
        o.writeInt(layers.size)
        layers.forEach(o::writeInt)
        o.writeBoolean(wide)
        o.writeFloat(temperature)
        fun floats(a: FloatArray) { o.writeInt(a.size); a.forEach(o::writeFloat) }
        floats(embedding); floats(embeddingBias)
        dense.forEach(::floats); denseBias.forEach(::floats)
        floats(out); floats(outBias); floats(wideWeights)
        o.flush()
    }

    companion object {
        private const val MAGIC = 0x574e4e4d // "WNNM"
        private const val FORMAT = 1

        internal fun relu(x: Double) = if (x > 0) x else 0.0

        fun read(input: InputStream): NeuralModel {
            val d = DataInputStream(input.buffered())
            require(d.readInt() == MAGIC) { "Not a Winnow network" }
            require(d.readInt() == FORMAT) { "Unsupported network format" }
            require(d.readInt() == Featurizer.VERSION) { "Trained for another featurizer" }
            val buckets = d.readInt()
            val k = d.readInt()
            val classes = List(k) { d.readUTF() }
            val layers = List(d.readInt()) { d.readInt() }
            val wide = d.readBoolean()
            val temperature = d.readFloat()
            fun floats() = FloatArray(d.readInt()) { d.readFloat() }
            val embedding = floats()
            val embeddingBias = floats()
            val dense = List(layers.size - 1) { floats() }
            val denseBias = List(layers.size - 1) { floats() }
            return NeuralModel(classes, buckets, layers, wide, embedding, embeddingBias, dense, denseBias, floats(), floats(), floats(), temperature)
        }

        /** An untrained network of this shape: embeddings at zero, the rest small and random (He), from [seed]. */
        fun fresh(classes: List<String>, buckets: Int, layers: List<Int>, wide: Boolean, seed: Int): NeuralModel {
            val k = classes.size
            val r = Random(seed)
            fun he(n: Int, fanIn: Int) = FloatArray(n) { ((r.nextDouble() * 2 - 1) * sqrt(6.0 / fanIn)).toFloat() }
            // Small random embeddings: zero ones would give every hidden unit the same gradient forever.
            val embedding = FloatArray(buckets * layers[0]) { ((r.nextDouble() * 2 - 1) * 0.05).toFloat() }
            val dense = (1 until layers.size).map { l -> he(layers[l - 1] * layers[l], layers[l - 1]) }
            return NeuralModel(
                classes, buckets, layers, wide,
                embedding = embedding,
                embeddingBias = FloatArray(layers[0]),
                dense = dense,
                denseBias = (1 until layers.size).map { FloatArray(layers[it]) },
                out = he(layers.last() * k, layers.last()),
                outBias = FloatArray(k),
                wideWeights = if (wide) FloatArray(buckets * k) else FloatArray(0),
            )
        }
    }
}
