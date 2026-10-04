package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class LocalModelTest {
    private val corpusDir = File("training/corpus")
    private val evalFile = File("training/eval.tsv")
    private val classifier = OnDeviceClassifier()

    @Test
    fun `the bundled model loads and covers every category`() {
        val model = LocalModel.bundled
        assertEquals(Category.entries.map { it.key }, model.classes)
        assertEquals(Featurizer.VERSION, model.featurizerVersion)
    }

    @Test
    fun `the shipped model is exactly what the corpus trains`() {
        val rebuilt = ByteArrayOutputStream().also { LocalModelBuild.run(corpusDir, evalFile).model.write(it) }.toByteArray()
        val shipped = LocalModel::class.java.getResourceAsStream("winnow-local.bin")!!.use { it.readBytes() }
        assertArrayEquals("Stale model: run ./gradlew :classifier:trainLocalModel", shipped, rebuilt)
    }

    @Test
    fun `int8 weights agree with the full-precision model`() {
        val corpus = Corpus.load(corpusDir)
        val full = LocalModelBuild.trainer().train(corpus.map { it.example(Corpus.classes) })
        val agree = corpus.count { t ->
            val f = Featurizer.features(Featurizer.Input(t.sender, t.body))
            full.predict(f).argmax() == LocalModel.bundled.predict(f).argmax()
        }
        assertTrue("$agree of ${corpus.size}", agree >= corpus.size * 0.99)
    }

    @Test
    fun `generalizes to text it never saw`() {
        val (train, heldOut) = Corpus.split(Corpus.load(corpusDir), 0.2)
        val probe = LocalModelBuild.trainer().train(train.map { it.example(Corpus.classes) })
        val heldOutMetrics = Metrics.of(probe, heldOut)
        assertTrue(heldOutMetrics.table(), heldOutMetrics.accuracy >= 0.85)

        val evalMetrics = Metrics.of(LocalModel.bundled, LocalModelBuild.loadEval(evalFile))
        assertTrue(evalMetrics.table(), evalMetrics.accuracy >= 0.9)
    }

    @Test
    fun `never confidently filters a wanted message from the evaluation set`() {
        LocalModelBuild.loadEval(evalFile).filter { it.category.defaultAction == Action.ALLOW }.forEach { t ->
            val p = classifier.classify(InboundMessage(t.sender, t.body))
            val confidentlyFiltered = p.category.defaultAction == Action.FILTER && p.confidence >= 0.85
            assertTrue("${t.body} → $p", !confidentlyFiltered)
        }
    }

    @Test
    fun `explains a toll phish by its link`() {
        val p = classifier.classify(InboundMessage("+18035550123", "SunPass: You have an unpaid toll of $4.15. Pay now to avoid a fee: sunpass.com-tollpay.vip"))
        assertEquals(Category.PHISHING, p.category)
        assertTrue(p.reasons.toString(), p.reasons.isNotEmpty())
        assertTrue(p.reasons.toString(), p.reasons.any { "link" in it || "web address" in it })
        assertTrue(p.reasons.toString(), p.reasons.none { it == "“pay”" && "“pay now”" in p.reasons })
    }

    @Test
    fun `featurizer replaces numbers, links and money with placeholders`() {
        val f = Featurizer.features(Featurizer.Input("+14155550100", "Pay $4.35 at ezpass.com-pay.top/x or call 555-0193 code 482913"))
        assertTrue(f.contains("__money__"))
        assertTrue(f.contains("__phone__"))
        assertTrue(f.contains("__risky_tld__"))
        assertTrue(f.contains("__deceptive_host__"))
        assertTrue(f.contains("h:ezpass"))
        assertTrue(f.contains("w:zznumlong"))
        assertTrue(f.none { it.contains("482913") || it.contains("4.35") })

        val shortened = Featurizer.features(Featurizer.Input("38822", "Track it: bit.ly/abc"))
        assertTrue(shortened.containsAll(listOf("__shortener__", "__url_path__", "__sender_short_code__")))
    }

    @Test
    fun `hashing is stable`() {
        // FNV-1a test vectors; the trained weights depend on these never changing.
        assertEquals(0x811c9dc5.toInt(), LocalModel.fnv1a(""))
        assertEquals(0xe40c292c.toInt(), LocalModel.fnv1a("a"))
        assertEquals(0xbf9cf968.toInt(), LocalModel.fnv1a("foobar"))
    }

    private fun DoubleArray.argmax() = indices.maxBy { this[it] }
}
