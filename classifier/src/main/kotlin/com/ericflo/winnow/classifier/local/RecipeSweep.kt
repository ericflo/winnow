package com.ericflo.winnow.classifier.local

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * A setting a sweep turns: the values it tries, and what it means, in words a steering service
 * (see [ServiceSteerer]) reads. Every value is one a [Recipe] can take.
 */
enum class Knob(val key: String, val meaning: String, val values: List<String>, val neuralOnly: Boolean = false) {
    KIND("kind", "Linear: one weight per word and category. Neural: a small network over the words (it can learn combinations).", listOf("linear", "neural")),
    LAYERS("layers", "Neural only: the word embedding's width, then any hidden layer's (64-32 is two layers).", listOf("32", "64", "128", "64-32", "128-64", "256-64"), neuralOnly = true),
    WIDE("wide", "Neural only: a linear part beside the network, so single words still count directly.", listOf("yes", "no"), neuralOnly = true),
    DROPOUT("dropout", "Neural only: the share of hidden units left out of each training step.", listOf("0", "0.1", "0.25", "0.4"), neuralOnly = true),
    BUCKETS("buckets", "Words are hashed into this many buckets: more is wider, fewer words sharing one.", listOf("4096", "8192", "16384", "32768", "65536")),
    EPOCHS("passes", "Passes over every text while training: more fits the labels closer, and past a point carries over to new texts worse.", listOf("3", "5", "8", "12", "20", "30", "60")),
    STEP("step", "AdaGrad's step size.", listOf("0.02", "0.05", "0.1", "0.2", "0.4")),
    L2("l2", "How hard every weight is pulled toward zero.", listOf("0", "1e-6", "1e-5", "1e-4", "1e-3")),
    WORDS_OUT("words_left_out", "The share of a text's words left out of each training step, a different few each time.", listOf("0", "0.15", "0.3", "0.45")),
    PIECES("word_pieces", "Also learn from four-letter pieces of words, so words sharing a stem share what's learned.", listOf("no", "yes")),
    USER_WEIGHT("user_label_weight", "How much each of the person's own labels counts against one shipped example.", listOf("1", "2", "3", "5", "8")),
    SERVICE_WEIGHT("service_label_weight", "How much each of the classifier service's labels counts; 0 leaves them out.", listOf("0", "0.15", "0.35", "0.7", "1")),
    CORPUS_WEIGHT("shipped_example_weight", "How much each of the 1,493 hand-written shipped examples counts; 0 leaves them out.", listOf("0", "0.3", "1")),
    BALANCE("balance_categories", "Count each category equally, however many texts it has.", listOf("yes", "no")),
    ;

    companion object {
        fun byKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** Between recipes and a sweep's [Knob] settings. */
object SweepSpace {
    /** The recipe [settings] make: a value for every knob. */
    fun recipeOf(settings: Map<Knob, String>): Recipe {
        fun v(k: Knob) = settings[k] ?: k.values.first()
        val neural = v(Knob.KIND) == "neural"
        val corpus = v(Knob.CORPUS_WEIGHT).toDouble()
        return Recipe(
            kind = if (neural) RecipeKind.NEURAL else RecipeKind.LINEAR,
            buckets = v(Knob.BUCKETS).toInt(),
            layers = if (neural) v(Knob.LAYERS).split('-').map(String::toInt) else listOf(64),
            wide = !neural || v(Knob.WIDE) == "yes",
            epochs = v(Knob.EPOCHS).toInt(),
            learningRate = v(Knob.STEP).toDouble(),
            l2 = v(Knob.L2).toDouble(),
            dropout = if (neural) v(Knob.DROPOUT).toDouble() else 0.0,
            inputDropout = v(Knob.WORDS_OUT).toDouble(),
            pieces = v(Knob.PIECES) == "yes",
            includeCorpus = corpus > 0,
            corpusWeight = if (corpus > 0) corpus else 1.0,
            userWeight = v(Knob.USER_WEIGHT).toDouble(),
            serviceWeight = v(Knob.SERVICE_WEIGHT).toDouble(),
            balance = v(Knob.BALANCE) == "yes",
        )
    }

    /**
     * [recipe] as the sweep's settings, each knob at its nearest value; null for a kind the sweep
     * doesn't turn (the personal layer, a blend).
     */
    fun settingsOf(recipe: Recipe): Map<Knob, String>? {
        if (recipe.kind != RecipeKind.LINEAR && recipe.kind != RecipeKind.NEURAL) return null
        val neural = recipe.kind == RecipeKind.NEURAL
        fun nearest(k: Knob, x: Double): String = k.values.minBy { v ->
            val y = v.toDouble()
            // Steps, weights and pulls are told apart by their ratios.
            if (x > 0 && y > 0) abs(ln(x / y)) else if (x == y) 0.0 else Double.MAX_VALUE / 2 + abs(x - y)
        }
        val layers = recipe.layers.joinToString("-")
        return mapOf(
            Knob.KIND to if (neural) "neural" else "linear",
            Knob.LAYERS to (Knob.LAYERS.values.firstOrNull { it == layers } ?: Knob.LAYERS.values.minBy { v -> abs(v.split('-').sumOf(String::toInt) - recipe.layers.sum()) }),
            Knob.WIDE to if (recipe.wide) "yes" else "no",
            Knob.DROPOUT to nearest(Knob.DROPOUT, recipe.dropout),
            Knob.BUCKETS to nearest(Knob.BUCKETS, recipe.buckets.toDouble()),
            Knob.EPOCHS to nearest(Knob.EPOCHS, recipe.epochs.toDouble()),
            Knob.STEP to nearest(Knob.STEP, recipe.learningRate),
            Knob.L2 to nearest(Knob.L2, recipe.l2),
            Knob.WORDS_OUT to nearest(Knob.WORDS_OUT, recipe.inputDropout),
            Knob.PIECES to if (recipe.pieces) "yes" else "no",
            Knob.USER_WEIGHT to nearest(Knob.USER_WEIGHT, recipe.userWeight),
            Knob.SERVICE_WEIGHT to nearest(Knob.SERVICE_WEIGHT, recipe.serviceWeight),
            Knob.CORPUS_WEIGHT to if (recipe.includeCorpus) nearest(Knob.CORPUS_WEIGHT, recipe.corpusWeight) else "0",
            Knob.BALANCE to if (recipe.balance) "yes" else "no",
        )
    }

    /** One key per distinct try: a linear one's network settings don't make it another. */
    fun keyOf(settings: Map<Knob, String>): String {
        val neural = settings[Knob.KIND] == "neural"
        return Knob.entries.joinToString(";") { k -> if (k.neuralOnly && !neural) "${k.key}=-" else "${k.key}=${settings[k]}" }
    }

    /** Where a sweep starts: different ways to keep a model from memorizing, one of each kind. */
    val STARTS: List<Map<Knob, String>> = listOf(
        settings("neural", layers = "64", buckets = "32768", epochs = "8", step = "0.05", l2 = "1e-6", wordsOut = "0.3"),
        settings("neural", layers = "64-32", buckets = "32768", epochs = "12", step = "0.05", l2 = "1e-6", dropout = "0.25", wordsOut = "0.15", pieces = "yes"),
        settings("linear", buckets = "65536", epochs = "60", step = "0.2", l2 = "1e-5"),
        settings("linear", buckets = "32768", epochs = "30", step = "0.2", l2 = "1e-5", wordsOut = "0.15", pieces = "yes"),
        settings("neural", layers = "128", buckets = "16384", epochs = "5", step = "0.05", l2 = "1e-5", wordsOut = "0.3"),
        settings("neural", layers = "64", wide = "no", buckets = "16384", epochs = "20", step = "0.05", l2 = "1e-6", dropout = "0.4", wordsOut = "0.3"),
    )

    private fun settings(
        kind: String, layers: String = "64", wide: String = "yes", dropout: String = "0", buckets: String, epochs: String, step: String, l2: String,
        wordsOut: String = "0", pieces: String = "no", user: String = "3", service: String = "0.35", corpus: String = "1", balance: String = "yes",
    ) = mapOf(
        Knob.KIND to kind, Knob.LAYERS to layers, Knob.WIDE to wide, Knob.DROPOUT to dropout, Knob.BUCKETS to buckets, Knob.EPOCHS to epochs,
        Knob.STEP to step, Knob.L2 to l2, Knob.WORDS_OUT to wordsOut, Knob.PIECES to pieces, Knob.USER_WEIGHT to user,
        Knob.SERVICE_WEIGHT to service, Knob.CORPUS_WEIGHT to corpus, Knob.BALANCE to balance,
    )
}

/** One try in a sweep, and how it scored. */
@Serializable
data class SweepTrial(
    val round: Int,
    val recipe: Recipe,
    /** Its settings by knob key; null for a recipe the sweep doesn't turn (a blend, the personal layer). */
    val settings: Map<String, String>?,
    /** On conversations it hadn't seen, as the Lab scores it (with the user's labels of each sender), and from the words alone. */
    val accuracy: Double,
    val macroF1: Double,
    val wordsAccuracy: Double,
    val scoredOn: Int,
    val millis: Long,
    /** Where it came from: the start, the user's best so far, a round's steering, or a blend of tries. */
    val from: String,
)

/** What steered one round: whose odds, what they leaned toward, and what it cost. */
@Serializable
data class SweepRound(
    val round: Int,
    val steeredBy: String,
    /** Each knob's likeliest value and its odds, as the steering gave them. */
    val leaning: List<Lean>,
    /** The odds the steering gave that another round would gain on the best so far. */
    val more: Double? = null,
    val costUsd: Double = 0.0,
    /** Anything the user should know: the service couldn't be reached, say, and the phone steered instead. */
    val note: String? = null,
)

@Serializable
data class Lean(val knob: String, val value: String, val odds: Double)

/** What the steering is shown: the data in counts, the knobs, every try so far. Never a text. */
class SweepState(
    val labeled: Int,
    val conversations: Int,
    /** The user's labels by category. */
    val categories: Map<String, Int>,
    val shippedExamples: Int,
    val serviceLabels: Int,
    val trials: List<SweepTrial>,
    val round: Int,
    val roundsLeft: Int,
) {
    val best: SweepTrial? get() = trials.maxByOrNull { it.accuracy }
}

/** Odds for each knob's values in the next round, and that another round gains anything. */
class Steering(val odds: Map<Knob, Map<String, Double>>, val more: Double, val by: String, val costUsd: Double = 0.0, val note: String? = null)

/** Decides where a sweep looks next, from how its tries have scored. */
interface Steerer {
    val name: String
    suspend fun steer(state: SweepState): Steering
}

/**
 * Steering without a service, on the phone: each value counts by the best try that used it,
 * values not tried yet hopefully (a little under the best), so the next round leans toward what
 * has worked and still looks at what hasn't been tried.
 */
object LocalSteerer : Steerer {
    override val name = "Winnow, on this phone"

    override suspend fun steer(state: SweepState): Steering {
        val tried = state.trials.filter { it.settings != null }
        val best = tried.maxOfOrNull { it.accuracy } ?: 0.0
        val odds = Knob.entries.associateWith { knob ->
            val scores = knob.values.associateWith { v -> tried.filter { it.settings?.get(knob.key) == v }.maxOfOrNull { it.accuracy } ?: (best - 0.005) }
            // A point of accuracy is worth e to the first; nothing ever drops below a few percent.
            val raw = scores.mapValues { (_, s) -> exp((s - best) / 0.01) }
            val total = raw.values.sum()
            raw.mapValues { (_, w) -> (w / total) * 0.9 + 0.1 / knob.values.size }
        }
        // Worth another round while the last one gained, or early on.
        val last = state.trials.filter { it.round == state.round - 1 }.maxOfOrNull { it.accuracy } ?: 0.0
        val before = state.trials.filter { it.round < state.round - 1 }.maxOfOrNull { it.accuracy } ?: 0.0
        val more = if (state.round <= 2 || last > before + 0.002) 0.7 else 0.35
        return Steering(odds, more, name)
    }
}

/** Draws a round's tries from a steering's odds. */
object SweepSampler {
    /**
     * [n] new tries: half from the best so far with a knob or two moved where the odds lean, half
     * drawn from the odds outright. Never one already [tried], never a recipe that can't train.
     */
    fun round(odds: Map<Knob, Map<String, Double>>, best: Map<Knob, String>?, tried: Set<String>, n: Int, random: Random): List<Map<Knob, String>> {
        val out = mutableListOf<Map<Knob, String>>()
        val taken = tried.toHashSet()
        repeat(n) { i ->
            for (attempt in 0 until 200) {
                val s = if (best != null && i < (n + 1) / 2) nudge(complete(best, odds, random), odds, random) else draw(odds, random)
                val key = SweepSpace.keyOf(s)
                if (key in taken || SweepSpace.recipeOf(s).problem() != null) continue
                taken += key
                out += s
                break
            }
        }
        return out
    }

    private fun draw(odds: Map<Knob, Map<String, Double>>, random: Random): Map<Knob, String> = Knob.entries.associateWith { pick(it, odds[it], random) }

    /** [settings] with any knob it lacks (a linear try's network settings) drawn from the odds. */
    private fun complete(settings: Map<Knob, String>, odds: Map<Knob, Map<String, Double>>, random: Random): Map<Knob, String> =
        Knob.entries.associateWith { k -> settings[k] ?: pick(k, odds[k], random) }

    /** [best] with one or two knobs moved: those the odds lean away from its value most likely. */
    private fun nudge(best: Map<Knob, String>, odds: Map<Knob, Map<String, Double>>, random: Random): Map<Knob, String> {
        val neural = best[Knob.KIND] == "neural"
        val movable = Knob.entries.filter { !it.neuralOnly || neural }
        val away = movable.associateWith { k -> (1.0 - (odds[k]?.get(best[k]) ?: 0.0)).coerceAtLeast(0.02) }
        val out = best.toMutableMap()
        repeat(1 + random.nextInt(2)) {
            val knob = weighted(away, random)
            val choices = (odds[knob] ?: knob.values.associateWith { 1.0 }).filterKeys { it != out[knob] }
            if (choices.isNotEmpty()) out[knob] = weighted(choices, random)
        }
        return out
    }

    private fun pick(knob: Knob, odds: Map<String, Double>?, random: Random): String = weighted(odds?.filterKeys { it in knob.values }?.takeIf { it.isNotEmpty() } ?: knob.values.associateWith { 1.0 }, random)

    private fun <T> weighted(odds: Map<T, Double>, random: Random): T {
        val total = odds.values.sumOf { it.coerceAtLeast(0.0) }
        if (total <= 0) return odds.keys.elementAt(random.nextInt(odds.size))
        var x = random.nextDouble() * total
        for ((k, w) in odds) {
            x -= w.coerceAtLeast(0.0)
            if (x <= 0) return k
        }
        return odds.keys.last()
    }
}

/**
 * Scores a sweep's tries the way the Lab scores a model: each of the user's labels by a model
 * trained without its conversation (see [RecipeTrainer.crossValidate]), its odds calibrated, then
 * with the user's labels of each sender from the other conversations (see [SenderMemory]). How
 * much those count is tried at each of [strengths] too, at no cost: it needs no training.
 */
class SweepScorer(
    private val base: LocalModel,
    val scored: List<TrainingItem>,
    val others: List<TrainingItem>,
    /** Categories that count as "should be filtered", and the confidence filtering needs, for the full metrics. */
    private val unwanted: Set<Int>,
    private val filterAt: Double,
    val strengths: List<Double> = listOf(0.0, 0.5, 1.0, 2.0, 3.0, 4.0),
) {
    val classes = base.classes
    private val folds = RecipeTrainer.foldsOf(scored)
    private val memories: Map<Int, SenderMemory> = folds?.let { f ->
        (0..f.max()).associateWith { fold -> SenderMemory.of(scored.indices.filter { f[it] != fold }.mapNotNull { i -> scored[i].sender?.let { it to scored[i].label } }, classes) }
    }.orEmpty()

    class Scoring(val recipe: Recipe, val logits: List<Pair<Int, DoubleArray>>, val accuracy: Double, val macroF1: Double, val wordsAccuracy: Double)

    fun crossValidate(recipe: Recipe, stopped: () -> Boolean = { false }) = RecipeTrainer.crossValidate(recipe, base, scored, others, stopped = stopped)

    /**
     * [cv] scored as the Lab would score [recipe]: at its own sender strength, or with [tune] at
     * whichever of [strengths] does best (the recipe returned says which).
     */
    fun score(recipe: Recipe, cv: List<Pair<Int, DoubleArray>>, tune: Boolean): Scoring? {
        if (cv.isEmpty()) return null
        val temperature = if (recipe.kind == RecipeKind.PERSONAL) base.temperature.toDouble() else RecipeTrainer.calibrate(cv.map { (i, s) -> s to scored[i].label }).toDouble()
        fun answers(strength: Double) = cv.map { (i, s) ->
            val p = LocalModel.softmax(s, temperature)
            val item = scored[i]
            val memory = folds?.let { memories[it[i]] }?.withStrength(strength)
            Scored(item.label, item.sender?.let { sender -> memory?.follow(p, sender, item.conversing)?.distribution } ?: p)
        }
        fun accuracy(rows: List<Scored>) = rows.count { it.predicted == it.label }.toDouble() / rows.size
        // Ties go to the recipe's own strength, then to the nearest to it.
        val strength = if (!tune) recipe.senderMemory else (strengths + recipe.senderMemory).distinct()
            .sortedBy { abs(it - recipe.senderMemory) }.maxBy { accuracy(answers(it)) }
        val m = MetricsCalculator.compute("sweep", "Cross-validated on your labels", classes, answers(strength), unwanted, filterAt)
        val words = cv.count { (i, s) -> s.indices.maxBy { s[it] } == scored[i].label }.toDouble() / cv.size
        return Scoring(recipe.copy(senderMemory = strength), cv, m.accuracy, m.macroF1, words)
    }

    /**
     * The best blend of [library]'s tries (each its cross-validated logits): a greedy pick, one
     * at a time and with repeats (a repeat counts twice), of whichever raises the blend's accuracy
     * most, up to [Recipe.MAX_MEMBERS] different ones. Its scoring is exactly what training the
     * blend and scoring it the same way would give, with no training at all: every training is
     * the same from the same texts. Null unless at least two tries blend better than the best alone.
     */
    fun blend(library: List<Pair<Recipe, List<Pair<Int, DoubleArray>>>>, tune: Boolean = true): Scoring? {
        val usable = library.filter { (r, cv) -> (r.kind == RecipeKind.LINEAR || r.kind == RecipeKind.NEURAL) && cv.isNotEmpty() }
        if (usable.size < 2) return null
        val byIndex = usable.map { (_, cv) -> cv.toMap() }
        val common = byIndex.map { it.keys }.reduce { a, b -> a intersect b }.sorted()
        if (common.isEmpty()) return null
        val probs = byIndex.map { m -> common.map { LocalModel.softmax(m.getValue(it)) } }
        fun accuracyOf(counts: IntArray): Double {
            val total = counts.sum().toDouble()
            return common.indices.count { j ->
                val k = classes.size
                val avg = DoubleArray(k)
                counts.forEachIndexed { m, c -> if (c > 0) probs[m][j].forEachIndexed { cl, p -> avg[cl] += c * p / total } }
                avg.indices.maxBy { avg[it] } == scored[common[j]].label
            }.toDouble() / common.size
        }
        val counts = IntArray(usable.size)
        var current = -1.0
        repeat(12) {
            val (pick, acc) = usable.indices.filter { m -> counts[m] > 0 || counts.count { it > 0 } < Recipe.MAX_MEMBERS }
                .map { m -> m to accuracyOf(counts.copyOf().also { it[m]++ }) }.maxBy { it.second }
            if (acc <= current + 1e-9 && counts.sum() > 0) return@repeat
            counts[pick]++
            current = acc
        }
        val members = usable.indices.filter { counts[it] > 0 }
        if (members.size < 2) return null
        val total = members.sumOf { counts[it] }.toDouble()
        val recipe = Recipe(
            kind = RecipeKind.BLEND,
            members = members.map { usable[it].first.copy(senderMemory = 0.0) },
            memberWeights = members.map { counts[it] / total },
        )
        val cv = common.map { i -> i to BlendPredictor.blendLogits(members.map { m -> byIndex[m].getValue(i) }, recipe.memberWeights) }
        return score(recipe, cv, tune)
    }
}

/**
 * A sweep: rounds of recipes tried on the user's labels and scored as the Lab scores a model,
 * each round steered by how the tries before it did: by [steerer] (a classifier service, given
 * every try's settings and scores, never a text), at most [Plan.steeringCalls] times, or by
 * [LocalSteerer] on the phone. At the end, the best blend of what it tried, when one beats every
 * try alone. Deterministic but for the steering: the same tries score the same.
 */
class RecipeSweep(
    private val scorer: SweepScorer,
    private val steerer: Steerer?,
    private val plan: Plan = Plan(),
) {
    @Serializable
    data class Plan(val rounds: Int = 4, val perRound: Int = 6, val steeringCalls: Int = 4, val seed: Int = 7)

    sealed interface Event {
        data class Trying(val round: Int, val n: Int, val of: Int, val recipe: Recipe) : Event
        data class Tried(val trial: SweepTrial) : Event
        data class Steered(val round: SweepRound) : Event
    }

    /** [steeringCalls]: calls made to the service, [steeringFailed] of them failing (the phone steered those rounds, and every one after). */
    class Result(val trials: List<SweepTrial>, val rounds: List<SweepRound>, val blend: SweepTrial?, val steeringCalls: Int, val steeringFailed: Int, val costUsd: Double, val stoppedEarly: Boolean)

    /**
     * Runs it. [baselines] (names and recipes: what the user has, to beat) are scored as they
     * are. [stopped] ends it between tries and inside each; what was tried by then stays.
     */
    suspend fun run(baselines: List<Pair<String, Recipe>>, onEvent: (Event) -> Unit = {}, stopped: () -> Boolean = { false }): Result {
        val trials = mutableListOf<SweepTrial>()
        val rounds = mutableListOf<SweepRound>()
        val library = mutableListOf<Pair<Recipe, List<Pair<Int, DoubleArray>>>>()
        val tried = HashSet<String>()
        val random = Random(plan.seed)
        var calls = 0
        var failed = 0
        var cost = 0.0
        var stoppedEarly = false

        fun keep(round: Int, scoring: SweepScorer.Scoring, millis: Long, from: String): SweepTrial {
            // A linear try's network settings mean nothing: not shown, not counted as evidence.
            val neural = scoring.recipe.kind == RecipeKind.NEURAL
            val settings = SweepSpace.settingsOf(scoring.recipe)?.filterKeys { !it.neuralOnly || neural }
            val trial = SweepTrial(round, scoring.recipe, settings?.mapKeys { it.key.key }, scoring.accuracy, scoring.macroF1, scoring.wordsAccuracy, scoring.logits.size, millis, from)
            trials += trial
            onEvent(Event.Tried(trial))
            return trial
        }

        fun tryOne(round: Int, n: Int, of: Int, recipe: Recipe, from: String, tune: Boolean = true) {
            if (stopped()) return
            onEvent(Event.Trying(round, n, of, recipe))
            val started = System.nanoTime()
            val cv = try {
                scorer.crossValidate(recipe, stopped)
            } catch (e: java.util.concurrent.CancellationException) {
                return
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            val scoring = scorer.score(recipe, cv, tune) ?: return
            library += recipe to cv
            keep(round, scoring, millis, from)
            // The user's own recipe with their labels of each sender counting differently: free to score.
            if (!tune) scorer.score(recipe, cv, tune = true)?.takeIf { it.recipe.senderMemory != recipe.senderMemory && it.accuracy > scoring.accuracy }
                ?.let { keep(round, it, 0, "$from, who sent it ×${it.recipe.senderMemory.let { s -> if (s % 1.0 == 0.0) s.toInt().toString() else s.toString() }}") }
        }

        // Round 0: what the user has, as it is, then the starts (none the same as one of theirs).
        baselines.forEach { (_, recipe) -> SweepSpace.settingsOf(recipe)?.takeIf { SweepSpace.recipeOf(it) == recipe }?.let { tried += SweepSpace.keyOf(it) } }
        val starts = SweepSpace.STARTS.filter { tried.add(SweepSpace.keyOf(it)) }
        val firstCount = starts.size + baselines.size
        var n = 0
        baselines.forEach { (name, recipe) -> tryOne(0, ++n, firstCount, recipe, name, tune = false) }
        starts.forEach { tryOne(0, ++n, firstCount, SweepSpace.recipeOf(it), "start") }

        for (round in 1..plan.rounds) {
            // Nothing could be scored (too few conversations): nothing to steer by, nothing to pay for.
            if (stopped() || trials.isEmpty()) break
            val state = SweepState(
                labeled = scorer.scored.size,
                conversations = scorer.scored.map { it.group }.distinct().size,
                categories = scorer.scored.groupingBy { scorer.classes[it.label] }.eachCount(),
                shippedExamples = scorer.others.count { it.source == TrainingItem.Source.CORPUS },
                serviceLabels = scorer.others.count { it.source == TrainingItem.Source.SERVICE },
                trials = trials.toList(), round = round, roundsLeft = plan.rounds - round,
            )
            // Every call counts against the cap, answered or not: a failed one may be paid for too.
            // After one fails, the phone steers the rest, so failures can't run up calls.
            val steering = if (steerer != null && calls < plan.steeringCalls && failed == 0) {
                calls++
                try {
                    steerer.steer(state).also { cost += it.costUsd }
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    val local = LocalSteerer.steer(state)
                    Steering(local.odds, local.more, local.by, note = "${steerer.name} couldn't steer this round (${e.message ?: e::class.simpleName}); the phone steers the rest.")
                }
            } else {
                LocalSteerer.steer(state)
            }
            rounds += SweepRound(
                round, steering.by,
                leaning = steering.odds.map { (k, o) -> o.maxBy { it.value }.let { (v, p) -> Lean(k.key, v, p) } },
                more = steering.more, costUsd = steering.costUsd, note = steering.note,
            ).also { onEvent(Event.Steered(it)) }
            // Told there's little left to gain, after a round of its own: done, without spending more.
            if (round >= 2 && steering.more < 0.25) {
                stoppedEarly = true
                break
            }
            val best = trials.filter { it.settings != null }.maxByOrNull { it.accuracy }?.settings?.mapKeys { Knob.byKey(it.key)!! }
            val next = SweepSampler.round(steering.odds, best, tried, plan.perRound, random)
            next.forEachIndexed { i, s ->
                tried += SweepSpace.keyOf(s)
                tryOne(round, i + 1, next.size, SweepSpace.recipeOf(s), steering.by)
            }
        }

        // A blend is picked and weighed on the very scores it's judged by: kept only when clearly ahead.
        val bestAlone = trials.maxOfOrNull { it.accuracy } ?: 0.0
        val blend = if (stopped()) null else scorer.blend(library)?.takeIf { it.accuracy >= bestAlone + BLEND_MARGIN }?.let { b ->
            val millis = b.recipe.members.sumOf { m -> trials.firstOrNull { it.recipe.copy(senderMemory = 0.0) == m }?.millis ?: 0 }
            keep(plan.rounds + 1, b, millis, "blend")
        }
        return Result(trials.sortedByDescending { it.accuracy }, rounds, blend, calls, failed, cost, stoppedEarly)
    }

    companion object {
        /** Half a point: what a blend must beat the best try alone by. */
        const val BLEND_MARGIN = 0.005
    }
}
