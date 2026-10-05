package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonalizerTest {
    private val base = OnDeviceClassifier()
    private val promo = InboundMessage("38411", "Brew Lab: Taco Tuesday is back! $2 tacos and $5 margaritas tonight. Reply STOP to opt out")
    private val allowed = setOf(Category.PERSONAL, Category.TRANSACTIONAL)

    @Test
    fun `a correction moves the corrected message and messages like it`() {
        assertEquals(Category.MARKETING, base.classify(promo).category)
        val correction = base.correction(promo, allowed)!!
        val taught = base.learn(listOf(correction))

        assertTrue(taught.classify(promo).category in allowed)
        // The same bar's next text moves the same way.
        val sibling = InboundMessage("38411", "Brew Lab: Trivia night is back! $5 margaritas and $2 tacos all night. Reply STOP to opt out")
        assertTrue(taught.classify(sibling).let { it.category in allowed || it.confidence < base.classify(sibling).confidence })
    }

    @Test
    fun `a correction barely moves unrelated messages`() {
        val taught = base.learn(listOfNotNull(base.correction(promo, allowed)))
        val corpus = Corpus.load(File("training/corpus")).map { InboundMessage(it.sender, it.body) }
        val changed = corpus.count { base.classify(it).category != taught.classify(it).category }
        assertTrue("$changed of ${corpus.size} changed", changed <= corpus.size / 50)

        val phish = InboundMessage("+17025550161", "FasTrak: You have an unpaid toll of 6.25 USD. Pay now to avoid penalties: fastrak.com-billing.vip")
        assertEquals(Category.PHISHING, taught.classify(phish).category)
    }

    @Test
    fun `the label is the likeliest acceptable category`() {
        val phish = InboundMessage("+17025550161", "Your E-ZPass has an unpaid balance. Pay now: ezpass.com-pay.top")
        val filterable = setOf(Category.PHISHING, Category.SCAM, Category.SPAM, Category.POLITICAL)
        val c = base.correction(phish, filterable)!!
        assertEquals(Category.PHISHING.key, LocalModel.bundled.classes[c.label])
        assertEquals(null, base.correction(phish, emptySet()))
    }

    @Test
    fun `no corrections means no change`() {
        assertEquals(0, Personalizer.train(LocalModel.bundled, emptyList()).size)
        assertEquals(base.classify(promo), base.learn(emptyList()).classify(promo))
    }

    @Test
    fun `the fast fit gives exactly what the plain one does`() {
        val r = kotlin.random.Random(7)
        val words = "your package confirm account vote donate today free prize call reply stop click order code bank payment dinner tomorrow late".split(" ")
        val corrections = (0 until 300).mapNotNull {
            val body = (0 until 14).joinToString(" ") { words[r.nextInt(words.size)] } + " ${r.nextInt(1000)}"
            base.correction(InboundMessage("+1415555${1000 + r.nextInt(8999)}", body), setOf(Category.entries[r.nextInt(7)]))
        }
        val fast = Personalizer.train(LocalModel.bundled, corrections)
        val plain = plainTrain(LocalModel.bundled, corrections)
        assertEquals(plain.keys, fast.weights.keys)
        plain.forEach { (bucket, row) -> assertTrue("bucket $bucket", row.contentEquals(fast.weights.getValue(bucket))) }
    }

    /** Personalizer.train as it was first written, with maps: the reference the fast one must match. */
    private fun plainTrain(base: LocalModel, corrections: List<Correction>, epochs: Int = 40, learningRate: Double = 0.5, l2: Double = 1e-3): Map<Int, FloatArray> {
        val k = base.classes.size
        val baseScores = corrections.map { base.scores(it.buckets) }
        val weights = HashMap<Int, DoubleArray>()
        val squares = HashMap<Int, DoubleArray>()
        corrections.forEach { c -> c.buckets.forEach { weights.getOrPut(it) { DoubleArray(k) }; squares.getOrPut(it) { DoubleArray(k) { 1e-8 } } } }
        repeat(epochs) {
            corrections.forEachIndexed { n, correction ->
                val value = LocalModel.featureValue(correction.buckets.size)
                val scores = baseScores[n].copyOf()
                for (i in correction.buckets) { val row = weights.getValue(i); for (c in 0 until k) scores[c] += row[c] * value }
                val p = LocalModel.softmax(scores, base.temperature.toDouble())
                for (c in 0 until k) {
                    val g = p[c] - if (c == correction.label) 1.0 else 0.0
                    for (i in correction.buckets) {
                        val row = weights.getValue(i)
                        val sq = squares.getValue(i)
                        val gi = g * value + l2 * row[c]
                        sq[c] += gi * gi
                        row[c] -= learningRate * gi / kotlin.math.sqrt(sq[c])
                    }
                }
            }
        }
        return weights.mapValues { (_, row) -> FloatArray(k) { row[it].toFloat() } }
    }

    @Test
    fun `a lighter label gives way to a full one on the same text`() {
        val asPersonal = base.correction(promo, setOf(Category.PERSONAL))!!
        val asMarketing = base.correction(promo, setOf(Category.MARKETING))!!
        val taught = base.learn(listOf(asMarketing.copy(weight = 0.3), asPersonal))
        assertEquals(Category.PERSONAL, taught.classify(promo).category)
    }
}
