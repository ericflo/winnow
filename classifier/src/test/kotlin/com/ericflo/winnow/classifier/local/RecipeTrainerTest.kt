package com.ericflo.winnow.classifier.local

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RecipeTrainerTest {
    private val base = LocalModel.bundled
    private val classes = base.classes
    private val corpus = ShippedCorpus.texts
    private val split = Corpus.split(corpus, 0.2, seed = 3)
    private val train = split.first
    private val test = split.second

    private fun items(texts: List<LabeledText>) = texts.map { t ->
        TrainingItem(Featurizer.features(Featurizer.Input(t.sender, t.body)), null, classes.indexOf(t.category.key), 1.0)
    }

    private fun accuracy(p: Predictor, texts: List<LabeledText>): Double = texts.count { t ->
        val probs = p.probabilities(Featurizer.features(Featurizer.Input(t.sender, t.body)))
        probs.indices.maxBy { probs[it] } == classes.indexOf(t.category.key)
    }.toDouble() / texts.size

    @Test
    fun theShippedTextsAreBundled() {
        assertEquals(1493, corpus.size)
        assertEquals(classes.toSet(), corpus.map { it.category.key }.toSet())
    }

    @Test
    fun aRetrainedLinearModelAndANetworkBothLearnTheTextsAndHoldUpOnUnseenOnes() {
        val linear = RecipeTrainer.train(Recipe.PRESETS[1].second.copy(epochs = 30), base, items(train))
        val linearAccuracy = accuracy(linear, test)
        assertTrue(linearAccuracy > 0.85, "linear $linearAccuracy")
        val neural = RecipeTrainer.train(Recipe.PRESETS.first { it.first == "Neural, deeper" }.second.copy(epochs = 12), base, items(train))
        val neuralAccuracy = accuracy(neural, test)
        assertTrue(neuralAccuracy > 0.85, "neural $neuralAccuracy")
        println("held out: linear $linearAccuracy, neural $neuralAccuracy")
    }

    @Test
    fun trainingIsDeterministicAndANetworkSurvivesBeingKept() {
        val recipe = Recipe(kind = RecipeKind.NEURAL, buckets = 1 shl 12, layers = listOf(16, 8), epochs = 3, learningRate = 0.05, dropout = 0.2)
        val a = RecipeTrainer.train(recipe, base, items(train.take(300))) as NeuralModel
        val b = RecipeTrainer.train(recipe, base, items(train.take(300))) as NeuralModel
        val probe = Featurizer.features(Featurizer.Input("+15555550101", "Your toll balance is unpaid, pay now at tolls-x.top"))
        assertContentEquals(a.probabilities(probe), b.probabilities(probe))
        a.temperature = 1.7f
        val bytes = ByteArrayOutputStream().also { a.write(it) }.toByteArray()
        val back = NeuralModel.read(ByteArrayInputStream(bytes))
        assertContentEquals(a.probabilities(probe), back.probabilities(probe))
        assertEquals(listOf(16, 8), back.layers)
        // It can say what drove an answer, by taking each feature out in turn.
        val top = back.probabilities(probe).let { p -> p.indices.maxBy { p[it] } }
        assertNotNull(back.reasons(probe, top))
    }

    @Test
    fun aRecipeSaysWhatsWrongWithItBeforeAnythingIsTrained() {
        assertNull(Recipe().problem())
        Recipe.PRESETS.forEach { (name, r) -> assertNull(r.problem(), name) }
        assertNotNull(Recipe(buckets = 3000).problem())
        assertNotNull(Recipe(kind = RecipeKind.NEURAL, buckets = 1 shl 18, layers = listOf(64)).problem())
        assertNotNull(Recipe(kind = RecipeKind.NEURAL, layers = listOf(64, 64, 64, 64, 64)).problem())
        assertFailsWith<IllegalArgumentException> { RecipeTrainer.train(Recipe(epochs = 0), base, emptyList()) }
    }

    @Test
    fun crossValidationKeepsAConversationsTextsTogetherAndScoresEach() {
        // Labels as a user would have them: a made-up club they file as political, in many conversations.
        val labels = (0 until 30).map { i ->
            TrainingItem(Featurizer.features(Featurizer.Input("+1555555${1000 + i}", "Zorblax league practice moved to field ${i % 7}")), null, classes.indexOf("political"), 1.0, group = i.toLong() / 2)
        }
        val scored = RecipeTrainer.crossValidate(Recipe(), base, labels, emptyList())
        assertEquals(30, scored.size)
        val right = scored.count { (i, logits) -> logits.indices.maxBy { logits[it] } == labels[i].label }
        assertTrue(right > 20, "right on $right")
    }
}
