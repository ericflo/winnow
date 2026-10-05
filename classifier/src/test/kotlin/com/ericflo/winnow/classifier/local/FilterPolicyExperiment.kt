package com.ericflo.winnow.classifier.local

import java.io.File
import java.util.Locale
import kotlin.math.ln

/**
 * Compares ways to cut wanted texts that get filtered, on out-of-fold predictions:
 * `./gradlew :classifier:filterPolicyExperiment`. Dev aid; nothing here ships.
 */
fun main() {
    val corpus = Corpus.load(File("training/corpus"))
    val classes = Corpus.classes
    val unwanted = LocalModelBuild.unwanted
    val examples = corpus.map { it.example(classes) }
    val binary = examples.map { Example(it.features, if (it.label in unwanted) 1 else 0) }
    val folds = Corpus.folds(corpus, LocalModelBuild.FOLDS)

    val single = arrayOfNulls<DoubleArray>(examples.size)
    val bagged = arrayOfNulls<DoubleArray>(examples.size)
    val gates = mutableMapOf<Double, Array<DoubleArray?>>()
    val costs = listOf(1.0, 2.0, 4.0, 8.0)
    costs.forEach { gates[it] = arrayOfNulls(examples.size) }
    folds.forEach { test ->
        val held = test.toSet()
        val train = examples.filterIndexed { i, _ -> i !in held }
        val m = LocalModelBuild.trainer().train(train)
        val bag = (1..7).map { b -> LocalModelTrainer(classes, bootstrap = b).train(train) }
        val trainBinary = binary.filterIndexed { i, _ -> i !in held }
        val gateModels = costs.associateWith { c -> LocalModelTrainer(listOf("wanted", "unwanted"), costs = doubleArrayOf(c, 1.0)).train(trainBinary) }
        test.forEach { i ->
            val idx = m.indices(examples[i].features)
            single[i] = m.scores(idx)
            val sum = DoubleArray(classes.size)
            bag.forEach { b -> b.scores(idx).forEachIndexed { c, v -> sum[c] += v / bag.size } }
            bagged[i] = sum
            costs.forEach { c -> gates.getValue(c)[i] = gateModels.getValue(c).scores(idx) }
        }
    }
    fun calibrated(logits: Array<DoubleArray?>, labels: List<Int>): List<DoubleArray> {
        val t = (5..60).map { it / 10.0 }.minBy { t -> labels.indices.sumOf { -ln(LocalModel.softmax(logits[it]!!, t)[labels[it]].coerceAtLeast(1e-12)) } }
        return logits.map { LocalModel.softmax(it!!, t) }
    }
    val labels = examples.map { it.label }
    val pSingle = calibrated(single, labels)
    val pBag = calibrated(bagged, labels)
    val pGate = costs.associateWith { c -> calibrated(gates.getValue(c), binary.map { it.label }).map { it[1] } }

    val positives = labels.count { it in unwanted }
    val negatives = labels.size - positives
    fun score(name: String, flag: (Int) -> Boolean) {
        var tp = 0; var fp = 0
        labels.indices.forEach { i -> if (flag(i)) { if (labels[i] in unwanted) tp++ else fp++ } }
        println(String.format(Locale.US, "%-58s FPR %5.2f%% (%2d)  recall %5.1f%%  precision %5.1f%%", name, 100.0 * fp / negatives, fp, 100.0 * tp / positives, 100.0 * tp / (tp + fp).coerceAtLeast(1)))
    }
    fun top(p: DoubleArray) = p.indices.maxBy { p[it] }

    println("$negatives wanted, $positives unwanted texts\n")
    score("baseline: top unwanted & conf>=0.85") { i -> top(pSingle[i]) in unwanted && pSingle[i].max() >= 0.85 }
    for (c in listOf(0.9, 0.95)) score("single: top unwanted & conf>=$c") { i -> top(pSingle[i]) in unwanted && pSingle[i].max() >= c }
    for (c in listOf(0.85, 0.9)) score("bagged: top unwanted & conf>=$c") { i -> top(pBag[i]) in unwanted && pBag[i].max() >= c }
    for (cost in costs) for (g in listOf(0.5, 0.7, 0.9)) {
        score("single 0.85 & gate(cost $cost) >= $g") { i -> top(pSingle[i]) in unwanted && pSingle[i].max() >= 0.85 && pGate.getValue(cost)[i] >= g }
    }
    for (cost in listOf(2.0, 4.0)) for (g in listOf(0.7, 0.9)) {
        score("bagged 0.85 & gate(cost $cost) >= $g") { i -> top(pBag[i]) in unwanted && pBag[i].max() >= 0.85 && pGate.getValue(cost)[i] >= g }
    }
    // Per-category: stricter only for scam, where wrong-number openers look like real texts.
    val scam = classes.indexOf("spam")
    for (sc in listOf(0.9, 0.95, 0.98)) score("single: 0.85, scam needs $sc") { i ->
        val t = top(pSingle[i]); t in unwanted && pSingle[i].max() >= (if (t == scam) sc else 0.85)
    }
    for (sc in listOf(0.95)) for (cost in listOf(2.0, 4.0)) score("bagged: 0.85, scam $sc, gate(cost $cost)>=0.7") { i ->
        val t = top(pBag[i]); t in unwanted && pBag[i].max() >= (if (t == scam) sc else 0.85) && pGate.getValue(cost)[i] >= 0.7
    }

    // A scam without a hook (link, money, number, payment app, job, crypto) is harmless left in the inbox, silenced.
    val lureWords = setOf("w:zelle", "w:venmo", "w:paypal", "w:telegram", "w:whatsapp", "w:crypto", "w:bitcoin", "w:invest", "w:investment",
        "w:investing", "w:job", "w:hiring", "w:recruiter", "w:wire", "w:deposit", "w:gift", "w:prize", "w:winner", "w:won", "w:claim", "w:loan",
        "w:refund", "w:bail", "w:shipping", "w:fee", "w:code", "w:paid", "w:pay", "w:earn", "w:profit", "w:returns", "b:cash app")
    fun lure(i: Int) = examples[i].features.any { it == "__url__" || it == "__money__" || it == "__phone__" || it == "__email__" || it in lureWords }
    fun quiet(name: String, flag: (Int) -> Boolean, silence: (Int) -> Boolean) {
        var tp = 0; var fp = 0; var quiet = 0
        labels.indices.forEach { i ->
            if (flag(i)) { if (labels[i] in unwanted) tp++ else fp++ }
            if (labels[i] in unwanted && (flag(i) || silence(i))) quiet++
        }
        println(String.format(Locale.US, "%-58s FPR %5.2f%% (%2d)  recall %5.1f%%  kept quiet %5.1f%%", name, 100.0 * fp / negatives, fp, 100.0 * tp / positives, 100.0 * quiet / positives))
    }
    println()
    // Silenced: everything the model calls unwanted but doesn't filter (Winnow silences anything below the bar).
    val silencedAny = { i: Int -> top(pSingle[i]) in unwanted }
    quiet("baseline", { i -> top(pSingle[i]) in unwanted && pSingle[i].max() >= 0.85 }, silencedAny)
    quiet("scam must have a lure to be filtered", { i -> val t = top(pSingle[i]); t in unwanted && pSingle[i].max() >= 0.85 && (t != scam || lure(i)) }, silencedAny)
    quiet("bagged + scam lure", { i -> val t = top(pBag[i]); t in unwanted && pBag[i].max() >= 0.85 && (t != scam || lure(i)) }, { i -> top(pBag[i]) in unwanted })
    quiet("bagged + scam lure + gate(cost 2)>=0.5", { i -> val t = top(pBag[i]); t in unwanted && pBag[i].max() >= 0.85 && (t != scam || lure(i)) && pGate.getValue(2.0)[i] >= 0.5 }, { i -> top(pBag[i]) in unwanted })

    // What actually reaches the user: the shipped ActionPolicy on the bagged model's verdicts, and
    // with an "unsure" floor below which the on-device verdict is just allowed (a near coin flip
    // between personal and scam shouldn't cost a real text its notification).
    val categories = classes.map { name -> com.ericflo.winnow.classifier.message.Category.entries.first { it.key == name } }
    val policy = com.ericflo.winnow.classifier.message.ActionPolicy()
    fun action(i: Int, floor: Double): com.ericflo.winnow.classifier.message.Action {
        val p = pBag[i]; val t = top(p)
        if (p[t] < floor) return com.ericflo.winnow.classifier.message.Action.ALLOW
        return policy.resolve(categories[t], p[t], com.ericflo.winnow.classifier.message.Origin.ON_DEVICE, Featurizer.hasHook(examples[i].features))
    }
    val ALLOW = com.ericflo.winnow.classifier.message.Action.ALLOW
    // Personal and transactional: the texts that must keep their notification (marketing is meant to be quiet).
    val important = setOf(classes.indexOf("personal"), classes.indexOf("transactional"))
    val wantedIdx = labels.indices.filter { labels[it] !in unwanted }
    val importantIdx = labels.indices.filter { labels[it] in important }
    val unwantedIdx = labels.indices.filter { labels[it] in unwanted }
    fun report(name: String, act: (Int) -> com.ericflo.winnow.classifier.message.Action) {
        val muted = importantIdx.count { act(it) != ALLOW }
        val filtered = wantedIdx.count { act(it) == com.ericflo.winnow.classifier.message.Action.FILTER }
        val quiet = unwantedIdx.count { act(it) != ALLOW }
        println(String.format(Locale.US, "%-46s important muted %5.1f%% (%3d)  unwanted kept quiet %5.1f%%  wanted filtered %4.2f%%", name, 100.0 * muted / importantIdx.size, muted, 100.0 * quiet / unwantedIdx.size, 100.0 * filtered / wantedIdx.size))
    }
    println("\nNotifications (bagged model, shipped policy):")
    for (floor in listOf(0.0, 0.5, 0.55, 0.6, 0.65, 0.7)) report("floor $floor") { action(it, floor) }
    // Narrower: only an unsure scam or phishing guess with nothing to defraud with is let through.
    val needsHook = setOf(classes.indexOf("spam"))
    fun hooklessUnsure(i: Int, below: Double): Boolean {
        val p = pBag[i]; val t = top(p)
        return t in needsHook && p[t] < below && !Featurizer.hasHook(examples[i].features)
    }
    for (below in listOf(0.7, 0.85)) report("hookless scam/phishing < $below allowed") { if (hooklessUnsure(it, below)) ALLOW else action(it, 0.0) }
    for (below in listOf(0.7, 0.85)) for (floor in listOf(0.55, 0.6)) report("hookless < $below allowed, floor $floor") { if (hooklessUnsure(it, below)) ALLOW else action(it, floor) }
    println("\nWanted texts muted at floor 0 (bagged):")
    wantedIdx.filter { action(it, 0.0) != com.ericflo.winnow.classifier.message.Action.ALLOW }.sortedBy { pBag[it].max() }.forEach { i ->
        println(String.format(Locale.US, "  %s → %s (%.0f%%) %s: %s", classes[labels[i]], classes[top(pBag[i])], pBag[i].max() * 100, action(i, 0.0), corpus[i].body.take(80)))
    }

    println("\nWanted texts filtered by the baseline rule:")
    labels.indices.filter { i -> labels[i] !in unwanted && top(pSingle[i]) in unwanted && pSingle[i].max() >= 0.85 }.forEach { i ->
        println(String.format(Locale.US, "  %s → %s (%.0f%%): %s", classes[labels[i]], classes[top(pSingle[i])], pSingle[i].max() * 100, corpus[i].body.take(90)))
    }
}
