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
    private val classifier = OnDeviceClassifier()

    private companion object {
        val corpusDir = File("training/corpus")
        val evalFile = File("training/eval.tsv")
        /** Cross-validation takes a few seconds, so every test shares one build. */
        val build by lazy { LocalModelBuild.run(corpusDir, evalFile) }
    }

    @Test
    fun `the bundled model loads and covers every category`() {
        val model = LocalModel.bundled
        assertEquals(Category.entries.map { it.key }, model.classes)
        assertEquals(Featurizer.VERSION, model.featurizerVersion)
    }

    @Test
    fun `the shipped model and metrics are exactly what the corpus trains`() {
        val rebuilt = ByteArrayOutputStream().also { build.model.write(it) }.toByteArray()
        val shipped = LocalModel::class.java.getResourceAsStream("winnow-local.bin")!!.use { it.readBytes() }
        assertArrayEquals("Stale model: run ./gradlew :classifier:trainLocalModel", shipped, rebuilt)
        val metrics = LocalModel::class.java.getResourceAsStream("winnow-local-metrics.json")!!.use { it.readBytes().decodeToString() }
        assertEquals("Stale metrics: run ./gradlew :classifier:trainLocalModel", metrics, build.metricsJson)
        assertEquals(build.metrics, ClassifierMetrics.bundled)
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
        val m = build.metrics
        assertTrue("cross-validated accuracy ${m.accuracy}", m.accuracy >= 0.88)
        assertTrue("spam ROC AUC ${m.unwanted.auc}", m.unwanted.auc >= 0.97)
        val political = m.perCategory.single { it.key == Category.POLITICAL.key }
        assertTrue("political F1 ${political.f1}", political.f1 >= 0.9)
        val e = m.evaluation!!
        assertTrue("eval accuracy ${e.accuracy}", e.accuracy >= 0.9)
    }

    @Test
    fun `Winnow's own rule rarely filters a wanted text`() {
        val rule = build.metrics.unwanted.operatingPoint
        assertTrue("false positive rate ${rule.falsePositiveRate}", rule.falsePositiveRate <= 0.005)
        assertTrue("unwanted kept quiet ${build.metrics.unwanted.unwantedQuieted}", build.metrics.unwanted.unwantedQuieted >= 0.93)
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
    fun `a hook is anything a fraudster could use, but not the company's real website`() {
        fun hook(sender: String, body: String) = Featurizer.hasHook(Featurizer.features(Featurizer.Input(sender, body)))
        assertTrue(hook("+13105550142", "Your package is on hold: usps-parcel.top/c"))
        assertTrue(hook("+13105550142", "Your account is locked. Call 555-0193 now"))
        assertTrue(hook("+13105550142", "I sent $200 by mistake on Cash App, please send it back"))
        assertTrue(hook("+13105550142", "Reply with the 6-digit code we just sent"))
        assertTrue(!hook("72975", "Netflix: Your password was changed. If you didn't do this, visit netflix.com/security"))
        assertTrue(!hook("+14155550199", "Hi, is this David? This is Amy from yoga"))
        // Missing spaces after a period aren't links.
        assertTrue(!hook("+14155550199", "Hi.Is this David?"))
        assertTrue(!hook("+14155550199", "Sorry wrong number.Who is this?"))
        assertTrue(hook("+14155550199", "Pay the toll at USPS.com-redelivery.top now"))
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
