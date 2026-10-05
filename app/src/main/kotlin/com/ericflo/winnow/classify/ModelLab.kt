package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.local.ClassifierMetrics
import com.ericflo.winnow.classifier.local.Featurizer
import com.ericflo.winnow.classifier.local.LinearPredictor
import com.ericflo.winnow.classifier.local.LocalModel
import com.ericflo.winnow.classifier.local.MetricsCalculator
import com.ericflo.winnow.classifier.local.NeuralModel
import com.ericflo.winnow.classifier.local.Predictor
import com.ericflo.winnow.classifier.local.Recipe
import com.ericflo.winnow.classifier.local.RecipeKind
import com.ericflo.winnow.classifier.local.RecipeTrainer
import com.ericflo.winnow.classifier.local.Scored
import com.ericflo.winnow.classifier.local.ShippedCorpus
import com.ericflo.winnow.classifier.local.TrainingItem
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.MessageTexts
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.db.CorrectionDao
import com.ericflo.winnow.data.db.EvalDao
import com.ericflo.winnow.data.db.EvalEntity
import com.ericflo.winnow.data.db.EvalItemEntity
import com.ericflo.winnow.data.db.VerdictDao
import java.io.File
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The Lab: models the user designs, trains on the phone, scores against their own labels and,
 * if they like one better, puts in use. Each is a [Recipe] (its kind, its capacity, how it's
 * fitted, what it learns from) kept with its latest scores and, once trained, the model itself,
 * so nothing about a result is ever hidden or lost. Training is the user's to start; a model in
 * use can be trained again on demand, or after each Train round and backlog run.
 */
class ModelLab(
    private val scope: CoroutineScope,
    private val corrections: CorrectionDao,
    private val verdicts: VerdictDao,
    private val contacts: ContactLookup,
    private val settings: SettingsRepository,
    private val texts: MessageTexts,
    private val evals: EvalDao,
    /** Threads the user has written in, so a text is featurized as the model saw it (see Bootstrap). */
    private val repliedThreads: () -> Set<Long>,
    private val dir: File,
    /** Told when the model in use changed or was trained again (Learner.reload). */
    private val onModelChanged: suspend () -> Unit,
) {
    /** One recipe and what became of it. */
    @Serializable
    data class Entry(
        val id: String,
        val name: String,
        val recipe: Recipe,
        val createdAt: Long,
        val trainedAt: Long? = null,
        /** Its latest scoring (EvalEntity), and the headline numbers from it. */
        val evalId: Long? = null,
        val accuracy: Double? = null,
        val macroF1: Double? = null,
        val scoredOn: Int = 0,
        /** How long the last full training took, the model's size, and its calibration. */
        val trainMillis: Long = 0,
        val parameters: Long = 0,
        val temperature: Float = 1f,
        /** Texts it learned from, and the user's labels left out because their text is gone and its buckets don't fit. */
        val learnedFrom: Int = 0,
        val leftOut: Int = 0,
    )

    sealed interface Status {
        data object Idle : Status
        data class Running(val id: String, val what: String, val progress: Float) : Status
        data class Failed(val id: String, val why: String) : Status
    }

    private val file = File(dir, "recipes.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private var job: Job? = null

    private fun load(): List<Entry> = runCatching { json.decodeFromString(ListSerializer(Entry.serializer()), file.readText()) }.getOrDefault(emptyList())

    private fun save(list: List<Entry>) {
        dir.mkdirs()
        val part = File(dir, "recipes.json.part")
        part.writeText(json.encodeToString(ListSerializer(Entry.serializer()), list))
        part.renameTo(file)
        _entries.value = list
    }

    private fun update(id: String, change: (Entry) -> Entry) = save(_entries.value.map { if (it.id == id) change(it) else it })

    fun create(name: String, recipe: Recipe): Entry {
        val entry = Entry(java.lang.Long.toHexString(System.nanoTime()).takeLast(6), name.trim().ifEmpty { recipe.describe() }, recipe, System.currentTimeMillis())
        save(_entries.value + entry)
        return entry
    }

    fun delete(id: String) {
        if (id == runningId()) job?.cancel()
        modelFile(id).delete()
        save(_entries.value.filterNot { it.id == id })
        scope.launch { if (settings.current().labModel == id) use(null) }
    }

    private fun runningId() = (_status.value as? Status.Running)?.id

    fun cancel() {
        job?.cancel()
    }

    /**
     * Trains [id]'s recipe and scores it on the user's labels: cross-validated by conversation,
     * then once more on everything, kept to put in use. Its scoring goes with every other.
     */
    fun trainAndScore(id: String) = launch(id) { entry ->
        val recipe = entry.recipe
        progress(id, "Reading your labels…", 0f)
        val data = data(recipe)
        val ctx = currentCoroutineContext()
        val started = System.currentTimeMillis()
        val cv = withContext(Dispatchers.Default) {
            RecipeTrainer.crossValidate(recipe, LocalModel.bundled, data.scored, data.others, onFold = { f, k -> progress(id, "Scoring on your labels: part ${f + 1} of $k", 0.8f * f / k) }, stopped = { !ctx.isActive })
        }
        // A trained model's odds set to match how often it was right on labels it hadn't seen.
        val temperature = if (recipe.kind == RecipeKind.PERSONAL) LocalModel.bundled.temperature else RecipeTrainer.calibrate(cv.map { (i, s) -> s to data.scored[i].label })
        val evalId = keepScoring(entry, data, cv, temperature, started)
        progress(id, "Training on everything…", 0.85f)
        val (millis, parameters) = trainFinal(id, recipe, data, temperature)
        val m = evalId?.let { evals.get(it) }
        update(id) {
            it.copy(
                trainedAt = System.currentTimeMillis(), evalId = evalId, accuracy = m?.accuracy, macroF1 = m?.macroF1, scoredOn = m?.examples ?: 0,
                trainMillis = millis, parameters = parameters, temperature = temperature, learnedFrom = data.scored.size + data.others.size, leftOut = data.leftOut,
            )
        }
        if (settings.current().labModel == id) onModelChanged()
    }

    /** Trains the model in use again on everything as it stands, without scoring it (the user's "Retrain on device"). */
    fun retrainInUse() {
        scope.launch {
            val id = settings.current().labModel ?: run { onModelChanged(); return@launch }
            launch(id) { entry ->
                progress(id, "Training on everything…", 0.1f)
                val data = data(entry.recipe)
                val (millis, parameters) = trainFinal(id, entry.recipe, data, entry.temperature)
                update(id) { it.copy(trainedAt = System.currentTimeMillis(), trainMillis = millis, parameters = parameters, learnedFrom = data.scored.size + data.others.size, leftOut = data.leftOut) }
                onModelChanged()
            }
        }
    }

    /**
     * Puts [id]'s model in use (null: back to Winnow's own, the personal layer). A personal-layer
     * recipe is put in use by fitting the personal layer its way.
     */
    suspend fun use(id: String?) {
        val entry = id?.let { i -> _entries.value.firstOrNull { it.id == i } }
        if (entry != null && entry.recipe.kind == RecipeKind.PERSONAL) {
            settings.update {
                it.copy(
                    labModel = null, personalEpochs = entry.recipe.epochs, personalStep = entry.recipe.learningRate,
                    personalL2 = entry.recipe.l2, providerWeight = entry.recipe.serviceWeight.coerceIn(0.0, 1.0),
                )
            }
        } else {
            settings.update { it.copy(labModel = entry?.id) }
        }
        onModelChanged()
    }

    /** The model in use from the Lab, if one is and it's been trained (see Learner's labModel). */
    suspend fun inUse(): Pair<String, Predictor>? = withContext(Dispatchers.IO) {
        val id = runCatching { settings.current().labModel }.getOrNull() ?: return@withContext null
        val entry = _entries.value.firstOrNull { it.id == id } ?: return@withContext null
        loadModel(id, entry.recipe.kind)?.let { id to it }
    }

    private fun launch(id: String, block: suspend (Entry) -> Unit) {
        if (job?.isActive == true) return
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        job = scope.launch {
            try {
                block(entry)
                _status.value = Status.Idle
            } catch (e: kotlinx.coroutines.CancellationException) {
                _status.value = Status.Idle
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("WinnowLab", "Training failed", e)
                _status.value = Status.Failed(id, "${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun progress(id: String, what: String, p: Float) {
        _status.value = Status.Running(id, what, p)
    }

    /** What a recipe learns from: the user's labels (scored), and the rest as it says. */
    private class Data(val scored: List<TrainingItem>, val scoredKeys: List<Pair<String, Long>>, val others: List<TrainingItem>, val leftOut: Int)

    private suspend fun data(recipe: Recipe): Data = withContext(Dispatchers.IO) {
        val classes = LocalModel.bundled.classes
        val rows = corrections.all().filter { it.featurizerVersion == Featurizer.VERSION && classes.indexOf(it.label) >= 0 }
        val recheck = verdicts.recheckThreads().toSet()
        val found = texts.of(rows.mapNotNull { it.messageKey })
        val replied = repliedThreads()
        fun buckets(s: String) = s.split(',').mapNotNull(String::toIntOrNull).toIntArray()
        fun featuresOf(key: String?): List<String>? {
            val t = key?.let(found::get) ?: return null
            val address = t.address ?: return null
            return Featurizer.features(Featurizer.Input(address, t.body, contacts.isContact(address), t.threadId in replied))
        }
        // A text that's gone can still teach a model of the shipped size through its kept buckets.
        val fits = { f: List<String>? -> f != null || recipe.kind == RecipeKind.PERSONAL || recipe.buckets == LocalModel.bundled.buckets }
        var leftOut = 0
        val scored = mutableListOf<TrainingItem>()
        val scoredKeys = mutableListOf<Pair<String, Long>>()
        val others = mutableListOf<TrainingItem>()
        for (r in rows) {
            val label = classes.indexOf(r.label)
            val f = featuresOf(r.messageKey)
            if (!fits(f)) { if (!r.fromProvider) leftOut++; continue }
            val idx = buckets(r.buckets)
            when {
                r.fromProvider -> if (recipe.serviceWeight > 0) others += TrainingItem(f, idx, label, recipe.serviceWeight)
                // The user's labels on texts are the answer key, a conversation's labels kept together;
                // a label still waiting to be rechecked under the six categories trains but isn't scored.
                r.messageKey != null && r.threadId != null && !r.messageKey.startsWith("restored:") && r.threadId !in recheck -> {
                    scored += TrainingItem(f, idx, label, recipe.userWeight, group = r.threadId)
                    scoredKeys += r.messageKey to r.threadId
                }
                else -> others += TrainingItem(f, idx, label, recipe.userWeight)
            }
        }
        if (recipe.kind != RecipeKind.PERSONAL && recipe.includeCorpus && recipe.corpusWeight > 0) {
            ShippedCorpus.texts.forEach { t ->
                others += TrainingItem(Featurizer.features(Featurizer.Input(t.sender, t.body)), null, classes.indexOf(t.category.key), recipe.corpusWeight)
            }
        }
        Data(scored, scoredKeys, others, leftOut)
    }

    /** Trains on everything and keeps the model; its training time and size. */
    private suspend fun trainFinal(id: String, recipe: Recipe, data: Data, temperature: Float): Pair<Long, Long> {
        val ctx = currentCoroutineContext()
        val started = System.nanoTime()
        val model = withContext(Dispatchers.Default) {
            RecipeTrainer.train(recipe, LocalModel.bundled, data.scored + data.others, onProgress = { e, n -> progress(id, "Training on everything: pass ${e + 1} of $n", 0.85f + 0.15f * e / n) }, stopped = { !ctx.isActive })
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        val parameters = when (model) {
            is NeuralModel -> model.also { it.temperature = temperature }.parameters
            is LinearPredictor -> model.model.buckets.toLong() * model.model.classes.size + (model.adjustments.size.toLong() * model.model.classes.size)
            else -> 0
        }
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val part = File(dir, "$id.model.part")
            DeflaterOutputStream(part.outputStream().buffered()).use { out ->
                when (model) {
                    is NeuralModel -> model.write(out)
                    // A personal layer needs no file: Winnow fits it its own way when it's put in use.
                    is LinearPredictor -> if (recipe.kind == RecipeKind.LINEAR) model.model.withTemperature(temperature).write(out)
                }
            }
            if (recipe.kind == RecipeKind.PERSONAL) part.delete() else part.renameTo(modelFile(id))
        }
        return millis to parameters
    }

    private fun modelFile(id: String) = File(dir, "$id.model")

    private fun loadModel(id: String, kind: RecipeKind): Predictor? = runCatching {
        InflaterInputStream(modelFile(id).inputStream().buffered()).use { input ->
            when (kind) {
                RecipeKind.NEURAL -> NeuralModel.read(input)
                RecipeKind.LINEAR -> LinearPredictor(LocalModel.read(input))
                RecipeKind.PERSONAL -> null
            }
        }
    }.getOrNull()

    /** The cross-validated scores kept as an evaluation of this recipe, beside every other. */
    private suspend fun keepScoring(entry: Entry, data: Data, cv: List<Pair<Int, DoubleArray>>, temperature: Float, at: Long): Long? = withContext(Dispatchers.IO) {
        if (cv.isEmpty()) return@withContext null
        val classes = LocalModel.bundled.classes
        val unwanted = Category.entries.filter { it.defaultAction == Action.FILTER }.map { classes.indexOf(it.key) }.filter { it >= 0 }.toSet()
        val rows = cv.map { (i, s) -> Scored(data.scored[i].label, LocalModel.softmax(s, temperature.toDouble())) }
        val m: ClassifierMetrics = MetricsCalculator.compute(entry.name, "Cross-validated on your labels", classes, rows, unwanted, settings.current().actionPolicy.onDeviceMinConfidence)
        val id = evals.insert(
            EvalEntity(
                at = at, model = "lab:${entry.id}", label = entry.name, dataset = EvalEntity.DATASET_MINE, method = EvalEntity.METHOD_CROSS_VALIDATED,
                examples = rows.size, accuracy = m.accuracy.finite(), macroF1 = m.macroF1.finite(), kappa = m.kappa.finite(),
                unwantedAuc = m.unwanted.auc.takeIf { it.isFinite() }, falsePositiveRate = m.unwanted.operatingPoint.falsePositiveRate.takeIf { it.isFinite() },
                metrics = json.encodeToString(ClassifierMetrics.serializer(), m),
                note = "${entry.recipe.describe()}. Each of your labeled texts scored by the recipe trained without its conversation's labels" +
                    (if (entry.recipe.kind != RecipeKind.PERSONAL && entry.recipe.includeCorpus) ", on the shipped examples too" else "") + ".",
            ),
        )
        evals.insertItems(
            cv.mapNotNull { (i, s) ->
                val p = LocalModel.softmax(s, temperature.toDouble())
                val best = p.indices.maxBy { p[it] }
                data.scoredKeys.getOrNull(i)?.let { (key, thread) -> EvalItemEntity(id, key, thread, classes[data.scored[i].label], classes[best], p[best]) }
            },
        )
        id
    }

    private fun Double.finite() = if (isFinite()) this else 0.0

    companion object {
        /** What a recipe's numbers mean, in a line each, for the editor. */
        val HELP = mapOf(
            "kind" to "Personal layer: the shipped model with a light layer of what you taught on top (what Winnow does now). Linear: one weight per word and category, trained from scratch here. Neural: a small network that can learn combinations words alone can't say.",
            "buckets" to "Words are hashed into this many buckets. More is wider: fewer words share one, more to learn.",
            "layers" to "The network's widths: the first is each word's embedding, each after is a hidden layer. More layers is deeper; bigger numbers are wider.",
            "wide" to "Adds a linear part beside the network, so single words still count directly (wide & deep).",
            "epochs" to "Passes over every text while training. More fits closer, and can overfit.",
            "learningRate" to "How big each step is (AdaGrad). Too big jumps around; too small learns slowly.",
            "l2" to "How hard every weight is pulled toward zero: more is plainer and steadier, less fits closer.",
            "dropout" to "The share of the network left out at each step, so it can't lean on any one part.",
            "bags" to "Train this many on resampled texts and average them: steadier, slower.",
            "corpus" to "Also learn from the 1,493 hand-written texts the shipped model learned from.",
            "userWeight" to "How much each of your labels counts against one shipped text.",
            "serviceWeight" to "How much each of the classifier service's labels counts; 0 leaves them out.",
            "balance" to "Count each category equally, however many texts it has.",
        )
    }
}
