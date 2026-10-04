package com.ericflo.winnow.classifier.local

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A softmax regression over hashed features: one weight per (feature bucket, class) plus a
 * bias per class. Small enough to ship in the app (weights are stored as int8) and fast
 * enough to run on every incoming text.
 */
class LocalModel(
    /** Class keys, in output order. */
    val classes: List<String>,
    val buckets: Int,
    /** Row-major: [bucket * classes.size + class]. */
    internal val weights: FloatArray,
    internal val bias: FloatArray,
    /** Divides the scores before the softmax so probabilities are calibrated. */
    val temperature: Float = 1f,
    val featurizerVersion: Int = Featurizer.VERSION,
) {
    init {
        require(buckets > 0 && buckets and (buckets - 1) == 0) { "buckets must be a power of two" }
        require(weights.size == buckets * classes.size && bias.size == classes.size)
    }

    private val k = classes.size

    /** Each feature's bucket. Duplicates collapse, so a word repeated ten times counts once. */
    fun indices(features: List<String>): IntArray = features.mapTo(LinkedHashSet()) { bucket(it) }.toIntArray()

    /** Class probabilities, in [classes] order. */
    fun predict(features: List<String>): DoubleArray = softmax(scores(indices(features)), temperature.toDouble())

    internal fun scores(indices: IntArray): DoubleArray {
        val value = featureValue(indices.size)
        val s = DoubleArray(k) { bias[it].toDouble() }
        for (i in indices) {
            val row = i * k
            for (c in 0 until k) s[c] += weights[row + c] * value
        }
        return s
    }

    /**
     * The features that pushed hardest toward [classIndex] over the other classes, strongest
     * first, as human-readable descriptions.
     */
    fun explain(features: List<String>, classIndex: Int, limit: Int = 3): List<String> {
        val seen = HashSet<Int>()
        val candidates = features
            .filter { seen.add(bucket(it)) }
            .mapNotNull { f ->
                val row = bucket(f) * k
                val others = (0 until k).filter { it != classIndex }.maxOf { weights[row + it] }
                val margin = weights[row + classIndex] - others
                Featurizer.describe(f)?.takeIf { margin > 0f }?.let { Triple(f, it, margin) }
            }
            .sortedByDescending { it.third }
        val reasons = mutableListOf<String>()
        val covered = HashSet<String>()
        for ((feature, description, _) in candidates) {
            if (reasons.size == limit) break
            val words = Featurizer.wordsOf(feature)
            // "pay now" already says "pay"; a link mentioning "sunpass" already says "sunpass".
            if (description in reasons || words.any { it in covered }) continue
            reasons += description
            covered += words
        }
        return reasons
    }

    fun bucket(feature: String): Int = fnv1a(feature) and (buckets - 1)

    /** Writes the model with int8 weights (one scale per class), about 7 bytes per bucket. */
    fun write(output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeInt(FORMAT)
        out.writeInt(featurizerVersion)
        out.writeInt(buckets)
        out.writeInt(k)
        classes.forEach(out::writeUTF)
        out.writeFloat(temperature)
        bias.forEach(out::writeFloat)
        val scales = FloatArray(k) { c -> (0 until buckets).maxOf { abs(weights[it * k + c]) }.coerceAtLeast(1e-9f) / 127f }
        scales.forEach(out::writeFloat)
        val bytes = ByteArray(weights.size) { i -> (weights[i] / scales[i % k]).roundToInt().coerceIn(-127, 127).toByte() }
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val MAGIC = 0x574e4c4d // "WNLM"
        private const val FORMAT = 1
        // Absolute, so it still resolves after R8 renames or moves this class.
        private const val RESOURCE = "/com/ericflo/winnow/classifier/local/winnow-local.bin"

        /** The model trained from `classifier/training/corpus`, bundled as a resource. */
        val bundled: LocalModel by lazy {
            (LocalModel::class.java.getResourceAsStream(RESOURCE) ?: error("$RESOURCE is missing from the classifier module"))
                .use(::read)
        }

        fun read(input: InputStream): LocalModel {
            val data = DataInputStream(input.buffered())
            require(data.readInt() == MAGIC) { "Not a Winnow model" }
            require(data.readInt() == FORMAT) { "Unsupported model format" }
            val featurizer = data.readInt()
            require(featurizer == Featurizer.VERSION) { "Model was trained for featurizer v$featurizer, this is v${Featurizer.VERSION}" }
            val buckets = data.readInt()
            val k = data.readInt()
            val classes = List(k) { data.readUTF() }
            val temperature = data.readFloat()
            val bias = FloatArray(k) { data.readFloat() }
            val scales = FloatArray(k) { data.readFloat() }
            val bytes = ByteArray(buckets * k).also(data::readFully)
            val weights = FloatArray(bytes.size) { i -> bytes[i] * scales[i % k] }
            return LocalModel(classes, buckets, weights, bias, temperature, featurizer)
        }

        /** Binary features, scaled so long messages don't get louder just by being long. */
        internal fun featureValue(count: Int): Double = if (count == 0) 0.0 else 1.0 / sqrt(count.toDouble())

        internal fun softmax(scores: DoubleArray, temperature: Double = 1.0): DoubleArray {
            val max = scores.max()
            // StrictMath, so training reproduces bit-for-bit on any JVM.
            val e = DoubleArray(scores.size) { StrictMath.exp((scores[it] - max) / temperature) }
            val sum = e.sum()
            return DoubleArray(e.size) { e[it] / sum }
        }

        /** 32-bit FNV-1a over UTF-8: stable across runs, JVMs and Android. */
        internal fun fnv1a(s: String): Int {
            var h = 0x811c9dc5.toInt()
            for (b in s.encodeToByteArray()) {
                h = h xor (b.toInt() and 0xff)
                h *= 0x01000193
            }
            return h
        }
    }
}
