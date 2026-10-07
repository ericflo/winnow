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
enum class Knob(val key: String, val meaning: String, val values: List<String>, val neuralOnly: Boolean = false, val linearOnly: Boolean = false) {
    KIND("kind", "Linear: one weight per word and category. Neural: a small network over the words (it can learn combinations).", listOf("linear", "neural")),
    LAYERS("layers", "Neural only: the word embedding's width, then any hidden layer's (64-32 is two layers).", listOf("16", "32", "64", "128", "256", "64-32", "128-64", "128-128", "256-64"), neuralOnly = true),
    WIDE("wide", "Neural only: a linear part beside the network, so single words still count directly.", listOf("yes", "no"), neuralOnly = true),
    DROPOUT("dropout", "Neural only: the share of hidden units left out of each training step.", listOf("0", "0.1", "0.25", "0.4", "0.55"), neuralOnly = true),
    BAGS("bags", "Linear only: models trained on resamples of the texts and averaged.", listOf("1", "3", "5"), linearOnly = true),
    BUCKETS("buckets", "Words are hashed into this many buckets: more is wider, fewer words sharing one.", listOf("4096", "8192", "16384", "32768", "65536", "131072", "262144")),
    EPOCHS("passes", "Passes over every text while training: more fits the labels closer, and past a point carries over to new texts worse.", listOf("3", "5", "8", "12", "20", "30", "60", "100", "150", "200")),
    STEP("step", "AdaGrad's step size.", listOf("0.01", "0.02", "0.05", "0.1", "0.2", "0.4", "0.8")),
    L2("l2", "How hard every weight is pulled toward zero.", listOf("0", "1e-8", "1e-7", "1e-6", "1e-5", "1e-4", "1e-3")),
    WORDS_OUT("words_left_out", "The share of a text's words left out of each training step, a different few each time.", listOf("0", "0.15", "0.3", "0.45", "0.6")),
    PIECES("word_pieces", "Also learn from four-letter pieces of words, so words sharing a stem share what's learned.", listOf("no", "yes")),
    SHAPES("text_shapes", "Also learn from what a text's words lose: percents and percents off, times, dates, weekday names, promo codes, order and tracking numbers, a run of emoji, several links.", listOf("no", "yes")),
    CLUSTERS("words_that_mean_alike", "Also learn from groups of words that mean alike (made from GloVe's word vectors), so what one word taught carries over to words like it: \"sale\", \"discount\", \"clearance\".", listOf("no", "yes")),
    CROSSES("words_by_sender", "Also learn each word as from the kind of sender it came from (a business, a stranger, someone the person texts), so a word can mean different things from each.", listOf("no", "yes")),
    CONTEXT("context", "Also learn from when each text came and what came before it in its conversation: time of day, weekday or weekend, the first text or an answer to the person's, how much came before, how long since the last.", listOf("no", "yes")),
    USER_WEIGHT("user_label_weight", "How much each of the person's own labels counts against one shipped example.", listOf("1", "2", "3", "5", "8", "12", "20")),
    SERVICE_WEIGHT("service_label_weight", "How much each of the classifier service's labels counts; 0 leaves them out.", listOf("0", "0.05", "0.15", "0.35", "0.7", "1", "1.5")),
    CORPUS_WEIGHT("shipped_example_weight", "How much each of the 1,493 hand-written shipped examples counts; 0 leaves them out.", listOf("0", "0.1", "0.3", "0.6", "1", "2")),
    CONVERSATION_WEIGHT(
        "conversation_text_weight",
        "How much each of the person's other texts counts in a conversation whose labels from them all agree, taken as that label (never one in a conversation being scored); 0 leaves them out.",
        listOf("0", "0.1", "0.25", "0.5", "1"),
    ),
    BALANCE("balance_categories", "Count each category equally, however many texts it has.", listOf("yes", "no")),
    ;

    /** Whether a try of this kind uses it. */
    fun usedBy(neural: Boolean) = if (neural) !linearOnly else !neuralOnly

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
            bags = if (neural) 1 else v(Knob.BAGS).toInt(),
            inputDropout = v(Knob.WORDS_OUT).toDouble(),
            pieces = v(Knob.PIECES) == "yes",
            context = v(Knob.CONTEXT) == "yes",
            crosses = v(Knob.CROSSES) == "yes",
            shapes = v(Knob.SHAPES) == "yes",
            clusters = v(Knob.CLUSTERS) == "yes",
            includeCorpus = corpus > 0,
            corpusWeight = if (corpus > 0) corpus else 1.0,
            userWeight = v(Knob.USER_WEIGHT).toDouble(),
            conversationWeight = v(Knob.CONVERSATION_WEIGHT).toDouble(),
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
            Knob.BAGS to nearest(Knob.BAGS, recipe.bags.toDouble()),
            Knob.BUCKETS to nearest(Knob.BUCKETS, recipe.buckets.toDouble()),
            Knob.EPOCHS to nearest(Knob.EPOCHS, recipe.epochs.toDouble()),
            Knob.STEP to nearest(Knob.STEP, recipe.learningRate),
            Knob.L2 to nearest(Knob.L2, recipe.l2),
            Knob.WORDS_OUT to nearest(Knob.WORDS_OUT, recipe.inputDropout),
            Knob.PIECES to if (recipe.pieces) "yes" else "no",
            Knob.CLUSTERS to if (recipe.clusters) "yes" else "no",
            Knob.CONTEXT to if (recipe.context) "yes" else "no",
            Knob.CROSSES to if (recipe.crosses) "yes" else "no",
            Knob.SHAPES to if (recipe.shapes) "yes" else "no",
            Knob.USER_WEIGHT to nearest(Knob.USER_WEIGHT, recipe.userWeight),
            Knob.SERVICE_WEIGHT to nearest(Knob.SERVICE_WEIGHT, recipe.serviceWeight),
            Knob.CORPUS_WEIGHT to if (recipe.includeCorpus) nearest(Knob.CORPUS_WEIGHT, recipe.corpusWeight) else "0",
            Knob.CONVERSATION_WEIGHT to nearest(Knob.CONVERSATION_WEIGHT, recipe.conversationWeight),
            Knob.BALANCE to if (recipe.balance) "yes" else "no",
        )
    }

    /** One key per distinct try: a linear one's network settings (or a network's bags) don't make it another. */
    fun keyOf(settings: Map<Knob, String>): String {
        val neural = settings[Knob.KIND] == "neural"
        return Knob.entries.joinToString(";") { k -> if (!k.usedBy(neural)) "${k.key}=-" else "${k.key}=${settings[k]}" }
    }

    /** Where a sweep starts: different ways to keep a model from memorizing, one of each kind. */
    val STARTS: List<Map<Knob, String>> = listOf(
        settings("neural", layers = "64", buckets = "32768", epochs = "8", step = "0.05", l2 = "1e-6", wordsOut = "0.3"),
        settings("neural", layers = "64-32", buckets = "32768", epochs = "12", step = "0.05", l2 = "1e-6", dropout = "0.25", wordsOut = "0.15", pieces = "yes"),
        settings("linear", buckets = "65536", epochs = "60", step = "0.2", l2 = "1e-5"),
        settings("linear", buckets = "32768", epochs = "30", step = "0.2", l2 = "1e-5", wordsOut = "0.15", pieces = "yes"),
        settings("neural", layers = "128", buckets = "16384", epochs = "5", step = "0.05", l2 = "1e-5", wordsOut = "0.3"),
        settings("neural", layers = "64", wide = "no", buckets = "16384", epochs = "20", step = "0.05", l2 = "1e-6", dropout = "0.4", wordsOut = "0.3"),
        // When texts came and what came before them: a signal words don't carry.
        settings("linear", buckets = "65536", epochs = "60", step = "0.2", l2 = "1e-5", context = "yes"),
        settings("neural", layers = "64", buckets = "32768", epochs = "8", step = "0.05", l2 = "1e-6", wordsOut = "0.3", context = "yes"),
        // The rest of each conversation the person has labeled one way: many more of their own texts to learn from.
        settings("linear", buckets = "65536", epochs = "60", step = "0.2", l2 = "1e-5", conversations = "0.25"),
        settings("linear", buckets = "131072", epochs = "60", step = "0.2", l2 = "1e-5", wordsOut = "0.15", conversations = "0.25", context = "yes"),
        // Words by the kind of sender they came from: what a linear model can't combine on its own.
        settings("linear", buckets = "131072", epochs = "60", step = "0.2", l2 = "1e-5", crosses = "yes"),
        // Words that mean alike (GloVe's groups): what one word taught carries to words like it.
        settings("linear", buckets = "65536", epochs = "30", step = "0.2", l2 = "1e-5", clusters = "yes"),
        settings("neural", layers = "64", buckets = "32768", epochs = "12", step = "0.05", l2 = "1e-6", wordsOut = "0.15", clusters = "yes"),
        // What the words lose: percents off, times and dates, codes and order numbers.
        settings("linear", buckets = "262144", epochs = "100", step = "0.2", l2 = "1e-3", wordsOut = "0.3", conversations = "0.25", shapes = "yes"),
    )

    private fun settings(
        kind: String, layers: String = "64", wide: String = "yes", dropout: String = "0", buckets: String, epochs: String, step: String, l2: String,
        wordsOut: String = "0", pieces: String = "no", user: String = "3", service: String = "0.35", corpus: String = "1", balance: String = "yes",
        bags: String = "1", context: String = "no", conversations: String = "0", crosses: String = "no", shapes: String = "no",
        clusters: String = "no",
    ) = mapOf(
        Knob.KIND to kind, Knob.LAYERS to layers, Knob.WIDE to wide, Knob.DROPOUT to dropout, Knob.BAGS to bags, Knob.BUCKETS to buckets, Knob.EPOCHS to epochs,
        Knob.STEP to step, Knob.L2 to l2, Knob.WORDS_OUT to wordsOut, Knob.PIECES to pieces, Knob.CONTEXT to context, Knob.CROSSES to crosses, Knob.SHAPES to shapes, Knob.CLUSTERS to clusters, Knob.USER_WEIGHT to user,
        Knob.SERVICE_WEIGHT to service, Knob.CORPUS_WEIGHT to corpus, Knob.CONVERSATION_WEIGHT to conversations, Knob.BALANCE to balance,
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
    /**
     * Stopped after its first parts, already well behind the best there (see RecipeSweep.PRUNE_MARGIN):
     * scored on those texts only, and never above the best, so it's never kept or blended.
     */
    val dropped: Boolean = false,
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
    /** How the round's tries were made from the odds: jitters from the best, steered, exploring. */
    val made: String? = null,
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
    /** Other texts in the person's conversations whose labels all agree (see [Knob.CONVERSATION_WEIGHT]). */
    val conversationTexts: Int = 0,
    /** How many tries have had each value of each knob. */
    val counts: Map<Knob, Map<String, Int>> = emptyMap(),
    /** Each round's steering so far: what it leaned toward. */
    val earlierRounds: List<SweepRound> = emptyList(),
    /** Rounds since the best last gained half a point or more (or since the start). */
    val stalled: Int = 0,
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
     * [n] new tries in three parts, so no round only refines one point: jitters (the best with a
     * knob or two a step or two along its values), steered (from the odds: half the best moved
     * where they lean, half drawn outright), and explorers (each knob's least-tried values; once
     * the best has [stalled] two rounds, as often the other kind of model). More explorers once it
     * has stalled at all. Never one already [tried], never a recipe that can't train.
     */
    fun round(
        odds: Map<Knob, Map<String, Double>>,
        best: Map<Knob, String>?,
        tried: Set<String>,
        n: Int,
        random: Random,
        counts: Map<Knob, Map<String, Int>> = emptyMap(),
        stalled: Int = 0,
    ): List<Map<Knob, String>> {
        val (jitters, explorers) = split(n, best != null, stalled)
        val parts = List(jitters) { Part.JITTER } + List(n - jitters - explorers) { Part.STEERED } + List(explorers) { Part.EXPLORE }
        val out = mutableListOf<Map<Knob, String>>()
        val taken = tried.toHashSet()
        for (part in parts.take(n)) {
            for (attempt in 0 until 200) {
                val s = when {
                    best == null -> draw(odds, random)
                    part == Part.JITTER -> jitter(complete(best, odds, random), random)
                    part == Part.EXPLORE -> explore(counts, best, stalled, random)
                    random.nextBoolean() -> nudge(complete(best, odds, random), odds, random)
                    else -> draw(odds, random)
                }
                val key = SweepSpace.keyOf(s)
                if (key in taken || SweepSpace.recipeOf(s).problem() != null) continue
                taken += key
                out += s
                break
            }
        }
        return out
    }

    private enum class Part { JITTER, STEERED, EXPLORE }

    /** Of [n] tries, how many jitter from the best and how many explore (the rest are steered). Two or fewer are all steered. */
    fun split(n: Int, fromBest: Boolean, stalled: Int): Pair<Int, Int> {
        if (!fromBest || n <= 2) return 0 to 0
        val explorers = (if (stalled >= 1) maxOf(2, n * 3 / 8) else maxOf(1, n / 4)).coerceAtMost(n - 1)
        val jitters = maxOf(1, n * 3 / 8).coerceAtMost(n - explorers)
        return jitters to explorers
    }

    /** [best] with one or two of its knobs (never its kind) a step or two along their values, either way. */
    private fun jitter(best: Map<Knob, String>, random: Random): Map<Knob, String> {
        val neural = best[Knob.KIND] == "neural"
        val movable = Knob.entries.filter { it != Knob.KIND && it.usedBy(neural) }
        val out = best.toMutableMap()
        repeat(1 + random.nextInt(2)) {
            val knob = movable[random.nextInt(movable.size)]
            val at = knob.values.indexOf(out[knob]).coerceAtLeast(0)
            val step = (1 + random.nextInt(2)) * (if (random.nextBoolean()) 1 else -1)
            val to = (at + step).let { if (it !in knob.values.indices) at - step else it }.coerceIn(knob.values.indices)
            out[knob] = knob.values[to]
        }
        return out
    }

    /**
     * Each knob at its least-tried values, most likely, the slowest a little less (many passes, the
     * widest networks: a phone's minutes); stalled two rounds, the other kind of model half the time.
     */
    private fun explore(counts: Map<Knob, Map<String, Int>>, best: Map<Knob, String>, stalled: Int, random: Random): Map<Knob, String> {
        fun slowness(k: Knob, v: String): Double = when (k) {
            Knob.EPOCHS -> maxOf(1.0, v.toDouble() / 30)
            Knob.LAYERS -> maxOf(1.0, v.split('-').sumOf(String::toInt) / 96.0)
            Knob.BAGS -> v.toDouble()
            else -> 1.0
        }
        val out = Knob.entries.associateWith { k ->
            weighted(k.values.associateWith { v -> 1.0 / Math.pow(1.0 + (counts[k]?.get(v) ?: 0), 2.0) / slowness(k, v) }, random)
        }.toMutableMap()
        if (stalled >= 2 && random.nextBoolean()) out[Knob.KIND] = if (best[Knob.KIND] == "neural") "linear" else "neural"
        return out
    }

    private fun draw(odds: Map<Knob, Map<String, Double>>, random: Random): Map<Knob, String> = Knob.entries.associateWith { pick(it, odds[it], random) }

    /** [settings] with any knob it lacks (a linear try's network settings) drawn from the odds. */
    private fun complete(settings: Map<Knob, String>, odds: Map<Knob, Map<String, Double>>, random: Random): Map<Knob, String> =
        Knob.entries.associateWith { k -> settings[k] ?: pick(k, odds[k], random) }

    /** [best] with one or two knobs moved: those the odds lean away from its value most likely. */
    private fun nudge(best: Map<Knob, String>, odds: Map<Knob, Map<String, Double>>, random: Random): Map<Knob, String> {
        val neural = best[Knob.KIND] == "neural"
        val movable = Knob.entries.filter { it.usedBy(neural) }
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
 * A steering's odds made to explore, whoever gave them: flattened (the square root of each), no
 * value above [cap] for its knob, values tried less counting more (one never tried, twice), and a
 * value the steering leaned toward last round as well, that's the best try's already, at half.
 * So a steering that leans the same way round after round still has its rounds look around.
 */
object SweepExploration {
    fun cap(values: Int) = maxOf(0.5, 1.5 / values)

    fun shape(
        odds: Map<Knob, Map<String, Double>>,
        counts: Map<Knob, Map<String, Int>>,
        previousTop: Map<Knob, String>?,
        best: Map<Knob, String>?,
    ): Map<Knob, Map<String, Double>> = Knob.entries.associateWith { k ->
        val raw = odds[k]?.filterKeys { it in k.values }?.takeIf { it.isNotEmpty() } ?: k.values.associateWith { 1.0 / k.values.size }
        val repeat = previousTop?.get(k)?.takeIf { it == best?.get(k) }
        val w = k.values.associateWith { v ->
            kotlin.math.sqrt((raw[v] ?: 0.0).coerceAtLeast(1e-6)) *
                (1.0 + 1.0 / (1 + (counts[k]?.get(v) ?: 0))) *
                (if (v == repeat) 0.5 else 1.0)
        }
        capped(w.mapValues { it.value / w.values.sum() }, cap(k.values.size))
    }

    /** [p] with none above [cap], the excess shared among the rest by their odds. */
    private fun capped(p: Map<String, Double>, cap: Double): Map<String, Double> {
        var q = p
        repeat(p.size) {
            val over = q.filterValues { it > cap + 1e-12 }
            if (over.isEmpty()) return q
            val excess = over.values.sumOf { it - cap }
            val rest = q.filterKeys { it !in over }
            val restSum = rest.values.sum()
            q = q.mapValues { (v, x) -> if (v in over) cap else if (restSum > 0) x + excess * x / restSum else x + excess / rest.size }
        }
        return q
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
    /** How many of a recipe's folds may train at once (see RecipeTrainer.crossValidateRows). */
    private val parallelism: (Recipe) -> Int = { 1 },
) {
    val classes = base.classes
    private val folds = RecipeTrainer.foldsOf(scored)
    private val memories: Map<Int, SenderMemory> = folds?.let { f ->
        (0..f.max()).associateWith { fold -> SenderMemory.of(scored.indices.filter { f[it] != fold }.mapNotNull { i -> scored[i].sender?.let { it to scored[i].label } }, classes) }
    }.orEmpty()
    /**
     * What each scored text's most alike labeled texts vote for, from the other folds' labels only
     * (see TemplateMemory): the same whatever the recipe, so worked out once, when first needed.
     */
    private val alikeVotes: Array<DoubleArray?> by lazy {
        val f = folds ?: return@lazy arrayOfNulls<DoubleArray>(scored.size)
        val buckets = scored.map { it.baseIndices ?: it.features?.let(base::indices) ?: IntArray(0) }
        val byFold = (0..f.max()).associateWith { fold -> TemplateMemory.of(scored.indices.filter { f[it] != fold }.map { buckets[it] to scored[it].label }, classes, 1.0) }
        Array(scored.size) { i -> byFold.getValue(f[i]).vote(buckets[i]) }
    }

    class Scoring(val recipe: Recipe, val logits: List<RecipeTrainer.Row>, val accuracy: Double, val macroF1: Double, val wordsAccuracy: Double)

    fun crossValidate(recipe: Recipe, stopped: () -> Boolean = { false }, keepGoing: (List<RecipeTrainer.Row>) -> Boolean = { true }) =
        RecipeTrainer.crossValidateRows(recipe, base, scored, others, stopped = stopped, parallelism = parallelism(recipe), keepGoing = keepGoing)

    /** The share of [rows] its words alone get right. */
    fun wordsAccuracy(rows: List<RecipeTrainer.Row>): Double =
        if (rows.isEmpty()) 0.0 else rows.count { r -> r.logits.indices.maxBy { r.logits[it] } == scored[r.index].label }.toDouble() / rows.size

    /** Rows from logits alone, for a model whose earlier texts weren't read. */
    fun rows(cv: List<Pair<Int, DoubleArray>>) = cv.map { (i, l) -> RecipeTrainer.Row(i, l) }

    /**
     * [cv] scored as the Lab would score [recipe]: each text's odds, leaned by what came before it
     * in its conversation (see ConversationReading), then by the user's labels of its sender, at the
     * recipe's own strengths; or with [tune], at whichever do best, and with leanings toward each
     * category (the recipe returned says which). None of it needs training.
     */
    fun score(recipe: Recipe, cv: List<RecipeTrainer.Row>, tune: Boolean): Scoring? {
        if (cv.isEmpty()) return null
        // [cv] is the recipe's own, without leanings: they're added here, as a model's are on top of
        // it, to the earlier texts' answers as much as its own (the same model reads them).
        fun plus(l: DoubleArray, bias: List<Double>) = if (bias.isEmpty()) l else DoubleArray(l.size) { l[it] + bias.getOrElse(it) { 0.0 } }
        fun leaned(bias: List<Double>) = if (bias.isEmpty()) cv else cv.map { r -> RecipeTrainer.Row(r.index, plus(r.logits, bias), r.earlier.map { plus(it, bias) }) }
        fun temperatureOf(c: List<RecipeTrainer.Row>) =
            if (recipe.kind == RecipeKind.PERSONAL) base.temperature.toDouble() else RecipeTrainer.calibrate(c.map { it.logits to scored[it.index].label }).toDouble()
        fun answers(c: List<RecipeTrainer.Row>, temperature: Double, reading: Double, strength: Double, alike: Double) = c.map { r ->
            val item = scored[r.index]
            val read = ConversationReading.lean(LocalModel.softmax(r.logits, temperature), r.earlier.map { LocalModel.softmax(it, temperature) }, reading)
            // The texts it reads like, from the other folds' labels only, then who sent it.
            val p = if (alike > 0) TemplateMemory.lean(read, alikeVotes[r.index], alike) else read
            val memory = folds?.let { memories[it[r.index]] }?.withStrength(strength)
            Scored(item.label, item.sender?.let { sender -> memory?.follow(p, sender, item.conversing)?.distribution } ?: p)
        }
        fun accuracy(rows: List<Scored>) = rows.count { it.predicted == it.label }.toDouble() / rows.size
        var bias = recipe.leaningsIn(classes)
        var c = leaned(bias)
        var temperature = temperatureOf(c)
        // Ties go to the recipe's own, then to the nearest to it.
        val reading = if (!tune) recipe.conversationReading else (READINGS + recipe.conversationReading).distinct()
            .sortedBy { abs(it - recipe.conversationReading) }.maxBy { accuracy(answers(c, temperature, it, recipe.senderMemory, recipe.templateMemory)) }
        val alike = if (!tune) recipe.templateMemory else (TemplateMemory.STRENGTHS + recipe.templateMemory).distinct()
            .sortedBy { abs(it - recipe.templateMemory) }.maxBy { accuracy(answers(c, temperature, reading, recipe.senderMemory, it)) }
        val strength = if (!tune) recipe.senderMemory else (strengths + recipe.senderMemory).distinct()
            .sortedBy { abs(it - recipe.senderMemory) }.maxBy { accuracy(answers(c, temperature, reading, it, alike)) }
        // Leanings toward each category, a step at a time, wherever one raises the share it gets
        // right (ties to the smaller lean), as free as the strengths.
        if (tune && recipe.kind != RecipeKind.PERSONAL) {
            val b = DoubleArray(classes.size) { bias.getOrElse(it) { 0.0 } }
            var best = accuracy(answers(leaned(b.toList()), temperature, reading, strength, alike))
            repeat(RecipeSweep.LEAN_PASSES) {
                for (cl in b.indices) for (v in RecipeSweep.LEANS) {
                    val old = b[cl]
                    if (v == old) continue
                    b[cl] = v
                    val a = accuracy(answers(leaned(b.toList()), temperature, reading, strength, alike))
                    if (a > best + 1e-9 || (a >= best - 1e-9 && abs(v) < abs(old))) best = maxOf(best, a) else b[cl] = old
                }
            }
            bias = if (b.all { it == 0.0 }) emptyList() else b.toList()
            c = leaned(bias)
            temperature = temperatureOf(c)
        }
        val m = MetricsCalculator.compute("sweep", "Cross-validated on your labels", classes, answers(c, temperature, reading, strength, alike), unwanted, filterAt)
        val words = c.count { r -> r.logits.indices.maxBy { r.logits[it] } == scored[r.index].label }.toDouble() / c.size
        return Scoring(recipe.copy(senderMemory = strength, conversationReading = reading, templateMemory = alike, classBias = bias), cv, m.accuracy, m.macroF1, words)
    }

    /**
     * The best blend of [library]'s tries (each its cross-validated rows): a greedy pick, one
     * at a time and with repeats (a repeat counts twice), of whichever raises the blend's accuracy
     * most, up to [Recipe.MAX_MEMBERS] different ones. Its scoring is exactly what training the
     * blend and scoring it the same way would give, with no training at all: every training is
     * the same from the same texts. Null unless at least two tries blend better than the best alone.
     */
    fun blend(library: List<Pair<Recipe, List<RecipeTrainer.Row>>>, tune: Boolean = true): Scoring? {
        val usable = library.filter { (r, cv) -> (r.kind == RecipeKind.LINEAR || r.kind == RecipeKind.NEURAL) && cv.isNotEmpty() }
        if (usable.size < 2) return null
        val byIndex = usable.map { (_, cv) -> cv.associateBy { it.index } }
        val common = byIndex.map { it.keys }.reduce { a, b -> a intersect b }.sorted()
        if (common.isEmpty()) return null
        val probs = byIndex.map { m -> common.map { LocalModel.softmax(m.getValue(it).logits) } }
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
            members = members.map { usable[it].first.copy(senderMemory = 0.0, conversationReading = 0.0, templateMemory = 0.0) },
            memberWeights = members.map { counts[it] / total },
        )
        val cv = common.map { i ->
            val rows = members.map { m -> byIndex[m].getValue(i) }
            // The blend reads each earlier text as it reads this one: its members' answers averaged.
            val earlier = rows.minOf { it.earlier.size }.let { n -> (0 until n).map { j -> BlendPredictor.blendLogits(rows.map { it.earlier[j] }, recipe.memberWeights) } }
            RecipeTrainer.Row(i, BlendPredictor.blendLogits(rows.map { it.logits }, recipe.memberWeights), earlier)
        }
        return score(recipe, cv, tune)
    }

    companion object {
        /** How much what came before in a conversation is tried at, free (see ConversationReading). */
        val READINGS = listOf(0.0, 0.5, 1.0, 2.0, 3.0)
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
    /**
     * [endEarly]: end once the steering twice running gives another round under [END_BELOW] odds
     * of gaining, after at least half the rounds; never, when false.
     */
    @Serializable
    data class Plan(val rounds: Int = 8, val perRound: Int = 8, val steeringCalls: Int = 8, val seed: Int = 7, val endEarly: Boolean = false)

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
    suspend fun run(
        baselines: List<Pair<String, Recipe>>,
        onEvent: (Event) -> Unit = {},
        stopped: () -> Boolean = { false },
        /** More to start from (named by where they came from: the last sweep's best, say), after the starts. */
        starts: List<Pair<String, Recipe>> = emptyList(),
    ): Result {
        val trials = mutableListOf<SweepTrial>()
        val rounds = mutableListOf<SweepRound>()
        val library = mutableListOf<Pair<Recipe, List<RecipeTrainer.Row>>>()
        /** Each whole try's rows, by the try (its recipe is as tuned, so not a key into [library]). */
        val rowsOf = java.util.IdentityHashMap<SweepTrial, List<RecipeTrainer.Row>>()
        val tried = HashSet<String>()
        val random = Random(plan.seed)
        var calls = 0
        var failed = 0
        var cost = 0.0
        var stoppedEarly = false

        fun keep(round: Int, scoring: SweepScorer.Scoring, millis: Long, from: String, dropped: Boolean = false, accuracy: Double = scoring.accuracy): SweepTrial {
            // A linear try's network settings (a network's bags) mean nothing: not shown, not counted as evidence.
            val neural = scoring.recipe.kind == RecipeKind.NEURAL
            val settings = SweepSpace.settingsOf(scoring.recipe)?.filterKeys { it.usedBy(neural) }
            val trial = SweepTrial(round, scoring.recipe, settings?.mapKeys { it.key.key }, accuracy, scoring.macroF1, scoring.wordsAccuracy, scoring.logits.size, millis, from, dropped)
            if (!dropped) rowsOf[trial] = scoring.logits
            trials += trial
            onEvent(Event.Tried(trial))
            return trial
        }

        fun tryOne(round: Int, n: Int, of: Int, recipe: Recipe, from: String, tune: Boolean = true) {
            if (stopped()) return
            onEvent(Event.Trying(round, n, of, recipe))
            val started = System.nanoTime()
            // Leanings sit on top of a model's own answers (see SweepScorer.score): trained without them.
            val plain = recipe.copy(classBias = emptyList())
            // The best whole try so far: a try well behind it on the first parts stops there (never one of the user's own).
            val best = trials.filter { !it.dropped }.maxByOrNull { it.accuracy }
            val bestRows = best?.let { rowsOf[it] }?.associateBy { it.index }
            var cut = false
            val keepGoing: (List<RecipeTrainer.Row>) -> Boolean = { rows ->
                val theirs = bestRows?.let { b -> rows.mapNotNull { b[it.index] } }
                (!tune || theirs == null || theirs.size < rows.size || rows.size < PRUNE_AT_LEAST ||
                    scorer.wordsAccuracy(rows) >= scorer.wordsAccuracy(theirs) - PRUNE_MARGIN).also { cut = !it }
            }
            val cv = try {
                scorer.crossValidate(plain, stopped, keepGoing)
            } catch (e: java.util.concurrent.CancellationException) {
                return
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            val scoring = scorer.score(recipe, cv, tune) ?: return
            if (cut && best != null) {
                // Scored on part of the texts only: told to the steering as behind the best, never kept or blended.
                keep(round, scoring, millis, from, dropped = true, accuracy = minOf(scoring.accuracy, best.accuracy - PRUNE_MARGIN))
                return
            }
            library += plain to cv
            keep(round, scoring, millis, from)
            // The user's own recipe with their labels of each sender counting differently: free to score.
            if (!tune) scorer.score(recipe, cv, tune = true)?.takeIf { it.accuracy > scoring.accuracy }
                ?.let { keep(round, it, 0, "$from, sender labels and leanings retuned") }
        }

        // Round 0: what the user has, as it is, then the starts (none the same as one of theirs).
        baselines.forEach { (_, recipe) -> SweepSpace.settingsOf(recipe)?.takeIf { SweepSpace.recipeOf(it) == recipe }?.let { tried += SweepSpace.keyOf(it) } }
        val fixed = SweepSpace.STARTS.filter { tried.add(SweepSpace.keyOf(it)) }
        val more = starts.filter { (_, r) -> SweepSpace.settingsOf(r)?.let { s -> SweepSpace.recipeOf(s) != r || tried.add(SweepSpace.keyOf(s)) } ?: true }
        val firstCount = fixed.size + baselines.size + more.size
        var n = 0
        baselines.forEach { (name, recipe) -> tryOne(0, ++n, firstCount, recipe, name, tune = false) }
        fixed.forEach { tryOne(0, ++n, firstCount, SweepSpace.recipeOf(it), "start") }
        more.forEach { (from, recipe) -> tryOne(0, ++n, firstCount, recipe, from) }
        var lowRounds = 0
        var previousTop: Map<Knob, String>? = null
        var stalled = 0
        var bestBefore = trials.maxOfOrNull { it.accuracy } ?: 0.0
        fun counts(): Map<Knob, Map<String, Int>> = Knob.entries.associateWith { k ->
            trials.mapNotNull { it.settings?.get(k.key) }.groupingBy { it }.eachCount()
        }

        for (round in 1..plan.rounds) {
            // Nothing could be scored (too few conversations): nothing to steer by, nothing to pay for.
            if (stopped() || trials.isEmpty()) break
            val state = SweepState(
                labeled = scorer.scored.size,
                conversations = scorer.scored.map { it.group }.distinct().size,
                categories = scorer.scored.groupingBy { scorer.classes[it.label] }.eachCount(),
                shippedExamples = scorer.others.count { it.source == TrainingItem.Source.CORPUS },
                serviceLabels = scorer.others.count { it.source == TrainingItem.Source.SERVICE },
                conversationTexts = scorer.others.count { it.source == TrainingItem.Source.CONVERSATION },
                counts = counts(), earlierRounds = rounds.toList(), stalled = stalled,
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
            val best = trials.filter { it.settings != null }.maxByOrNull { it.accuracy }?.settings?.mapKeys { Knob.byKey(it.key)!! }
            // Whatever the steering said, the round explores (see SweepExploration, SweepSampler.round).
            val shaped = SweepExploration.shape(steering.odds, counts(), previousTop, best)
            val next = SweepSampler.round(shaped, best, tried, plan.perRound, random, counts(), stalled)
            rounds += SweepRound(
                round, steering.by,
                leaning = steering.odds.map { (k, o) -> o.maxBy { it.value }.let { (v, p) -> Lean(k.key, v, p) } },
                more = steering.more, costUsd = steering.costUsd, note = steering.note,
                made = madeOf(next.size, best != null, stalled, plan.perRound),
            ).also { onEvent(Event.Steered(it)) }
            previousTop = steering.odds.mapValues { (_, o) -> o.maxBy { it.value }.key }
            // Told twice running there's almost nothing left to gain, past half its rounds: done,
            // without spending more. Anything less, it keeps looking.
            lowRounds = if (steering.more < END_BELOW) lowRounds + 1 else 0
            if (plan.endEarly && lowRounds >= 2 && round * 2 >= plan.rounds) {
                stoppedEarly = true
                break
            }
            next.forEachIndexed { i, s ->
                tried += SweepSpace.keyOf(s)
                tryOne(round, i + 1, next.size, SweepSpace.recipeOf(s), steering.by)
            }
            val bestNow = trials.maxOfOrNull { it.accuracy } ?: 0.0
            stalled = if (bestNow >= bestBefore + STALL_GAIN) 0 else stalled + 1
            bestBefore = maxOf(bestBefore, bestNow)
        }

        // A blend is picked and weighed on the very scores it's judged by: kept only when clearly ahead.
        val bestAlone = trials.maxOfOrNull { it.accuracy } ?: 0.0
        val blend = if (stopped()) null else scorer.blend(library)?.takeIf { it.accuracy >= bestAlone + BLEND_MARGIN }?.let { b ->
            val millis = b.recipe.members.sumOf { m -> trials.firstOrNull { it.recipe.copy(senderMemory = 0.0, conversationReading = 0.0, templateMemory = 0.0, classBias = emptyList()) == m }?.millis ?: 0 }
            keep(plan.rounds + 1, b, millis, "blend")
        }
        return Result(trials.sortedByDescending { it.accuracy }, rounds, blend, calls, failed, cost, stoppedEarly)
    }

    /** What a round's tries were, in words (mirrors SweepSampler.round's split). */
    private fun madeOf(made: Int, fromBest: Boolean, stalled: Int, n: Int): String {
        val (jitters, explorers) = SweepSampler.split(n, fromBest, stalled)
        if (jitters == 0 && explorers == 0) return "$made drawn from the odds"
        val steered = n - jitters - explorers
        return "$jitters a step or two from the best, $steered steered, $explorers exploring the least-tried settings" +
            (if (stalled >= 2) ", some the other kind of model" else "") + (if (stalled >= 1) " (the best had stalled)" else "")
    }

    companion object {
        /** What counts as the best gaining, for exploring more when it hasn't: half a point. */
        const val STALL_GAIN = 0.005

        /** Leanings tried for each category, and how many times round them all. */
        val LEANS = (-8..8).map { it * 0.25 }
        const val LEAN_PASSES = 2

        /** Half a point: what a blend must beat the best try alone by. */
        const val BLEND_MARGIN = 0.005

        /**
         * How far behind the best whole try a try's words alone may be on the first parts scored
         * (the same texts) and still go on: three points, more than tuning who-sent-it, reading and
         * leanings has made up between tries.
         */
        const val PRUNE_MARGIN = 0.03

        /** Texts the first parts must hold before a try can stop there: fewer, and three points is a text or two. */
        const val PRUNE_AT_LEAST = 200

        /** The odds of another round gaining under which the steering's "no" counts toward ending (see [Plan.endEarly]). */
        const val END_BELOW = 0.10
    }
}
