package com.ericflo.winnow.classifier.local

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.sqrt

/**
 * What a text means as a whole: the average of its words' meanings, from GloVe's word vectors
 * (Stanford's, learned from Wikipedia and newswire text, public domain under the ODC PDDL) for the
 * 20,000 commonest English words, so two texts that say the same thing in different words point
 * the same way. Made from a text's `w:` words alone. The vectors ship in Winnow (one byte a number,
 * one scale a word); nothing is looked up anywhere.
 */
object WordMeanings {
    private const val RESOURCE = "/com/ericflo/winnow/classifier/local/word-meanings.bin.gz"
    private const val MAGIC = 0x574d4e47 // "WMNG"

    private class Table(val dim: Int, val vectors: Map<String, Pair<Float, ByteArray>>)

    private val table: Table by lazy {
        val stream = WordMeanings::class.java.getResourceAsStream(RESOURCE) ?: return@lazy Table(0, emptyMap())
        DataInputStream(GZIPInputStream(stream).buffered()).use { d ->
            require(d.readInt() == MAGIC) { "Not Winnow's word meanings" }
            val n = d.readInt()
            val dim = d.readInt()
            val vectors = HashMap<String, Pair<Float, ByteArray>>(n * 2)
            repeat(n) {
                val word = d.readUTF()
                val scale = d.readFloat()
                vectors[word] = scale to ByteArray(dim).also(d::readFully)
            }
            Table(dim, vectors)
        }
    }

    /** How many numbers a meaning has; 0 if the vectors weren't shipped. */
    val dim: Int get() = table.dim

    /** How many words have a meaning. */
    val size: Int get() = table.vectors.size

    /** The meaning of the text [features] are of: its known words' vectors averaged, length 1; null when it has none. */
    fun of(features: List<String>): DoubleArray? {
        val t = table
        val sum = DoubleArray(t.dim)
        var any = false
        for (f in features) {
            if (!f.startsWith("w:")) continue
            val (scale, bytes) = t.vectors[f.substring(2)] ?: continue
            for (d in 0 until t.dim) sum[d] += bytes[d] * scale.toDouble()
            any = true
        }
        if (!any) return null
        val length = sqrt(sum.sumOf { it * it })
        return if (length <= 0.0) null else DoubleArray(t.dim) { sum[it] / length }
    }
}

/**
 * A linear model over a text's words that reads what the text means as a whole too
 * ([WordMeanings]): each category's score is its words' weights, as [model]'s are, plus [meaning]
 * (one weight per category and meaning number, `[class * dim + d]`) times the text's meaning. At
 * its own [temperature].
 */
class MeaningPredictor(val model: LocalModel, val meaning: FloatArray, val dim: Int, var temperature: Float = 1f) : Predictor {
    override val classes get() = model.classes

    /** Each category's score, before the softmax, for [indices] (its buckets in [model]) and the text's meaning [m]. */
    fun scores(indices: IntArray, m: DoubleArray?): DoubleArray {
        val s = model.scoresOf(indices)
        if (m != null && m.size == dim) for (c in s.indices) {
            var x = 0.0
            for (d in 0 until dim) x += meaning[c * dim + d] * m[d]
            s[c] += x
        }
        return s
    }

    override fun probabilities(features: List<String>) = LocalModel.softmax(scores(model.indices(features), WordMeanings.of(features)), temperature.toDouble())
    override fun reasons(features: List<String>, classIndex: Int, limit: Int) = model.explain(features, classIndex, limit)

    /** Its meaning weights first, then the word model (whose reading may read on past its end). */
    fun write(output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeFloat(temperature)
        out.writeInt(dim)
        out.writeInt(meaning.size)
        meaning.forEach(out::writeFloat)
        out.flush()
        model.withTemperature(1f).write(output)
    }

    companion object {
        private const val MAGIC = 0x574d4e50 // "WMNP"

        fun read(input: InputStream): MeaningPredictor {
            val d = DataInputStream(input)
            require(d.readInt() == MAGIC) { "Not a model that reads meanings" }
            val temperature = d.readFloat()
            val dim = d.readInt()
            val meaning = FloatArray(d.readInt()) { d.readFloat() }
            return MeaningPredictor(LocalModel.read(input), meaning, dim, temperature)
        }
    }
}
