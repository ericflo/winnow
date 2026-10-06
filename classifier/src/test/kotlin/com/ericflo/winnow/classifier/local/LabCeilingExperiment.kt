package com.ericflo.winnow.classifier.local

import java.util.Locale
import kotlin.random.Random

/**
 * What caps a Lab model's accuracy on someone's own labels, measured with the Lab's own training
 * and scoring (RecipeTrainer, cross-validated by conversation): `./gradlew :classifier:labCeilingExperiment`.
 *
 * The bundled corpus stands in for a phone: 400 texts are "your labels" (in conversations of
 * three, by category), 500 more are a classifier service's labels, and the rest are the shipped
 * examples the base model learns from, none of them overlapping. Your labels can disagree with the
 * corpus at random, or systematically (you call some reminders transactional, say), and the
 * service can disagree with you. Each design is scored the Lab's way, and fitted on everything
 * and scored on the same labels too, to see whether it can't fit (capacity) or can't generalize.
 * Dev aid; nothing here ships.
 */
fun main() {
    val classes = LocalModel.bundled.classes
    val k = classes.size
    val all = ShippedCorpus.texts.shuffled(Random(7))
    val user = all.take(400)
    val service = all.drop(400).take(500)
    val corpus = all.drop(900)
    fun idx(t: LabeledText) = classes.indexOf(t.category.key)
    val feats = HashMap<LabeledText, List<String>>()
    fun f(t: LabeledText) = feats.getOrPut(t) { Featurizer.features(Featurizer.Input(t.sender, t.body)) }

    // A base model that never saw your labels or the service's texts (the shipped one saw them all).
    val base = LocalModelTrainer(classes).train(corpus.map { Example(f(it), idx(it)) })
    fun c(key: String) = classes.indexOf(key)

    /** Your labels under a way of disagreeing with the corpus. */
    fun userLabels(noise: String): List<Int> {
        val r = Random(11)
        return user.map { t ->
            val y = idx(t)
            when (noise) {
                "random10" -> if (r.nextDouble() < 0.10) (y + 1 + r.nextInt(k - 1)) % k else y
                "random20" -> if (r.nextDouble() < 0.20) (y + 1 + r.nextInt(k - 1)) % k else y
                // Your own sense of the categories: some reminders are transactional to you, some marketing spam, some personal transactional.
                "systematic" -> when {
                    y == c("reminder") && r.nextDouble() < 0.35 -> c("transactional")
                    y == c("marketing") && r.nextDouble() < 0.30 -> c("spam")
                    y == c("personal") && r.nextDouble() < 0.10 -> c("transactional")
                    else -> y
                }
                else -> y
            }
        }
    }

    /** The service's labels: right by the corpus, or with its own systematic lean. */
    fun serviceLabels(lean: Boolean): List<Int> {
        val r = Random(13)
        return service.map { t ->
            val y = idx(t)
            if (!lean) y else when {
                y == c("transactional") && r.nextDouble() < 0.30 -> c("reminder")
                y == c("spam") && r.nextDouble() < 0.20 -> c("marketing")
                else -> y
            }
        }
    }

    data class Result(val acc: Double, val macroF1: Double, val trainAcc: Double, val recall: DoubleArray)

    fun run(recipe: Recipe, noise: String, serviceLean: Boolean): Result {
        val yu = userLabels(noise)
        val ys = serviceLabels(serviceLean)
        // Conversations of three, each of one category: texts from one sender are much alike.
        val byCat = user.indices.groupBy { idx(user[it]) }
        val group = IntArray(user.size).also { g -> byCat.values.forEach { ids -> ids.forEachIndexed { n, i -> g[i] = idx(user[i]) * 1000 + n / 3 } } }
        val scored = user.indices.map { TrainingItem(f(user[it]), null, yu[it], recipe.userWeight, group[it].toLong()) }
        val others = buildList {
            if (recipe.serviceWeight > 0) service.indices.forEach { add(TrainingItem(f(service[it]), null, ys[it], recipe.serviceWeight)) }
            if (recipe.kind != RecipeKind.PERSONAL && recipe.includeCorpus) corpus.forEach { add(TrainingItem(f(it), null, idx(it), recipe.corpusWeight)) }
        }
        val cv = RecipeTrainer.crossValidate(recipe, base, scored, others)
        val pred = IntArray(user.size) { -1 }
        cv.forEach { (i, s) -> pred[i] = s.indices.maxBy { s[it] } }
        val acc = user.indices.count { pred[it] == yu[it] }.toDouble() / user.size
        val recall = DoubleArray(k) { cl -> user.indices.filter { yu[it] == cl }.let { ids -> if (ids.isEmpty()) Double.NaN else ids.count { pred[it] == cl }.toDouble() / ids.size } }
        val f1s = (0 until k).map { cl ->
            val tp = user.indices.count { pred[it] == cl && yu[it] == cl }.toDouble()
            val fp = user.indices.count { pred[it] == cl && yu[it] != cl }
            val fn = user.indices.count { pred[it] != cl && yu[it] == cl }
            if (tp == 0.0) 0.0 else 2 * tp / (2 * tp + fp + fn)
        }
        // Fitted on everything, scored on the same labels: what it can fit at all.
        val full = RecipeTrainer.train(recipe, base, scored + others)
        val trainAcc = scored.withIndex().count { (i, it) -> RecipeTrainer.logits(full, it)?.let { s -> s.indices.maxBy { s[it] } } == yu[i] }.toDouble() / user.size
        return Result(acc, f1s.average(), trainAcc, recall)
    }

    val designs = listOf(
        "personal (default)" to Recipe(),
        "personal, 120 passes" to Recipe(epochs = 120),
        "linear, retrained" to Recipe.PRESETS[1].second,
        "linear, no balance" to Recipe.PRESETS[1].second.copy(balance = false),
        "neural 64+wide" to Recipe.PRESETS.first { it.first == "Neural, wider" }.second,
        "neural 64→32+wide" to Recipe.PRESETS.first { it.first == "Neural, deeper" }.second,
        "neural 256→128+wide" to Recipe.PRESETS.first { it.first == "Neural, deeper" }.second.copy(layers = listOf(256, 128), buckets = 1 shl 14),
        "neural 64→32, 60 passes" to Recipe.PRESETS.first { it.first == "Neural, deeper" }.second.copy(epochs = 60),
        "neural 64→32, no wide" to Recipe.PRESETS.first { it.first == "Neural, deeper" }.second.copy(wide = false),
    )
    val header = "%-26s %-11s %-7s %6s %6s %6s   %s"
    println(String.format(Locale.US, header, "design", "your labels", "service", "acc", "macF1", "train", classes.joinToString(" ") { it.take(5).padStart(5) }))
    fun line(name: String, noise: String, lean: Boolean, r: Result) = println(
        String.format(Locale.US, "%-26s %-11s %-7s %5.1f%% %6.2f %5.1f%%   %s", name, noise, if (lean) "leans" else "agrees", r.acc * 100, r.macroF1, r.trainAcc * 100,
            r.recall.joinToString(" ") { if (it.isNaN()) "    -" else String.format(Locale.US, "%4.0f%%", it * 100) }),
    )
    if (System.getProperty("part") == "two") { partTwo(user, service, corpus, base, ::f, ::idx, ::userLabels); return }
    val only = System.getProperty("only")
    for (noise in listOf("clean", "random10", "systematic")) {
        for ((name, recipe) in designs) {
            if (only != null && !name.startsWith(only)) continue
            line(name, noise, false, run(recipe, noise, false))
        }
    }
    // The service's own lean, and how much of it to take.
    for (weight in listOf(0.0, 0.35, 1.0)) {
        line("personal, service ×$weight", "systematic", true, run(Recipe(serviceWeight = weight), "systematic", true))
        line("linear, service ×$weight", "systematic", true, run(Recipe.PRESETS[1].second.copy(serviceWeight = weight), "systematic", true))
    }
}

/**
 * Part two: whether "check the labels it's sure are wrong" finds real slips (and what fixing them
 * is worth), whether parts of words help, and what more labels are worth.
 */
private fun partTwo(
    user: List<LabeledText>, service: List<LabeledText>, corpus: List<LabeledText>, base: LocalModel,
    f: (LabeledText) -> List<String>, idx: (LabeledText) -> Int, userLabels: (String) -> List<Int>,
) {
    val linear = Recipe.PRESETS[1].second
    fun groupsOf(list: List<LabeledText>): IntArray {
        val g = IntArray(list.size)
        list.indices.groupBy { idx(list[it]) }.values.forEach { ids -> ids.forEachIndexed { n, i -> g[i] = idx(list[i]) * 1000 + n / 3 } }
        return g
    }
    fun cv(recipe: Recipe, texts: List<LabeledText>, labels: List<Int>, feats: (LabeledText) -> List<String> = f): Pair<List<Int>, List<Double>> {
        val g = groupsOf(texts)
        val scored = texts.indices.map { TrainingItem(feats(texts[it]), null, labels[it], recipe.userWeight, g[it].toLong()) }
        val others = service.map { TrainingItem(feats(it), null, idx(it), recipe.serviceWeight) } + corpus.map { TrainingItem(feats(it), null, idx(it), recipe.corpusWeight) }
        val out = RecipeTrainer.crossValidate(recipe, base, scored, others)
        val t = RecipeTrainer.calibrate(out.map { (i, s) -> s to labels[i] })
        val pred = IntArray(texts.size); val conf = DoubleArray(texts.size)
        out.forEach { (i, s) -> val p = LocalModel.softmax(s, t.toDouble()); pred[i] = p.indices.maxBy { p[it] }; conf[i] = p[pred[i]] }
        return pred.toList() to conf.toList()
    }
    fun acc(pred: List<Int>, labels: List<Int>) = pred.indices.count { pred[it] == labels[it] }.toDouble() / pred.size
    for (noise in listOf("random10", "systematic")) {
        val noisy = userLabels(noise)
        val truth = user.map(idx)
        val (pred, conf) = cv(linear, user, noisy)
        val wrongLabels = user.indices.count { noisy[it] != truth[it] }
        for (sure in listOf(0.8, 0.9)) {
            val flagged = user.indices.filter { pred[it] != noisy[it] && conf[it] >= sure }
            val real = flagged.count { noisy[it] != truth[it] }
            // The user checks each flagged label and sets it right; the model is retrained and scored again.
            val fixed = noisy.toMutableList().also { l -> flagged.forEach { l[it] = truth[it] } }
            val (pred2, _) = cv(linear, user, fixed)
            println(String.format(Locale.US, "%-10s flag at %.0f%%: %3d flagged, %3d of them really mislabeled (%.0f%%), of %d mislabeled in all; accuracy %.1f%% -> %.1f%% after fixing them",
                noise, sure * 100, flagged.size, real, if (flagged.isEmpty()) 0.0 else 100.0 * real / flagged.size, wrongLabels, acc(pred, noisy) * 100, acc(pred2, fixed) * 100))
        }
    }
    // Parts of words: every word's 4-letter pieces as features too.
    fun pieces(t: LabeledText): List<String> = f(t) + t.body.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 5 }
        .flatMap { w -> "^$w$".windowed(4).map { "g:$it" } }
    for (noise in listOf("clean", "random10")) {
        val labels = userLabels(noise)
        val plain = acc(cv(linear, user, labels).first, labels)
        val withPieces = acc(cv(linear, user, labels, ::pieces).first, labels)
        val neural = Recipe.PRESETS.first { it.first == "Neural, wider" }.second
        val nPlain = acc(cv(neural, user, labels).first, labels)
        val nPieces = acc(cv(neural, user, labels, ::pieces).first, labels)
        println(String.format(Locale.US, "%-10s word pieces: linear %.1f%% -> %.1f%%, neural 64+wide %.1f%% -> %.1f%%", noise, plain * 100, withPieces * 100, nPlain * 100, nPieces * 100))
    }
    // More labels: the same 400, cut to fewer.
    for (noise in listOf("clean", "random10")) {
        val labels = userLabels(noise)
        val row = listOf(100, 200, 300, 400).map { n -> val sub = user.take(n); acc(cv(linear, sub, labels.take(n)).first, labels.take(n)) }
        println(String.format(Locale.US, "%-10s labels 100/200/300/400: %s", noise, row.joinToString(" / ") { String.format(Locale.US, "%.1f%%", it * 100) }))
    }
}
