package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.local.ClassifierMetrics
import com.ericflo.winnow.classifier.DecisionProvider
import com.ericflo.winnow.classifier.local.ContextFeatures
import com.ericflo.winnow.classifier.local.ConversationReading
import com.ericflo.winnow.classifier.local.Featurizer
import com.ericflo.winnow.classifier.local.LabModelFile
import com.ericflo.winnow.classifier.local.LocalModel
import com.ericflo.winnow.classifier.local.MetricsCalculator
import com.ericflo.winnow.classifier.local.PersonalEvaluation
import com.ericflo.winnow.classifier.local.Predictor
import com.ericflo.winnow.classifier.local.RecipeSweep
import com.ericflo.winnow.classifier.local.ServiceSteerer
import com.ericflo.winnow.classifier.local.SweepRound
import com.ericflo.winnow.classifier.local.SweepScorer
import com.ericflo.winnow.classifier.local.SweepSpace
import com.ericflo.winnow.classifier.local.SweepTrial
import com.ericflo.winnow.classifier.local.Recipe
import com.ericflo.winnow.classifier.local.RecipeKind
import com.ericflo.winnow.classifier.local.RecipeTrainer
import com.ericflo.winnow.classifier.local.SenderMemory
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
import kotlinx.coroutines.sync.withLock
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
    private val context: android.content.Context,
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
    /** Told as training starts, to keep it going when the user leaves Winnow (see WorkService). */
    private val onRunStarted: () -> Unit = {},
    /** The classifier service a sweep can be steered by (see ServiceSteerer); null when none is set up. */
    private val steeringProvider: suspend () -> DecisionProvider? = { null },
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
        /** What its trained model takes up on the phone (compressed); 0 for a personal layer, which keeps no file. */
        val bytes: Long = 0,
        /**
         * Of the user's labels, the share the model trained on everything gets right: how well it
         * fits the labels it learns from, beside how it does on ones it hasn't seen (see LabDiagnosis).
         */
        val fitAccuracy: Double? = null,
        /**
         * On the user's newest labels, by a model trained on their older ones (see
         * RecipeTrainer.scoreNewest): how many, the share it followed as it would on the phone
         * (with their labels of each sender), and from the words alone.
         */
        val newestCount: Int = 0,
        val newestAccuracy: Double? = null,
        val newestWordsAccuracy: Double? = null,
    )

    /** What a training of everything came to. */
    private data class Trained(val millis: Long, val parameters: Long, val bytes: Long, val fitAccuracy: Double?)

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
        // Trained before their size was kept: read off their files.
        .map { e -> if (e.bytes == 0L && e.trainedAt != null) e.copy(bytes = modelFile(e.id).takeIf { it.exists() }?.length() ?: 0) else e }

    private val writes = kotlinx.coroutines.sync.Mutex()

    /**
     * [list] is the Lab's now; written to disk after, off the main thread (a tap saves too), one
     * write at a time and each of the latest list, so the file ends as the list does.
     */
    private fun save(list: List<Entry>) {
        _entries.value = list
        scope.launch(Dispatchers.IO) {
            writes.withLock {
                runCatching {
                    dir.mkdirs()
                    val part = File(dir, "recipes.json.part")
                    part.writeText(json.encodeToString(ListSerializer(Entry.serializer()), _entries.value))
                    part.renameTo(file)
                }.onFailure { android.util.Log.w("WinnowLab", "Couldn't save the Lab's designs", it) }
            }
        }
    }

    private fun update(id: String, change: (Entry) -> Entry) = save(_entries.value.map { if (it.id == id) change(it) else it })

    /** Designs from a backup not here already (by id), untrained: how many were added. */
    fun restore(designs: List<Triple<String, String, Pair<Recipe, Long>>>): Int {
        val have = _entries.value.mapTo(HashSet()) { it.id }
        val fresh = designs.filter { it.first !in have }.map { (id, name, rc) -> Entry(id, name, rc.first, rc.second) }
        if (fresh.isNotEmpty()) save(_entries.value + fresh)
        return fresh.size
    }

    fun create(name: String, recipe: Recipe): Entry {
        val entry = Entry(java.lang.Long.toHexString(System.nanoTime()).takeLast(6), name.trim().ifEmpty { recipe.describe() }, recipe, System.currentTimeMillis())
        save(_entries.value + entry)
        return entry
    }

    fun delete(id: String) {
        if (id == runningId() || id == trainingId) job?.cancel()
        scope.launch(Dispatchers.IO) { modelFile(id).delete() }
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
    fun trainAndScore(id: String) = launch(id) { entry -> trainScore(entry, id) }

    /** [trainAndScore]'s work, its progress said under [progressId] (a sweep keeps its best this way). */
    private suspend fun trainScore(entry: Entry, progressId: String) {
        val id = entry.id
        trainingId = id
        try {
            trainScoreOf(entry, progressId)
        } finally {
            trainingId = null
        }
    }

    /** The entry being trained, whatever the run is called (a sweep trains the best it found). */
    @Volatile private var trainingId: String? = null

    private suspend fun trainScoreOf(entry: Entry, progressId: String) {
        val id = entry.id
        val recipe = entry.recipe
        fun say(what: String, p: Float) = progress(progressId, what, p)
        say("Reading your labels…", 0f)
        val data = data()
        val ctx = currentCoroutineContext()
        val started = System.currentTimeMillis()
        val rows = withContext(Dispatchers.Default) {
            RecipeTrainer.crossValidateRows(
                recipe, LocalModel.bundled, data.scored, data.others, onFold = { f, k -> say("Scoring on your labels: part ${f + 1} of $k", 0.8f * f / k) },
                stopped = { !ctx.isActive }, parallelism = parallelismFor(recipe),
            )
        }
        val cv = rows.map { it.index to it.logits }
        val cvEarlier = rows.associate { it.index to it.earlier }
        // A trained model's odds set to match how often it was right on labels it hadn't seen.
        val temperature = if (recipe.kind == RecipeKind.PERSONAL) LocalModel.bundled.temperature else RecipeTrainer.calibrate(cv.map { (i, s) -> s to data.scored[i].label })
        val classes = LocalModel.bundled.classes
        fun memoryOf(indices: List<Int>) = SenderMemory.of(indices.mapNotNull { i -> data.scored[i].sender?.let { it to data.scored[i].label } }, classes, recipe.senderMemory)
        // Scored as the model would answer on the phone: with the user's labels of each sender,
        // from the other conversations only.
        val folds = RecipeTrainer.foldsOf(data.scored)
        val memories = folds?.let { f -> (0..f.max()).associateWith { fold -> memoryOf(data.scored.indices.filter { f[it] != fold }) } }.orEmpty()
        // What came before each text in its conversation, read by the same model, leans it first (see ConversationReading).
        fun leaned(i: Int, s: DoubleArray, earlier: Map<Int, List<DoubleArray>>) =
            ConversationReading.lean(LocalModel.softmax(s, temperature.toDouble()), earlier[i].orEmpty().map { LocalModel.softmax(it, temperature.toDouble()) }, recipe.conversationReading)
        val answered = cv.map { (i, s) ->
            val p = leaned(i, s, cvEarlier)
            val memory = folds?.let { memories[it[i]] }
            i to (data.scored[i].sender?.let { sender -> memory?.follow(p, sender, data.scored[i].conversing) }?.distribution ?: p)
        }
        val evalId = keepScoring(entry, data, answered, started)
        // On their newest labels, by a model trained on their older ones: where who sent it can count.
        say("Scoring on your newest labels…", 0.8f)
        val newestRows = withContext(Dispatchers.Default) { RecipeTrainer.scoreNewestRows(recipe, LocalModel.bundled, data.scored, data.others, stopped = { !ctx.isActive }) }
        val newest = newestRows.map { it.index to it.logits }
        val newestEarlier = newestRows.associate { it.index to it.earlier }
        val newestSet = newest.mapTo(HashSet()) { it.first }
        val olderMemory = memoryOf(data.scored.indices.filter { it !in newestSet })
        fun argmax(p: DoubleArray) = p.indices.maxBy { p[it] }
        val newestWords = newest.count { (i, s) -> argmax(s) == data.scored[i].label }
        val newestFollowed = newest.count { (i, s) ->
            val p = leaned(i, s, newestEarlier)
            (data.scored[i].sender?.let { olderMemory.follow(p, it, data.scored[i].conversing).best } ?: argmax(p)) == data.scored[i].label
        }
        say("Training on everything…", 0.85f)
        val trained = trainFinal(id, recipe, data, temperature, progressId)
        val m = evalId?.let { evals.get(it) }
        update(id) {
            it.copy(
                trainedAt = System.currentTimeMillis(), evalId = evalId, accuracy = m?.accuracy, macroF1 = m?.macroF1, scoredOn = m?.examples ?: 0,
                trainMillis = trained.millis, parameters = trained.parameters, temperature = temperature, learnedFrom = learnedFrom(recipe, data), leftOut = leftOut(recipe, data),
                bytes = trained.bytes, fitAccuracy = trained.fitAccuracy,
                newestCount = newest.size,
                newestAccuracy = newest.takeIf { it.isNotEmpty() }?.let { newestFollowed.toDouble() / it.size },
                newestWordsAccuracy = newest.takeIf { it.isNotEmpty() }?.let { newestWords.toDouble() / it.size },
            )
        }
        if (settings.current().labModel == id) onModelChanged()
    }

    /**
     * The latest sweep, as it stands: its plan, its tries best first, how each round was
     * steered and what that cost, and the Lab model its best became, if it beat the user's.
     */
    @Serializable
    data class Sweep(
        val startedAt: Long,
        val plan: RecipeSweep.Plan,
        /** The service steering it (by name), or null when the phone steers. */
        val steeredBy: String? = null,
        /** What steering was asked for but couldn't be: no service set up. */
        val noService: Boolean = false,
        /** What the user has, which it set out to beat, each as the sweep scored it. */
        val baselines: List<Mark> = emptyList(),
        val trials: List<SweepTrial> = emptyList(),
        val rounds: List<SweepRound> = emptyList(),
        /** Calls made to the service, and how many failed (the phone steered those rounds, and every one after). */
        val steeringCalls: Int = 0,
        val steeringFailed: Int = 0,
        val costUsd: Double = 0.0,
        val finishedAt: Long? = null,
        /** Cut off before it finished: Winnow was closed, or the phone restarted. */
        val interrupted: Boolean = false,
        /** The steering said there was little left to gain, and it ended before its last round. */
        val settled: Boolean = false,
        /** The user stopped it. */
        val stopped: Boolean = false,
        /** The Lab model its best try became. */
        val keptId: String? = null,
        val failed: String? = null,
    ) {
        val best: SweepTrial? get() = trials.maxByOrNull { it.accuracy }
        val tried: Int get() = trials.count { it.millis > 0 || it.from == "blend" }
    }

    /** Something a sweep set out to beat, and how it scored. */
    @Serializable
    data class Mark(val name: String, val accuracy: Double)

    /**
     * How the next sweep runs: its rounds, tries a round, how many times the service may steer
     * (each call is paid), and whether it may end early when the steering sees nothing left.
     */
    @Serializable
    data class SweepPrefs(
        val rounds: Int = 8,
        val perRound: Int = 8,
        val steeringCalls: Int = 8,
        val steer: Boolean = true,
        // Named anew when it came to start off: a sweep that ended early before is now let run every round.
        val endEarlyWhenSettled: Boolean = false,
    )

    private val contexts = com.ericflo.winnow.data.MessageContexts(context)
    /** The texts before each labeled one in its conversation, read once a process. */
    private val earlierCache: MutableMap<String, List<String>> = java.util.Collections.synchronizedMap(HashMap())

    /** Each labeled conversation's latest texts, read once a process. */
    private val conversationCache: MutableMap<Long, List<Pair<com.ericflo.winnow.data.MessageTexts.Text, com.ericflo.winnow.classifier.message.MessageContext>>> =
        java.util.Collections.synchronizedMap(HashMap())

    // Null where the store couldn't say (asked again next time): a ConcurrentHashMap can't hold that.
    private val contextCache: MutableMap<String, com.ericflo.winnow.classifier.message.MessageContext?> = java.util.Collections.synchronizedMap(HashMap())

    private val sweepFile = File(dir, "sweep.json")
    private val sweepPrefsFile = File(dir, "sweep-prefs.json")
    private val _sweep = MutableStateFlow(
        runCatching { json.decodeFromString(Sweep.serializer(), sweepFile.readText()) }.getOrNull()
            // Never finished, and nothing in this new process runs it: cut off.
            ?.let { if (it.finishedAt == null) it.copy(interrupted = true) else it },
    )
    val sweep: StateFlow<Sweep?> = _sweep.asStateFlow()
    private val _sweepPrefs = MutableStateFlow(runCatching { json.decodeFromString(SweepPrefs.serializer(), sweepPrefsFile.readText()) }.getOrDefault(SweepPrefs()))
    val sweepPrefs: StateFlow<SweepPrefs> = _sweepPrefs.asStateFlow()

    fun setSweepPrefs(prefs: SweepPrefs) {
        _sweepPrefs.value = prefs
        scope.launch(Dispatchers.IO) { writeFile(sweepPrefsFile, json.encodeToString(SweepPrefs.serializer(), prefs)) }
    }

    private fun publish(sweep: Sweep) {
        _sweep.value = sweep
        scope.launch(Dispatchers.IO) { writes.withLock { _sweep.value?.let { writeFile(sweepFile, json.encodeToString(Sweep.serializer(), it)) } } }
    }

    private fun writeFile(file: File, text: String) {
        runCatching {
            dir.mkdirs()
            val part = File(dir, file.name + ".part")
            part.writeText(text)
            part.renameTo(file)
        }.onFailure { android.util.Log.w("WinnowLab", "Couldn't save ${file.name}", it) }
    }

    /**
     * What the user has, for a sweep to beat: the model sorting their texts now (a Lab model, or
     * Winnow's own personal layer as their settings fit it), and their best-scoring Lab model if
     * that's another.
     */
    private suspend fun baselines(): List<Pair<String, Recipe>> {
        val s = settings.current()
        val inUse = _entries.value.firstOrNull { it.id == s.labModel }
        val running = inUse?.let { it.name to it.recipe } ?: (
            "Winnow's own (personal layer)" to Recipe(
                kind = RecipeKind.PERSONAL, epochs = s.personalEpochs, learningRate = s.personalStep, l2 = s.personalL2,
                serviceWeight = s.providerWeight, senderMemory = s.senderMemory,
            )
        )
        val best = _entries.value.filter { it.accuracy != null && it.id != inUse?.id }.maxByOrNull { it.accuracy!! }?.let { it.name to it.recipe }
        return listOfNotNull(running, best?.takeIf { it.second != running.second })
    }

    /**
     * Sweeps recipes on the user's labels (see RecipeSweep): rounds of tries, each scored as the
     * Lab scores a model, steered between rounds by the classifier service when it may be (shown
     * each try's settings and scores, never a text), by the phone otherwise. If its best beats
     * everything the user has, it's kept as a Lab model, trained on everything and scored like
     * any other, for the user to put in use or not.
     */
    fun startSweep() = launchJob(SWEEP) {
        val prefs = _sweepPrefs.value
        // Said at once, so a sweep stopped while it reads the labels is this one, not the last.
        val last = _sweep.value
        var state = Sweep(System.currentTimeMillis(), RecipeSweep.Plan(rounds = prefs.rounds, perRound = prefs.perRound, steeringCalls = 0, endEarly = prefs.endEarlyWhenSettled))
        publish(state)
        val ctx = currentCoroutineContext()
        try {
            progress(SWEEP, "Reading your labels…", 0f)
            val data = data()
            val provider = if (prefs.steer && prefs.steeringCalls > 0) runCatching { steeringProvider() }.getOrNull() else null
            // Called what the rest of Winnow calls it.
            val steerer = provider?.let { ServiceSteerer(it, settings.current().provider.label.substringBefore(" (")) }
            val plan = RecipeSweep.Plan(rounds = prefs.rounds, perRound = prefs.perRound, steeringCalls = if (steerer != null) prefs.steeringCalls else 0, endEarly = prefs.endEarlyWhenSettled)
            val baselines = baselines()
            val names = baselines.map { it.first }.toSet()
            val classes = LocalModel.bundled.classes
            val unwanted = Category.entries.filter { it.defaultAction == Action.FILTER }.map { classes.indexOf(it.key) }.filter { it >= 0 }.toSet()
            val scorer = SweepScorer(LocalModel.bundled, data.scored, data.others, unwanted, settings.current().actionPolicy.onDeviceMinConfidence, parallelism = ::parallelismFor)
            state = state.copy(plan = plan, steeredBy = steerer?.name, noService = prefs.steer && prefs.steeringCalls > 0 && steerer == null)
            publish(state)
            // Where the last sweep got to: its best tries start this one, so each sweep picks up from the one before.
            val again = last?.trials.orEmpty().filter { it.settings != null && it.from !in names && it.from != "blend" }
                .take(LAST_BEST).map { "the last sweep's best" to it.recipe }
            val planned = SweepSpace.STARTS.size + baselines.size + again.size + plan.rounds * plan.perRound
            var done = 0
            val result = withContext(Dispatchers.Default) {
                RecipeSweep(scorer, steerer, plan).run(
                    baselines,
                    onEvent = { e ->
                        when (e) {
                            is RecipeSweep.Event.Trying -> progress(
                                SWEEP,
                                (if (e.round == 0) "Starting points" else "Round ${e.round} of ${plan.rounds}") + ": try ${e.n} of ${e.of}" +
                                    (state.best?.let { " · best so far ${pctOf(it.accuracy)}" } ?: ""),
                                (done.toFloat() / planned).coerceAtMost(0.95f),
                            )
                            is RecipeSweep.Event.Tried -> {
                                if (e.trial.millis > 0) done++
                                state = state.copy(
                                    trials = (state.trials + e.trial).sortedByDescending { it.accuracy },
                                    baselines = if (e.trial.from in names) state.baselines + Mark(e.trial.from, e.trial.accuracy) else state.baselines,
                                )
                                publish(state)
                            }
                            is RecipeSweep.Event.Steered -> {
                                val asked = steerer != null && (e.round.steeredBy == steerer.name || e.round.note != null)
                                state = state.copy(
                                    rounds = state.rounds + e.round,
                                    steeringCalls = state.steeringCalls + (if (asked) 1 else 0),
                                    steeringFailed = state.steeringFailed + (if (asked && e.round.steeredBy != steerer?.name) 1 else 0),
                                    costUsd = state.costUsd + e.round.costUsd,
                                )
                                publish(state)
                            }
                        }
                    },
                    stopped = { !ctx.isActive },
                    starts = again,
                )
            }
            state = state.copy(
                trials = result.trials, steeringCalls = result.steeringCalls, steeringFailed = result.steeringFailed, costUsd = result.costUsd,
                settled = result.stoppedEarly, finishedAt = System.currentTimeMillis(),
            )
            publish(state)
            // Its best, if it beats everything the user has, becomes a Lab model like any other.
            val best = result.trials.firstOrNull()
            val bar = state.baselines.maxOfOrNull { it.accuracy }
            if (best != null && best.from !in names && (bar == null || best.accuracy > bar)) {
                val called = "Best of the ${java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(state.startedAt))} sweep"
                // A second sweep the same day: "(2)", not two models of one name.
                val name = generateSequence(1) { it + 1 }.map { if (it == 1) called else "$called ($it)" }.first { n -> _entries.value.none { it.name == n } }
                val entry = create(name, best.recipe)
                state = state.copy(keptId = entry.id)
                publish(state)
                trainScore(entry, SWEEP)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            publish(state.copy(stopped = true, finishedAt = System.currentTimeMillis()))
            throw e
        } catch (e: Throwable) {
            publish(state.copy(failed = "${e::class.simpleName}: ${e.message}", finishedAt = System.currentTimeMillis()))
            throw e
        }
    }

    /**
     * How many of [recipe]'s folds train at once: as many as the phone has cores to spare (up to
     * four), and as fit in the memory Winnow has left, with room to spare. One, at worst.
     */
    private fun parallelismFor(recipe: Recipe): Int {
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()) - MEMORY_RESERVE
        val fits = (free / (RecipeTrainer.memoryOf(recipe) * 3 / 2)).toInt()
        return minOf(MAX_PARALLEL, runtime.availableProcessors() - 1, fits).coerceAtLeast(1)
    }

    /** A sweep's try kept as a Lab model of its own, trained and scored like any other; null while something else trains. */
    fun keepTrial(trial: SweepTrial): Entry? {
        if (job?.isActive == true) return null
        val entry = create("From the sweep: ${pctOf(trial.accuracy)}", trial.recipe)
        trainAndScore(entry.id)
        return entry
    }

    private fun pctOf(x: Double) = "${"%.1f".format(x * 100)}%"

    /** Trains the model in use again on everything as it stands, without scoring it (the user's "Retrain on device"). */
    fun retrainInUse() {
        scope.launch {
            val id = settings.current().labModel ?: run { onModelChanged(); return@launch }
            launch(id) { entry ->
                progress(id, "Training on everything…", 0.1f)
                val data = data()
                val t = trainFinal(id, entry.recipe, data, entry.temperature)
                update(id) {
                    it.copy(
                        trainedAt = System.currentTimeMillis(), trainMillis = t.millis, parameters = t.parameters, learnedFrom = learnedFrom(entry.recipe, data),
                        leftOut = leftOut(entry.recipe, data), bytes = t.bytes, fitAccuracy = t.fitAccuracy,
                    )
                }
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
        // Who sent it counts on the phone as the design that's put in use says.
        // Who sent it, and what came before in its conversation, count on the phone as the design in use says.
        settings.update { it.copy(senderMemory = entry?.recipe?.senderMemory ?: it.senderMemory, conversationReading = entry?.recipe?.conversationReading ?: 0.0) }
        onModelChanged()
    }

    /** The model in use from the Lab, if one is and it's been trained (see Learner's labModel). */
    suspend fun inUse(): Pair<String, Predictor>? = withContext(Dispatchers.IO) {
        val id = runCatching { settings.current().labModel }.getOrNull() ?: return@withContext null
        val entry = _entries.value.firstOrNull { it.id == id } ?: return@withContext null
        // Asked at every refit of the personal layer (every label): read from disk only when it changed.
        loaded?.takeIf { it.first == id to entry.trainedAt }?.let { return@withContext id to it.second }
        loadModel(id, entry.recipe)?.also { loaded = (id to entry.trainedAt) to it }?.let { id to it }
    }

    @Volatile private var loaded: Pair<Pair<String, Long?>, Predictor>? = null

    private fun launch(id: String, block: suspend (Entry) -> Unit) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        launchJob(id) { block(entry) }
    }

    private fun launchJob(id: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        // Running before anything watches for it: the service keeping it going stops at Idle.
        progress(id, "Starting…", 0f)
        onRunStarted()
        job = scope.launch {
            try {
                block()
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

    /**
     * What every recipe learns from: the user's labels (scored), and the rest, each marked with
     * where it came from so a recipe says how much it counts (see TrainingItem.weightUnder).
     * [gone]: the user's labels whose text is gone, which only a model of the shipped size can
     * still learn from (through their kept buckets).
     */
    private class Data(val scored: List<TrainingItem>, val scoredKeys: List<Pair<String, Long>>, val others: List<TrainingItem>, val gone: Int)

    /** Whether [recipe] can learn from a text through its kept buckets alone (see [Data.gone]). */
    private fun keepsBuckets(recipe: Recipe): Boolean = when (recipe.kind) {
        RecipeKind.PERSONAL -> true
        RecipeKind.BLEND -> recipe.members.all { keepsBuckets(it) }
        // Pieces of words or not: a text that's gone teaches it its words through their kept buckets.
        else -> recipe.buckets == LocalModel.bundled.buckets
    }

    private fun leftOut(recipe: Recipe, data: Data) = if (keepsBuckets(recipe)) 0 else data.gone

    /** The texts [recipe] learns from: those it counts at all, and can read. */
    private fun learnedFrom(recipe: Recipe, data: Data): Int =
        if (recipe.kind == RecipeKind.BLEND) recipe.members.maxOf { learnedFrom(it, data) }
        else (data.scored + data.others).count { it.weightUnder(recipe) > 0 && (it.features != null || keepsBuckets(recipe)) }

    private suspend fun data(): Data = withContext(Dispatchers.IO) {
        val classes = LocalModel.bundled.classes
        val rows = corrections.all().filter { it.featurizerVersion == Featurizer.VERSION && classes.indexOf(it.label) >= 0 }
        val recheck = verdicts.recheckThreads().toSet()
        val found = texts.of(rows.mapNotNull { it.messageKey })
        val replied = repliedThreads()
        fun buckets(s: String) = s.split(',').mapNotNull(String::toIntOrNull).toIntArray()
        // Each text's context, read once a process (what came before a text doesn't change).
        fun contextOf(key: String?): List<String>? {
            val t = key?.let(found::get) ?: return null
            val ctx = contextCache.getOrPut(key) { contexts.before(t.threadId, t.date, key) } ?: return null
            return ContextFeatures.of(ctx)
        }
        // The texts before each labeled one in its conversation, as the phone reads them with it.
        fun earlierOf(key: String?): List<List<String>>? {
            val t = key?.let(found::get) ?: return null
            val address = t.address ?: return null
            val bodies = earlierCache.getOrPut(key) { contexts.earlierBodies(t.threadId, t.date, key) }
            return bodies.map { Featurizer.features(Featurizer.Input(address, it, contacts.isContact(address), t.threadId in replied)) }
        }
        fun featuresOf(key: String?): List<String>? {
            val t = key?.let(found::get) ?: return null
            val address = t.address ?: return null
            return Featurizer.features(Featurizer.Input(address, t.body, contacts.isContact(address), t.threadId in replied))
        }
        // A text that's gone can still teach a model of the shipped size through its kept buckets;
        // others can't read it (RecipeTrainer leaves it out of those).
        var gone = 0
        val scored = mutableListOf<TrainingItem>()
        val scoredKeys = mutableListOf<Pair<String, Long>>()
        val others = mutableListOf<TrainingItem>()
        for (r in rows) {
            val label = classes.indexOf(r.label)
            val f = featuresOf(r.messageKey)
            val t = r.messageKey?.let(found::get)
            if (f == null && !r.fromProvider) gone++
            val idx = buckets(r.buckets)
            when {
                // With its conversation, so it's left out wherever that conversation is held out.
                r.fromProvider -> others += TrainingItem(f, idx, label, 1.0, group = r.threadId ?: -1, key = r.messageKey, source = TrainingItem.Source.SERVICE, contextFeatures = contextOf(r.messageKey))
                // The user's labels on texts are the answer key, a conversation's labels kept together;
                // a label still waiting to be rechecked under the six categories trains but isn't scored.
                // Only those whose text is still here, which every recipe can read, so all are scored on
                // the same labels; one whose text is gone trains whatever can learn from its kept
                // buckets, never while its conversation is held out.
                r.messageKey != null && r.threadId != null && !r.messageKey.startsWith("restored:") && r.threadId !in recheck && f != null -> {
                    scored += TrainingItem(
                        f, idx, label, 1.0, group = r.threadId, key = r.messageKey, sender = t?.address, at = r.createdAt, conversing = r.threadId in replied,
                        source = TrainingItem.Source.USER, contextFeatures = contextOf(r.messageKey), earlier = earlierOf(r.messageKey),
                    )
                    scoredKeys += r.messageKey to r.threadId
                }
                r.messageKey != null && r.threadId != null && !r.messageKey.startsWith("restored:") && r.threadId !in recheck -> others += TrainingItem(
                    null, idx, label, 1.0, group = r.threadId, key = com.ericflo.winnow.classifier.local.PersonalEvaluation.threadKey(r.threadId), source = TrainingItem.Source.USER,
                )
                // A conversation's own correction: left out wherever that conversation is held out.
                else -> others += TrainingItem(
                    f, idx, label, 1.0, group = r.threadId ?: -1, key = r.messageKey ?: r.threadId?.let(com.ericflo.winnow.classifier.local.PersonalEvaluation::threadKey),
                    source = TrainingItem.Source.USER, contextFeatures = contextOf(r.messageKey),
                )
            }
        }
        // The rest of each conversation the user labeled one way, taken as that label: for recipes
        // that learn from them (see Recipe.conversationWeight). With their conversation, so they're
        // left out wherever it's held out; never a text the user labeled themselves.
        val mine = rows.filter { !it.fromProvider && it.threadId != null && it.threadId !in recheck }
        val oneWay = mine.groupBy { it.threadId!! }.mapNotNull { (thread, labels) -> labels.map { it.label }.distinct().singleOrNull()?.let { thread to classes.indexOf(it) } }
        val labeledKeys = rows.mapNotNullTo(HashSet()) { it.messageKey }
        for ((thread, label) in oneWay.take(MAX_CONVERSATIONS)) {
            val latest = conversationCache.getOrPut(thread) { contexts.latestFromThem(thread, PER_CONVERSATION) }
            for ((t, ctx) in latest) {
                val address = t.address ?: continue
                if (t.key in labeledKeys) continue
                others += TrainingItem(
                    Featurizer.features(Featurizer.Input(address, t.body, contacts.isContact(address), thread in replied)), null, label, 1.0,
                    group = thread, key = "conversation:${t.key}", source = TrainingItem.Source.CONVERSATION, contextFeatures = ContextFeatures.of(ctx),
                )
            }
        }
        ShippedCorpus.texts.forEach { t ->
            others += TrainingItem(Featurizer.features(Featurizer.Input(t.sender, t.body)), null, classes.indexOf(t.category.key), 1.0, source = TrainingItem.Source.CORPUS)
        }
        Data(scored, scoredKeys, others, gone)
    }

    /** Trains on everything and keeps the model; its training time and size. */
    private suspend fun trainFinal(id: String, recipe: Recipe, data: Data, temperature: Float, progressId: String = id): Trained {
        val ctx = currentCoroutineContext()
        val started = System.nanoTime()
        val model = withContext(Dispatchers.Default) {
            RecipeTrainer.train(recipe, LocalModel.bundled, data.scored + data.others, onProgress = { e, n -> progress(progressId, "Training on everything: pass ${e + 1} of $n", 0.85f + 0.15f * e / n) }, stopped = { !ctx.isActive })
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        val parameters = LabModelFile.parameters(model)
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val part = File(dir, "$id.model.part")
            // A personal layer needs no file: Winnow fits it its own way when it's put in use.
            val kept = DeflaterOutputStream(part.outputStream().buffered()).use { out -> LabModelFile.write(recipe, model, temperature, out) }
            if (kept) part.renameTo(modelFile(id)) else part.delete()
        }
        val bytes = withContext(Dispatchers.IO) { modelFile(id).takeIf { recipe.kind != RecipeKind.PERSONAL && it.exists() }?.length() ?: 0 }
        // The labels it learned from, scored by it: what it can fit at all.
        val fit = withContext(Dispatchers.Default) {
            data.scored.mapNotNull { item -> RecipeTrainer.logits(model, item)?.let { s -> s.indices.maxBy { s[it] } == item.label } }
                .takeIf { it.isNotEmpty() }?.let { r -> r.count { it }.toDouble() / r.size }
        }
        return Trained(millis, parameters, bytes, fit)
    }

    private fun modelFile(id: String) = File(dir, "$id.model")

    private fun loadModel(id: String, recipe: Recipe): Predictor? = runCatching {
        InflaterInputStream(modelFile(id).inputStream().buffered()).use { input -> LabModelFile.read(recipe, input) }
    }.onFailure { android.util.Log.w("WinnowLab", "Couldn't read the model of $id", it) }.getOrNull()

    /** The cross-validated scores kept as an evaluation of this recipe, beside every other. */
    private suspend fun keepScoring(entry: Entry, data: Data, cv: List<Pair<Int, DoubleArray>>, at: Long): Long? = withContext(Dispatchers.IO) {
        if (cv.isEmpty()) return@withContext null
        val classes = LocalModel.bundled.classes
        val unwanted = Category.entries.filter { it.defaultAction == Action.FILTER }.map { classes.indexOf(it.key) }.filter { it >= 0 }.toSet()
        val rows = cv.map { (i, p) -> Scored(data.scored[i].label, p) }
        val m: ClassifierMetrics = MetricsCalculator.compute(entry.name, "Cross-validated on your labels", classes, rows, unwanted, settings.current().actionPolicy.onDeviceMinConfidence)
        val id = evals.insert(
            EvalEntity(
                at = at, model = "lab:${entry.id}", label = entry.name, dataset = EvalEntity.DATASET_MINE, method = EvalEntity.METHOD_CROSS_VALIDATED,
                examples = rows.size, accuracy = m.accuracy.finite(), macroF1 = m.macroF1.finite(), kappa = m.kappa.finite(),
                unwantedAuc = m.unwanted.auc.takeIf { it.isFinite() }, falsePositiveRate = m.unwanted.operatingPoint.falsePositiveRate.takeIf { it.isFinite() },
                metrics = json.encodeToString(ClassifierMetrics.serializer(), m),
                note = "${entry.recipe.describe()}. Each of your labeled texts scored by the recipe trained without its conversation's labels" +
                    (if (entry.recipe.kind != RecipeKind.PERSONAL && entry.recipe.includeCorpus) ", on the shipped examples too" else "") +
                    (if (entry.recipe.senderMemory > 0) ", with your labels of each sender from the other conversations" else "") + ".",
            ),
        )
        evals.insertItems(
            cv.mapNotNull { (i, p) ->
                val best = p.indices.maxBy { p[it] }
                data.scoredKeys.getOrNull(i)?.let { (key, thread) -> EvalItemEntity(id, key, thread, classes[data.scored[i].label], classes[best], p[best]) }
            },
        )
        id
    }

    private fun Double.finite() = if (isFinite()) this else 0.0

    companion object {
        /** A sweep's id where a model's would be, in [Status]. */
        const val SWEEP = "sweep"

        /** Folds trained at once, at most; and memory kept free for the rest of Winnow while they train. */
        private const val MAX_PARALLEL = 4
        private const val MEMORY_RESERVE = 96L * 1024 * 1024

        /** How many of the last sweep's best tries start the next. */
        private const val LAST_BEST = 3

        /** Of each conversation labeled one way, the latest texts learned from; of those conversations, at most so many. */
        private const val PER_CONVERSATION = 15
        private const val MAX_CONVERSATIONS = 1500

        /** What a recipe's numbers mean, in a line each, for the editor. */
        val HELP = mapOf(
            "kind" to "Personal layer: the shipped model with a light layer of what you taught on top (what Winnow does now). Linear: one weight per word and category, trained from scratch here. Neural: a small network that can learn combinations words alone can't say.",
            "buckets" to "Words are hashed into this many buckets. More is wider: fewer words share one, more to learn.",
            "layers" to "The network's widths: the first is each word's embedding, each after is a hidden layer. More layers is deeper; bigger numbers are wider. On texts this short, one layer usually follows your labels as well as two or more, and deeper can do worse: try one first.",
            "wide" to "Adds a linear part beside the network, so single words still count directly (wide & deep).",
            "epochs" to "Passes over every text while training. More fits the labels it learns from closer; past a point a network carries them over to new texts worse, not better.",
            "learningRate" to "How big each step is (AdaGrad). Too big jumps around; too small learns slowly.",
            "l2" to "How hard every weight is pulled toward zero: more is plainer and steadier, less fits closer.",
            "dropout" to "The share of the network left out at each step, so it can't lean on any one part.",
            "bags" to "Train this many on resampled texts and average them: steadier, slower.",
            "inputDropout" to "The share of a text's words left out of each training step, a different few each time. No one word can carry a text, so the model learns from the rest of it too: it memorizes your labels less and carries them over to new texts better.",
            "pieces" to "Also learn from pieces of words, four letters at a time, so words that share a stem (redeliver, redelivery) or a misspelling share what's learned.",
            "conversationReading" to "Your categories are mostly a conversation's: a pharmacy's thread is reminders, a friend's is personal. This has the model read the texts before each one in its conversation too (no labels needed: they're there when it arrives), and lean its answer the way they read, by this much. A text whose own words are clear stays as they say; one that could be either goes the way its conversation does. 0 leaves it out. Scored the same way: on conversations it hadn't learned from, reading what came before.",
            "crosses" to "Also learn each word as from the kind of sender it came from: a business (a short code or a named sender), a stranger's number, or someone you text or have as a contact. \"Appointment\" from a clinic and from a friend can then mean different things to it.",
            "conversationWeight" to "How much each of your other texts counts in a conversation whose labels from you all agree, taken as that label: a pharmacy's other reminders, a friend's other texts. Many more of your own texts to learn from; never one in a conversation being scored. 0 leaves them out.",
            "context" to "Also learn from when each text came and what came before it in its conversation: the time of day, a weekday or the weekend, whether it opened the conversation or answered your text, how much came before it, how long since the last text. Where texts read alike, these can be what tells them apart to you. It reads the same of each new text, on this phone.",
            "corpus" to "Also learn from the 1,493 hand-written texts the shipped model learned from.",
            "userWeight" to "How much each of your labels counts against one shipped text.",
            "serviceWeight" to "How much each of the classifier service's labels counts; 0 leaves them out. Your labels always count more: where the service sees texts differently from you, less here lets yours set the model.",
            "balance" to "Count each category equally, however many texts it has: it helps the model follow you on the categories you've labeled few of.",
            "senderMemory" to "Texts that read alike can be different things to you because of who sent them. This makes your labels of each sender count with the model's answer for their next texts: " +
                "three or more labels of a sender you don't text with (a pharmacy, a short code), all one way, decide; otherwise (fewer, labeled both ways, or someone you text with) they nudge, by this much. " +
                "0 leaves who sent it out. Your newest labels, scored by a model trained on the older ones, show what it adds.",
        )
    }
}
