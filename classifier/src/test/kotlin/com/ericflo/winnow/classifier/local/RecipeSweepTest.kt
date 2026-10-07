package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.DataHandling
import com.ericflo.winnow.classifier.DecisionProvider
import com.ericflo.winnow.classifier.DecisionRequest
import com.ericflo.winnow.classifier.DecisionResponse
import com.ericflo.winnow.classifier.Distribution
import com.ericflo.winnow.classifier.ProviderDescriptor
import com.ericflo.winnow.classifier.ProviderException
import com.ericflo.winnow.classifier.Usage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeSweepTest {
    private val base = LocalModel.bundled
    private val classes = base.classes
    private val texts = ShippedCorpus.texts.shuffled(Random(5))

    /** "Your labels": 120 texts in conversations of three; the rest of a slice as shipped examples. */
    private val scored = texts.take(120).mapIndexed { i, t ->
        TrainingItem(Featurizer.features(Featurizer.Input(t.sender, t.body)), null, classes.indexOf(t.category.key), 3.0, group = (i / 3).toLong(), key = "sms:$i", sender = t.sender, at = i.toLong(), source = TrainingItem.Source.USER)
    }
    private val others = texts.drop(120).take(240).map { t ->
        TrainingItem(Featurizer.features(Featurizer.Input(t.sender, t.body)), null, classes.indexOf(t.category.key), 1.0, source = TrainingItem.Source.CORPUS)
    }

    private fun scorer() = SweepScorer(base, scored, others, unwanted = emptySet(), filterAt = 0.9)

    @Test
    fun everyStartTrainsAndSettingsGoBothWays() {
        SweepSpace.STARTS.forEach { s ->
            val r = SweepSpace.recipeOf(s)
            assertNull(r.problem(), r.describe())
            assertEquals(SweepSpace.keyOf(s), SweepSpace.keyOf(SweepSpace.settingsOf(r)!!))
        }
        // A recipe from outside the sweep lands on its nearest settings.
        val deeper = Recipe.PRESETS.first { it.first == "Neural, deeper" }.second
        val s = SweepSpace.settingsOf(deeper)!!
        assertEquals("64-32", s[Knob.LAYERS])
        assertEquals("20", s[Knob.EPOCHS])
        assertEquals("1e-6", s[Knob.L2])
        assertEquals("0.1", s[Knob.DROPOUT])
        assertNull(SweepSpace.settingsOf(Recipe()))
    }

    @Test
    fun aRoundNeverRepeatsATryOrOneThatCantTrain() {
        val odds = Knob.entries.associateWith { k -> k.values.associateWith { 1.0 } }
        val tried = SweepSpace.STARTS.map(SweepSpace::keyOf).toSet()
        val round = SweepSampler.round(odds, SweepSpace.STARTS[0], tried, 12, Random(1))
        assertEquals(12, round.size)
        assertEquals(12, round.map(SweepSpace::keyOf).toSet().size)
        round.forEach { s ->
            assertFalse(SweepSpace.keyOf(s) in tried)
            assertNull(SweepSpace.recipeOf(s).problem())
        }
    }

    @Test
    fun wordPiecesComeFromLongerWordsOnly() {
        val f = WordPieces.expand(listOf("w:redelivery", "w:hi", "w:zzurl", "__url__"))
        assertTrue("g:^red" in f && "g:ery$" in f, f.toString())
        assertFalse(f.any { it.startsWith("g:") && ("hi" in it || "zz" in it) })
        // A model that learned from them reads every text with them.
        val recipe = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 3, pieces = true)
        val model = RecipeTrainer.train(recipe, base, scored)
        assertTrue(model is PiecesPredictor)
        val item = scored.first()
        val p = model.probabilities(item.features!!)
        val q = LocalModel.softmax(RecipeTrainer.logits(model, item)!!)
        p.indices.forEach { assertEquals(p[it], q[it], 1e-9) }
    }

    @Test
    fun aBlendScoredFromItsMembersTriesIsTheBlendTrained() {
        val linear = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 5, learningRate = 0.2)
        val neural = Recipe(kind = RecipeKind.NEURAL, buckets = 1 shl 12, layers = listOf(16), epochs = 3, learningRate = 0.05, inputDropout = 0.2)
        val s = scorer()
        val lib = listOf(linear to s.crossValidate(linear), neural to s.crossValidate(neural))
        val blend = Recipe(kind = RecipeKind.BLEND, members = listOf(linear, neural), memberWeights = listOf(0.5, 0.5))
        assertNull(blend.problem())
        val trained = s.crossValidate(blend).associate { it.index to it.logits }
        val fromTries = lib.map { (_, rows) -> rows.associate { it.index to it.logits } }
        trained.forEach { (i, logits) ->
            val expected = BlendPredictor.blendLogits(fromTries.map { it.getValue(i) }, listOf(0.5, 0.5))
            logits.indices.forEach { c -> assertEquals(expected[c], logits[c], 1e-6) }
        }
        // And a blend survives being kept as a recipe.
        val json = Json { encodeDefaults = true }
        assertEquals(blend, json.decodeFromString(Recipe.serializer(), json.encodeToString(Recipe.serializer(), blend)))
        assertTrue(blend.describe().startsWith("Blend of 2"))
    }

    @Test
    fun aSweepScoresTheBaselineAsItIsAndAsksTheServiceAtMostAsOftenAsAllowed() = runBlocking {
        val provider = FakeProvider()
        val plan = RecipeSweep.Plan(rounds = 3, perRound = 2, steeringCalls = 2)
        val baseline = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 5, learningRate = 0.2, userWeight = 3.0)
        val result = RecipeSweep(scorer(), ServiceSteerer(provider), plan).run(listOf("Your best" to baseline))
        assertEquals(2, provider.calls)
        assertEquals(2, result.steeringCalls)
        assertEquals(0.002, result.costUsd, 1e-12)
        assertEquals(3, result.rounds.size)
        assertEquals(listOf("Fake Jev", "Fake Jev", LocalSteerer.name), result.rounds.map { it.steeredBy })
        val mine = result.trials.first { it.from == "Your best" }
        assertEquals(baseline, mine.recipe)
        assertEquals(120, mine.scoredOn)
        // Tries the service leaned toward, after the starts.
        assertTrue(result.trials.count { it.from == "Fake Jev" } >= 2)
        // Best first.
        assertEquals(result.trials.maxOf { it.accuracy }, result.trials.first().accuracy)
        // Never a text, a sender or a word in what the service is shown.
        val shown = provider.requests.joinToString { it.state.toString() }
        assertFalse(scored.any { it.sender != null && it.sender in shown })
        assertFalse("w:" in shown)
        assertTrue("labels_by_category" in shown)
    }

    @Test
    fun aServiceThatFailsIsNotAskedAgainAndTheCallStillCounts() = runBlocking {
        val provider = FakeProvider(fail = true)
        val result = RecipeSweep(scorer(), ServiceSteerer(provider), RecipeSweep.Plan(rounds = 3, perRound = 1, steeringCalls = 4)).run(emptyList())
        // A failed call may still be paid for: it counts, and no more are made.
        assertEquals(1, provider.calls)
        assertEquals(1, result.steeringCalls)
        assertEquals(1, result.steeringFailed)
        assertEquals(List(3) { LocalSteerer.name }, result.rounds.map { it.steeredBy })
        val note = result.rounds.first().note
        assertNotNull(note)
        assertTrue("Fake Jev" in note)
    }

    @Test
    fun nothingToScoreMeansNothingIsAskedOrPaidFor() = runBlocking {
        val provider = FakeProvider()
        // One conversation: nothing can be held out.
        val one = scored.map { TrainingItem(it.features, null, it.label, 1.0, group = 1, key = it.key, sender = it.sender, source = TrainingItem.Source.USER) }
        val result = RecipeSweep(SweepScorer(base, one, others, emptySet(), 0.9), ServiceSteerer(provider), RecipeSweep.Plan(rounds = 3, perRound = 1)).run(emptyList())
        assertEquals(0, provider.calls)
        assertTrue(result.trials.isEmpty())
    }

    @Test
    fun aSweepScoresARecipeAsTheLabDoes() {
        // The Lab: cross-validated logits, calibrated, then the sender memory of the other conversations.
        val recipe = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 5, learningRate = 0.2, senderMemory = 1.0)
        val s = scorer()
        val cv = RecipeTrainer.crossValidate(recipe, base, scored, others)
        val t = RecipeTrainer.calibrate(cv.map { (i, l) -> l to scored[i].label }).toDouble()
        val folds = RecipeTrainer.foldsOf(scored)!!
        val right = cv.count { (i, l) ->
            val memory = SenderMemory.of(scored.indices.filter { folds[it] != folds[i] }.map { scored[it].sender!! to scored[it].label }, classes, 1.0)
            memory.follow(LocalModel.softmax(l, t), scored[i].sender!!, false).best == scored[i].label
        }
        assertEquals(right.toDouble() / cv.size, s.score(recipe, s.crossValidate(recipe), tune = false)!!.accuracy, 1e-12)
    }

    @Test
    fun aModelTrainedWhileReminderWasACategoryAnswersOverTheOnesThereAreNow() {
        val six = Recipe.SIX_CATEGORIES
        val old = object : Predictor {
            override val classes = six
            override fun probabilities(features: List<String>) = doubleArrayOf(0.1, 0.5, 0.2, 0.1, 0.05, 0.05)
            override fun reasons(features: List<String>, classIndex: Int, limit: Int) = listOf("class $classIndex")
        }
        val now = ClassesPredictor(old, classes)
        assertEquals(listOf("personal", "transactional", "marketing", "political", "spam"), now.classes)
        // Reminder's half goes; the rest share the odds as they stood, transactional still twice personal.
        assertContentEquals(doubleArrayOf(0.2, 0.4, 0.2, 0.1, 0.1).toList(), now.probabilities(emptyList()).map { Math.round(it * 1e9) / 1e9 })
        assertEquals(listOf("class 2"), now.reasons(emptyList(), 1, 3))
        // Leanings tuned then keep each category's own; Reminder's goes.
        val leaned = Recipe(kind = RecipeKind.LINEAR, classBias = listOf(0.1, 9.0, 0.3, 0.4, 0.5, 0.6))
        assertEquals(listOf(0.1, 0.3, 0.4, 0.5, 0.6), leaned.leaningsIn(classes))
        assertEquals(leaned.classBias, leaned.leaningsIn(six))
        assertEquals(listOf(1.0, 2.0), Recipe(kind = RecipeKind.LINEAR, classBias = listOf(1.0, 2.0)).leaningsIn(classes))
    }

    @Test
    fun theSweepLeansOnTextsLikeOnesLabeledOnlyWhereItHelpsAndNeverOnAHeldOutOne() {
        // A model that calls everything transactional, and labels where a fifth are marketing from
        // four templates: each text's template-mates sit in other conversations, other folds.
        val trans = classes.indexOf("transactional")
        val mark = classes.indexOf("marketing")
        val items = (0 until 100).map { i ->
            val template = i % 5 == 0
            val words = if (template) {
                val t = i / 5 % 4
                listOf("w:your", "w:order", "w:tmpl${t}a", "w:tmpl${t}b", "w:tmpl${t}c", "w:tmpl${t}d", "w:filler$i")
            } else {
                listOf("w:your", "w:order", "w:alpha$i", "w:beta$i", "w:gamma$i")
            }
            TrainingItem(words, null, if (template) mark else trans, 1.0, group = i.toLong(), key = "sms:t$i", source = TrainingItem.Source.USER)
        }
        val scorer = SweepScorer(base, items, emptyList(), unwanted = emptySet(), filterAt = 0.9)
        val k = classes.size
        val cv = items.indices.map { i -> RecipeTrainer.Row(i, DoubleArray(k) { if (it == trans) ln(0.6) else ln(0.4 / (k - 1)) }) }
        val recipe = Recipe(kind = RecipeKind.LINEAR, senderMemory = 0.0)
        val plain = scorer.score(recipe, cv, tune = false)!!
        assertEquals(0.8, plain.accuracy, 1e-9)
        val tuned = scorer.score(recipe, cv, tune = true)!!
        assertTrue(tuned.recipe.templateMemory > 0, "texts like it ${tuned.recipe.templateMemory}")
        assertEquals(1.0, tuned.accuracy, 1e-9)
        // A template seen nowhere else (one text, so only in its own fold) has no lookalike to lean on.
        val lonely = items.take(99) + TrainingItem(listOf("w:your", "w:order", "w:solo1", "w:solo2", "w:solo3", "w:solo4"), null, mark, 1.0, group = 999, key = "sms:solo", source = TrainingItem.Source.USER)
        val scoredLonely = SweepScorer(base, lonely, emptyList(), unwanted = emptySet(), filterAt = 0.9)
            .score(recipe.copy(templateMemory = 3.0), lonely.indices.map { i -> RecipeTrainer.Row(i, cv[0].logits) }, tune = false)!!
        assertEquals(99.0 / 100, scoredLonely.accuracy, 1e-9)
    }

    @Test
    fun aTryWellBehindTheBestStopsAfterItsFirstPartsAndIsNeverKept() = runBlocking {
        // Scoring stops where told to, with the first parts' rows only.
        val recipe = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 5, learningRate = 0.2, includeCorpus = false)
        val whole = RecipeTrainer.crossValidateRows(recipe, base, scored, others)
        var asked = 0
        val part = RecipeTrainer.crossValidateRows(recipe, base, scored, others, keepGoing = { asked++; false })
        assertEquals(1, asked)
        assertTrue(part.isNotEmpty() && part.size < whole.size, "${part.size} of ${whole.size}")
        val wholeByIndex = whole.associateBy { it.index }
        part.forEach { r -> assertContentEquals(wholeByIndex.getValue(r.index).logits.toList(), r.logits.toList()) }
        // A try that's hopeless next to the starts: stopped early, below the best, never the one kept.
        val awful = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 10, epochs = 1, learningRate = 0.001, includeCorpus = false, serviceWeight = 0.0)
        val many = texts.take(600).mapIndexed { i, t ->
            TrainingItem(Featurizer.features(Featurizer.Input(t.sender, t.body)), null, classes.indexOf(t.category.key), 3.0, group = (i / 3).toLong(), key = "sms:m$i", sender = t.sender, at = i.toLong(), source = TrainingItem.Source.USER)
        }
        val plan = RecipeSweep.Plan(rounds = 0, perRound = 1, steeringCalls = 0)
        val result = RecipeSweep(SweepScorer(base, many, emptyList(), unwanted = emptySet(), filterAt = 0.9), ServiceSteerer(FakeProvider()), plan).run(emptyList(), starts = listOf("awful" to awful))
        val tried = result.trials.single { it.from == "awful" }
        assertTrue(tried.dropped, "${tried.accuracy}")
        assertTrue(tried.accuracy < result.trials.filter { !it.dropped }.maxOf { it.accuracy })
        assertFalse(result.trials.first().dropped)
        assertTrue(result.blend?.recipe?.members?.none { it == awful } ?: true)
        // With few labels (here 120, the first parts under 200), three points is a text or two: every try finishes.
        val few = RecipeSweep(scorer(), ServiceSteerer(FakeProvider()), plan).run(emptyList(), starts = listOf("awful" to awful))
        assertTrue(few.trials.none { it.dropped })
    }

    @Test
    fun noOtherLabelInAHeldOutConversationTrainsIt() {
        // Each conversation's texts carry a mark of it, and a service's label on its middle text
        // says that text (mark and all) is a wrong category. Held out with its conversation, that
        // label can't teach the answer away; unmarked by conversation, it leaks in. (Held out, it
        // still trains the other folds, wrongly: a few points, never the leak's twenty and more.)
        val k = classes.size
        val marked = scored.map { it.copy(features = it.features!! + "conv:${it.group}") }
        fun contrary(grouped: Boolean) = marked.groupBy { it.group }.map { (g, texts) ->
            TrainingItem(texts[1].features, null, (texts[0].label + 1) % k, 10.0, group = if (grouped) g else -1, key = "svc:$g")
        }
        val recipe = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 14, epochs = 5, learningRate = 0.2)
        fun accuracy(extra: List<TrainingItem>) = RecipeTrainer.crossValidate(recipe, base, marked, others + extra)
            .count { (i, l) -> l.indices.maxBy { l[it] } == marked[i].label }.toDouble() / marked.size
        val clean = accuracy(emptyList())
        val grouped = accuracy(contrary(grouped = true))
        val leaked = accuracy(contrary(grouped = false))
        assertTrue(clean - grouped < 0.05, "grouped $grouped vs clean $clean")
        assertTrue(leaked < grouped - 0.15, "leaked $leaked vs grouped $grouped")
    }

    @Test
    fun theSweepEndsEarlyOnlyWhenTheSteeringTwiceSeesAlmostNothingLeft() = runBlocking {
        // Twice under 10%, half its rounds done: it ends.
        val nothing = FakeProvider(more = 0.05)
        val ended = RecipeSweep(scorer(), ServiceSteerer(nothing), RecipeSweep.Plan(rounds = 4, perRound = 1, steeringCalls = 4, endEarly = true)).run(emptyList())
        assertTrue(ended.stoppedEarly)
        assertEquals(2, nothing.calls)
        assertEquals(2, ended.rounds.size)
        // 19% (as a real sweep's steering said) isn't nothing: it keeps looking, every round.
        val little = FakeProvider(more = 0.19)
        val kept = RecipeSweep(scorer(), ServiceSteerer(little), RecipeSweep.Plan(rounds = 4, perRound = 1, steeringCalls = 4, endEarly = true)).run(emptyList())
        assertFalse(kept.stoppedEarly)
        assertEquals(4, kept.rounds.size)
        // Unless told it may (it isn't, to start), it never ends early.
        val never = RecipeSweep(scorer(), ServiceSteerer(FakeProvider(more = 0.0)), RecipeSweep.Plan(rounds = 3, perRound = 1, steeringCalls = 3)).run(emptyList())
        assertFalse(never.stoppedEarly)
        assertEquals(3, never.rounds.size)
    }

    @Test
    fun contextFeaturesSayWhenATextCameAndWhatCameBefore() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        // Saturday 2026-10-03, 23:30 UTC; a reply to the user's text five minutes before.
        val sat = java.util.GregorianCalendar(utc).apply { clear(); set(2026, java.util.Calendar.OCTOBER, 3, 23, 30) }.timeInMillis
        val reply = ContextFeatures.of(com.ericflo.winnow.classifier.message.MessageContext(sat, earlierFromThem = 7, earlierFromYou = 4, answersYou = true, sinceLastMillis = 5 * 60_000L), utc)
        assertEquals(setOf("__ctx_hour_late__", "__ctx_weekend__", "__ctx_them_some__", "__ctx_you_wrote__", "__ctx_answers_you__", "__ctx_gap_minutes__"), reply.toSet())
        // A stranger's first text on a weekday morning.
        val tue = java.util.GregorianCalendar(utc).apply { clear(); set(2026, java.util.Calendar.OCTOBER, 6, 9, 0) }.timeInMillis
        val first = ContextFeatures.of(com.ericflo.winnow.classifier.message.MessageContext(tue, 0, 0, null, null), utc)
        assertEquals(setOf("__ctx_hour_morning__", "__ctx_weekday__", "__ctx_first__", "__ctx_them_none__", "__ctx_you_never__"), first.toSet())
        assertTrue(ContextFeatures.of(null).isEmpty())
        assertEquals("an answer to your text", Featurizer.describe("__ctx_answers_you__"))
    }

    @Test
    fun aModelThatLearnsFromContextReadsItAndABlendGivesItOnlyToThoseThatDo() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        // Context that tells the categories apart where words alone might not: each category at its own hour.
        val withContext = scored.map { item ->
            val ctx = com.ericflo.winnow.classifier.message.MessageContext(item.label * 4 * 3_600_000L, item.label, 0, null, null)
            TrainingItem(item.features, null, item.label, 3.0, group = item.group, key = item.key, sender = item.sender, source = TrainingItem.Source.USER, contextFeatures = ContextFeatures.of(ctx, utc))
        }
        val reads = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 5, learningRate = 0.2, context = true)
        val plain = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 5, learningRate = 0.2)
        val model = RecipeTrainer.train(reads, base, withContext)
        assertTrue(model.readsContext && model is ContextPredictor)
        val item = withContext.first()
        val p = model.probabilities(item.features!! + item.contextFeatures!!)
        val q = LocalModel.softmax(RecipeTrainer.logits(model, item)!!)
        p.indices.forEach { assertEquals(p[it], q[it], 1e-9) }
        // Scored by conversation, the hours it learned carry over.
        fun accuracy(r: Recipe) = RecipeTrainer.crossValidate(r, base, withContext, others).count { (i, l) -> l.indices.maxBy { l[it] } == withContext[i].label }.toDouble() / withContext.size
        assertTrue(accuracy(reads) > accuracy(plain), "context ${accuracy(reads)} vs words ${accuracy(plain)}")
        // In a blend, a member that didn't learn from context never sees it.
        val blend = Recipe(kind = RecipeKind.BLEND, members = listOf(reads, plain))
        val both = RecipeTrainer.train(blend, base, withContext) as BlendPredictor
        assertTrue(both.readsContext)
        val f = item.features!! + item.contextFeatures!!
        val expected = BlendPredictor.blendLogits(listOf(RecipeTrainer.logits(both.members[0], item)!!, RecipeTrainer.logits(both.members[1], item)!!), listOf(0.5, 0.5))
        val got = both.probabilities(f)
        LocalModel.softmax(expected).forEachIndexed { c, x -> assertEquals(x, got[c], 1e-9) }
        // And it's kept and read back whole.
        val bytes = java.io.ByteArrayOutputStream().also { LabModelFile.write(blend, both, 1f, it) }.toByteArray()
        val back = LabModelFile.read(blend, java.io.ByteArrayInputStream(bytes))!!
        assertTrue(back.readsContext)
        back.probabilities(f).forEachIndexed { c, x -> assertEquals(got[c], x, 5e-3) }
        assertNotNull(Recipe(kind = RecipeKind.PERSONAL, context = true).problem())
    }

    @Test
    fun aBlendWithPiecesOfWordsIsKeptAndReadBackWhole() {
        val blend = Recipe(
            kind = RecipeKind.BLEND,
            members = listOf(
                Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 4, learningRate = 0.2, pieces = true),
                Recipe(kind = RecipeKind.NEURAL, buckets = 1 shl 11, layers = listOf(8), epochs = 2, learningRate = 0.05),
            ),
            memberWeights = listOf(2.0, 1.0),
        )
        val model = LabModelFile.calibrate(RecipeTrainer.train(blend, base, scored), 1.6f)
        val bytes = java.io.ByteArrayOutputStream().also { assertTrue(LabModelFile.write(blend, model, 1.6f, it)) }.toByteArray()
        val back = LabModelFile.read(blend, java.io.ByteArrayInputStream(bytes))!!
        assertTrue(back is BlendPredictor && back.members[0] is PiecesPredictor)
        val probe = Featurizer.features(Featurizer.Input("+15555550101", "Your redelivery fee is unpaid, pay now at usps-help.top"))
        val p = model.probabilities(probe)
        val q = back.probabilities(probe)
        // A linear model keeps its weights in 8 bits: the odds come back within a hair.
        p.indices.forEach { assertEquals(p[it], q[it], 5e-3) }
        assertEquals(LabModelFile.parameters(model), LabModelFile.parameters(back))
        // Calibrated as a whole: a higher temperature, less sure.
        assertTrue(q.max() < LabModelFile.calibrate(back, 1f).probabilities(probe).max())
    }

    @Test
    fun theRestOfAConversationLabeledOneWayTeachesUnseenOnes() {
        // Conversations of three of "your" labels, one category each, and eight more of their texts unlabeled.
        val byCategory = texts.groupBy { it.category }
        val groups = byCategory.values.flatMap { it.chunked(11).filter { c -> c.size == 11 }.take(4) }
        val labeled = groups.flatMapIndexed { g, c -> c.take(3).mapIndexed { j, t ->
            TrainingItem(Featurizer.features(Featurizer.Input(t.sender, t.body)), null, classes.indexOf(t.category.key), 1.0, group = g.toLong(), key = "sms:$g-$j", source = TrainingItem.Source.USER)
        } }
        val rest = groups.flatMapIndexed { g, c -> c.drop(3).mapIndexed { j, t ->
            TrainingItem(Featurizer.features(Featurizer.Input(t.sender, t.body)), null, classes.indexOf(t.category.key), 1.0, group = g.toLong(), key = "conversation:$g-$j", source = TrainingItem.Source.CONVERSATION)
        } }
        val base = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 14, epochs = 20, learningRate = 0.2, includeCorpus = false)
        fun accuracy(r: Recipe) = RecipeTrainer.crossValidate(r, this.base, labeled, rest).count { (i, l) -> l.indices.maxBy { l[it] } == labeled[i].label }.toDouble() / labeled.size
        val without = accuracy(base)
        val with = accuracy(base.copy(conversationWeight = 0.5))
        assertTrue(with > without, "with the rest of the conversations $with, without $without")
        // A personal layer never takes them.
        assertEquals(0.0, rest.first().weightUnder(Recipe(conversationWeight = 1.0)))
    }

    @Test
    fun leaningsAreScoredAsTheLabScoresThemNeverCostAccuracyAndAreKept() {
        val s = scorer()
        val recipe = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 5, learningRate = 0.2)
        val cv = s.crossValidate(recipe)
        val plain = s.score(recipe, cv, tune = false)!!
        val tuned = s.score(recipe, cv, tune = true)!!
        assertTrue(tuned.accuracy >= plain.accuracy)
        // A recipe with leanings, trained and scored as the Lab does it, gives what the sweep said.
        val leaning = recipe.copy(classBias = listOf(0.5, -0.25, 0.0, 0.75, -0.5, 0.25).take(classes.size))
        assertNull(leaning.problem())
        val asLab = RecipeTrainer.crossValidate(leaning, base, scored, others)
        assertEquals(s.score(leaning, cv, tune = false)!!.accuracy, s.score(leaning.copy(classBias = emptyList()), s.rows(asLab), tune = false)!!.accuracy, 1e-12)
        // Kept, and read back with its calibration on top.
        val model = LabModelFile.calibrate(RecipeTrainer.train(leaning, base, scored), 1.4f)
        assertTrue(model is BiasedPredictor)
        val bytes = java.io.ByteArrayOutputStream().also { assertTrue(LabModelFile.write(leaning, model, 1.4f, it)) }.toByteArray()
        val back = LabModelFile.read(leaning, java.io.ByteArrayInputStream(bytes))!!
        val f = scored.first().features!!
        model.probabilities(f).zip(back.probabilities(f).toList()).forEach { (a, b) -> assertEquals(a, b, 5e-3) }
        assertNotNull(Recipe(classBias = listOf(1.0)).problem())
    }

    @Test
    fun wordsBySenderLearnWhatAWordMeansFromEachKindOfSender() {
        val f = SenderCrosses.expand(listOf("__sender_short_code__", "w:appointment", "b:your appointment"))
        assertTrue("x:short_code|appointment" in f && f.none { it.startsWith("x:") && "your" in it })
        assertTrue("x:phone_number+you|tonight" in SenderCrosses.expand(listOf("__sender_phone_number__", "__known__", "w:tonight")))
        // The same two words, opposite things from a business and from someone you text: words
        // alone can't say; words by sender can.
        val transactional = classes.indexOf("transactional")
        val personal = classes.indexOf("personal")
        val items = (0 until 120).map { i ->
            val business = i % 2 == 0
            val word = if (i % 4 < 2) "w:pickup" else "w:tomorrow"
            val label = if (business == (word == "w:pickup")) transactional else personal
            val kind = if (business) listOf("__sender_short_code__") else listOf("__sender_phone_number__", "__known__")
            TrainingItem(kind + word + "w:filler${i % 7}", null, label, 1.0, group = (i / 2).toLong(), key = "sms:x$i", source = TrainingItem.Source.USER)
        }
        val plain = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 20, learningRate = 0.2, includeCorpus = false)
        fun accuracy(r: Recipe) = RecipeTrainer.crossValidate(r, base, items, emptyList()).count { (i, l) -> l.indices.maxBy { l[it] } == items[i].label }.toDouble() / items.size
        assertTrue(accuracy(plain.copy(crosses = true)) > 0.95, "by sender ${accuracy(plain.copy(crosses = true))}")
        assertTrue(accuracy(plain) < 0.8, "words alone ${accuracy(plain)}")
        // It reads each new text the same way, and is kept and read back whole.
        val model = RecipeTrainer.train(plain.copy(crosses = true), base, items)
        assertTrue(model is CrossesPredictor)
        val p = model.probabilities(items[0].features!!)
        val q = LocalModel.softmax(RecipeTrainer.logits(model, items[0])!!)
        p.indices.forEach { assertEquals(p[it], q[it], 1e-9) }
        val bytes = java.io.ByteArrayOutputStream().also { LabModelFile.write(plain.copy(crosses = true), model, 1f, it) }.toByteArray()
        assertTrue(LabModelFile.read(plain.copy(crosses = true), java.io.ByteArrayInputStream(bytes)) is CrossesPredictor)
    }

    @Test
    fun oddsAreSpreadSoARoundLooksAround() {
        // A steering sure of one value: none ends up above half, and values tried less count more.
        val sure = mapOf(Knob.EPOCHS to Knob.EPOCHS.values.associateWith { if (it == "60") 0.98 else 0.02 / (Knob.EPOCHS.values.size - 1) })
        val counts = mapOf(Knob.EPOCHS to mapOf("60" to 9, "20" to 3))
        val shaped = SweepExploration.shape(sure, counts, previousTop = null, best = null).getValue(Knob.EPOCHS)
        assertEquals(1.0, shaped.values.sum(), 1e-9)
        assertTrue(shaped.values.all { it <= 0.5 + 1e-9 }, shaped.toString())
        assertTrue(shaped.getValue("100") > shaped.getValue("20"))
        // Leaning toward the best's value again, round after round, counts for half of that.
        val leaning = mapOf(Knob.EPOCHS to Knob.EPOCHS.values.associateWith { if (it == "60") 0.6 else 0.4 / (Knob.EPOCHS.values.size - 1) })
        val again = SweepExploration.shape(leaning, emptyMap(), previousTop = mapOf(Knob.EPOCHS to "60"), best = mapOf(Knob.EPOCHS to "60")).getValue(Knob.EPOCHS)
        assertTrue(again.getValue("60") < SweepExploration.shape(leaning, emptyMap(), null, null).getValue(Knob.EPOCHS).getValue("60"))
    }

    @Test
    fun aRoundJittersFromTheBestAndExploresTheLeastTried() {
        val best = SweepSpace.STARTS[2]
        val uniform = Knob.entries.associateWith { k -> k.values.associateWith { 1.0 } }
        val round = SweepSampler.round(uniform, best, setOf(SweepSpace.keyOf(best)), 8, Random(3), stalled = 2)
        assertEquals(8, round.size)
        fun distance(s: Map<Knob, String>) = Knob.entries.count { k -> k.usedBy(best[Knob.KIND] == "neural") && s[k] != best[k] }
        // Jitters: a knob or two from the best.
        assertTrue(round.count { distance(it) <= 2 } >= 3, round.map(::distance).toString())
        // Explorers: far from it.
        assertTrue(round.any { distance(it) >= 5 }, round.map(::distance).toString())
    }

    @Test
    fun aSteeringThatLeansTheSameWayEveryRoundStillHasTheSweepLookAround() = runBlocking {
        // Like a real sweep's: 92-99% on one point, five rounds running.
        val stuck = FakeProvider(sure = mapOf("passes" to "60", "step" to "0.2", "buckets" to "65536", "l2" to "1e-5", "service_label_weight" to "0.35", "kind" to "linear"))
        val result = RecipeSweep(scorer(), ServiceSteerer(stuck), RecipeSweep.Plan(rounds = 3, perRound = 6, steeringCalls = 3)).run(emptyList())
        val steered = result.trials.filter { it.round >= 1 && it.settings != null }
        assertTrue(steered.size >= 15)
        // Far from all on that point: several passes, steps and kinds tried.
        assertTrue(steered.mapNotNull { it.settings?.get("passes") }.distinct().size >= 4)
        assertTrue(steered.mapNotNull { it.settings?.get("step") }.distinct().size >= 3)
        assertTrue(steered.count { it.settings?.get("passes") == "60" && it.settings?.get("step") == "0.2" && it.settings?.get("buckets") == "65536" } < steered.size / 2)
        assertTrue(result.rounds.all { it.made != null })
        // It's told what's been tried and where it leaned.
        val shown = stuck.requests.last().state.toString()
        assertTrue("times_each_value_was_tried" in shown && "your_earlier_leanings" in shown)
    }

    @Test
    fun aConversationsEarlierTextsLeanAnAnswerTheWayTheyRead() {
        // Two classes; the text itself barely leans the first, its conversation clearly the second.
        val p = doubleArrayOf(0.55, 0.45)
        val earlier = listOf(doubleArrayOf(0.1, 0.9), doubleArrayOf(0.2, 0.8))
        assertTrue(ConversationReading.lean(p, earlier, 1.0)[1] > 0.5)
        assertContentEquals(p, ConversationReading.lean(p, earlier, 0.0))
        assertContentEquals(p, ConversationReading.lean(p, emptyList(), 2.0))
        // A text whose words are sure stays itself against a lukewarm conversation.
        assertTrue(ConversationReading.lean(doubleArrayOf(0.97, 0.03), listOf(doubleArrayOf(0.4, 0.6)), 1.0)[0] > 0.9)
        assertEquals(1, ConversationReading.leaning(earlier))
    }

    @Test
    fun readingConversationsHelpsWhereEachIsOneThingAndTheSweepFindsItForFree() {
        // Each of "your" labeled texts with its conversation's earlier texts: more of the same category.
        val byCategory = texts.groupBy { it.category }
        val groups = byCategory.values.flatMap { it.chunked(7).filter { c -> c.size == 7 }.take(5) }
        fun f(t: LabeledText) = Featurizer.features(Featurizer.Input(t.sender, t.body))
        val labeled = groups.mapIndexed { g, c ->
            TrainingItem(f(c[0]), null, classes.indexOf(c[0].category.key), 1.0, group = g.toLong(), key = "sms:c$g", source = TrainingItem.Source.USER, earlier = c.drop(1).map(::f))
        }
        val s = SweepScorer(base, labeled, others, emptySet(), 0.9)
        val recipe = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 13, epochs = 8, learningRate = 0.2, senderMemory = 0.0)
        val rows = s.crossValidate(recipe)
        assertTrue(rows.all { it.earlier.size == 6 })
        val alone = s.score(recipe, rows, tune = false)!!.accuracy
        val reading = s.score(recipe.copy(conversationReading = 1.0), rows, tune = false)!!.accuracy
        assertTrue(reading > alone, "reading its conversation $reading, alone $alone")
        // Tuned for free, it's chosen.
        assertTrue(s.score(recipe, rows, tune = true)!!.recipe.conversationReading > 0)
    }

    @Test
    fun foldsTrainedSideBySideScoreExactlyAsOneAtATime() {
        val recipe = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 5, learningRate = 0.2, inputDropout = 0.2)
        val one = RecipeTrainer.crossValidateRows(recipe, base, scored, others, parallelism = 1)
        val four = RecipeTrainer.crossValidateRows(recipe, base, scored, others, parallelism = 4)
        assertEquals(one.map { it.index }, four.map { it.index })
        one.zip(four).forEach { (a, b) -> assertContentEquals(a.logits, b.logits) }
        // A failure in one fold comes out as itself.
        val stop = java.util.concurrent.atomic.AtomicInteger()
        val e = runCatching { RecipeTrainer.crossValidateRows(recipe, base, scored, others, parallelism = 3, stopped = { stop.incrementAndGet() > 3 }) }.exceptionOrNull()
        assertTrue(e is java.util.concurrent.CancellationException, "$e")
    }

    @Test
    fun textShapesFindWhatTheWordsLose() {
        fun has(body: String) = TextShapes.of(body).map { it.removePrefix("__shape_").removeSuffix("__") }.toSet()
        assertEquals(setOf("percent", "percent_off", "promo_code"), has("Take 20% off everything with promo code SAVE20"))
        assertEquals(setOf("time", "date", "weekday"), has("Reminder: your appointment is Tue 10/15 at 3:30 PM"))
        assertTrue("date" in has("See you on Oct 21st"))
        assertTrue("order_number" in has("Your order #A1B2C3D4 has shipped"))
        assertTrue("order_number" in has("Tracking number: 1Z999AA10123456784"))
        assertEquals(emptySet<String>(), has("hey are we still on for lunch"))
        assertEquals("a percent off", Featurizer.describe("__shape_percent_off__"))
    }

    @Test
    fun aModelThatLearnsShapesReadsThemAndIsKeptWhole() {
        // Two classes the words don't separate, their shapes do.
        val marketing = classes.indexOf("marketing")
        val transactional = classes.indexOf("transactional")
        val items = (0 until 80).map { i ->
            val promo = i % 2 == 0
            val body = if (promo) "Your account update ${i % 5}: take 20% off with code SAVE${i}X" else "Your account update ${i % 5}: order #ZX${1000 + i}Q confirmed"
            TrainingItem(listOf("w:your", "w:account", "w:update"), null, if (promo) marketing else transactional, 1.0, group = (i / 2).toLong(), key = "sms:s$i", source = TrainingItem.Source.USER, shapeFeatures = TextShapes.of(body))
        }
        val plain = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 20, learningRate = 0.2, includeCorpus = false)
        fun accuracy(r: Recipe) = RecipeTrainer.crossValidate(r, base, items, emptyList()).count { (i, l) -> l.indices.maxBy { l[it] } == items[i].label }.toDouble() / items.size
        assertTrue(accuracy(plain.copy(shapes = true)) > 0.95)
        assertTrue(accuracy(plain) < 0.7)
        val model = RecipeTrainer.train(plain.copy(shapes = true), base, items)
        assertTrue(model.readsShapes)
        val p = model.probabilities(items[0].features!! + items[0].shapeFeatures!!)
        val q = LocalModel.softmax(RecipeTrainer.logits(model, items[0])!!)
        p.indices.forEach { assertEquals(p[it], q[it], 1e-9) }
        val bytes = java.io.ByteArrayOutputStream().also { LabModelFile.write(plain.copy(shapes = true), model, 1f, it) }.toByteArray()
        assertTrue(LabModelFile.read(plain.copy(shapes = true), java.io.ByteArrayInputStream(bytes))!!.readsShapes)
        // A model that didn't learn them never reads them.
        assertEquals(listOf("w:a"), featuresFor(RecipeTrainer.train(plain, base, items), listOf("w:a", "__shape_time__", "__ctx_weekend__")))
    }

    @Test
    fun aModelThatLearnsWordsThatMeanAlikeCarriesALabelToWordsItNeverSaw() {
        // "discount" taught marketing and "delivery" transactional; "selling" and "shipping" never seen.
        assertTrue(WordClusters.groups.size > 20_000, "${WordClusters.groups.size} words grouped")
        assertContentEquals(WordClusters.groups["discount"], WordClusters.groups["selling"])
        assertContentEquals(WordClusters.groups["delivery"], WordClusters.groups["shipping"])
        assertEquals(listOf("w:discount", "x:other", "k:${WordClusters.groups.getValue("discount")[0]}"), WordClusters.expand(listOf("w:discount", "x:other")))
        val marketing = classes.indexOf("marketing")
        val transactional = classes.indexOf("transactional")
        val items = (0 until 40).map { i ->
            val promo = i % 2 == 0
            TrainingItem(listOf(if (promo) "w:discount" else "w:delivery", "w:note$i"), null, if (promo) marketing else transactional, 1.0, group = i.toLong(), key = "sms:k$i", source = TrainingItem.Source.USER)
        }
        val plain = Recipe(kind = RecipeKind.LINEAR, buckets = 1 shl 12, epochs = 20, learningRate = 0.2, includeCorpus = false)
        val grouped = plain.copy(clusters = true)
        fun says(model: Predictor, word: String) = model.probabilities(listOf("w:$word")).let { p -> p.indices.maxBy { p[it] } }
        val model = RecipeTrainer.train(grouped, base, items)
        assertEquals(marketing, says(model, "selling"))
        assertEquals(transactional, says(model, "shipping"))
        // Without the groups, two words it never saw read the same: it can't get both.
        val words = RecipeTrainer.train(plain, base, items)
        assertEquals(says(words, "selling"), says(words, "shipping"))
        // Kept and read back, it reads the same; scored the way it was trained.
        val bytes = java.io.ByteArrayOutputStream().also { LabModelFile.write(grouped, model, 1f, it) }.toByteArray()
        val back = LabModelFile.read(grouped, java.io.ByteArrayInputStream(bytes))!!
        assertEquals(marketing, says(back, "selling"))
        val p = model.probabilities(items[0].features!!)
        val q = LocalModel.softmax(RecipeTrainer.logits(model, items[0])!!)
        p.indices.forEach { assertEquals(p[it], q[it], 1e-9) }
        assertNotNull(Recipe(kind = RecipeKind.PERSONAL, clusters = true).problem())
    }

    private class FakeProvider(private val fail: Boolean = false, private val more: Double = 0.8, private val sure: Map<String, String> = emptyMap()) : DecisionProvider {
        override val descriptor = ProviderDescriptor("fake", "Fake Jev", DataHandling.REMOTE)
        var calls = 0
        val requests = mutableListOf<DecisionRequest>()

        override suspend fun decide(request: DecisionRequest): DecisionResponse {
            calls++
            requests += request
            if (fail) throw ProviderException("offline", retryable = true)
            // Leans every knob to its first value but passes, which it wants small.
            val answers = request.questions.mapValues { (key, q) ->
                val options = q.options.keys.toList()
                when {
                    key in sure -> Distribution.of(options.associateWith { if (it == sure[key]) 0.98 else 0.02 / (options.size - 1) }, options)
                    key == ServiceSteerer.MORE -> Distribution.of(mapOf("yes" to more, "no" to 1 - more), options)
                    key == Knob.EPOCHS.key -> Distribution.of(mapOf("3" to 0.7, "5" to 0.3), options)
                    else -> Distribution.of(mapOf(options.first() to 0.6) + options.drop(1).associateWith { 0.4 / (options.size - 1) }, options)
                }
            }
            return DecisionResponse(answers, "fake", Usage(1000, 10, 0.001))
        }
    }
}
