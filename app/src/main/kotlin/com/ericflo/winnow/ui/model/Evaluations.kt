package com.ericflo.winnow.ui.model

import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.local.ClassifierMetrics
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classify.CurvePoint
import com.ericflo.winnow.classify.EvalData
import com.ericflo.winnow.classify.EvalResult
import com.ericflo.winnow.classify.EvalSubject
import com.ericflo.winnow.classify.Evaluator
import com.ericflo.winnow.classify.LabDiagnosis
import com.ericflo.winnow.data.MessageTexts
import com.ericflo.winnow.data.db.EvalEntity
import com.ericflo.winnow.data.db.EvalItemEntity
import com.ericflo.winnow.data.db.ModelFitEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One thing the user can pick to score, with what's needed to build it. */
sealed interface Pick {
    val id: String
    data object Now : Pick { override val id = "now" }
    data object Shipped : Pick { override val id = "shipped" }
    data object YoursOnly : Pick { override val id = "yours-only" }
    data object Service : Pick { override val id = "service" }
    data class Weight(val weight: Double) : Pick { override val id = "weight" }
    data class Kept(val fit: ModelFitEntity) : Pick { override val id = "fit:${fit.fit}" }
}

/** A labeled text a subject got wrong, as the drill-down shows it. */
data class Miss(val key: String, val threadId: Long?, val address: String?, val text: String?, val label: Category, val predicted: Category, val confidence: Double)

/**
 * Runs evaluations for the model screen (see [Evaluator]) and keeps each one (EvalEntity, its
 * items in EvalItemEntity), so earlier ones can be looked at and the model's scores followed over
 * time.
 */
class Evaluations(private val container: AppContainer, private val scope: CoroutineScope) {
    /** Every evaluation kept, newest first. */
    val history: StateFlow<List<EvalEntity>?> = container.evalDao.observeAll().stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Kept fits that can be scored. */
    val kept: StateFlow<List<ModelFitEntity>> = container.fitDao.observeAll().map { all -> all.filter { it.kept } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _progress = MutableStateFlow<String?>(null)
    /** What's being scored right now; null when nothing is. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val _curve = MutableStateFlow<List<CurvePoint>?>(null)
    val curve: StateFlow<List<CurvePoint>?> = _curve.asStateFlow()
    private val _curving = MutableStateFlow(false)
    val curving: StateFlow<Boolean> = _curving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Why the last scoring stopped short, if it did. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    private val _finishedAt = MutableStateFlow<Long?>(null)
    /** When the last scoring ran to its end (not stopped, not failed). */
    val finishedAt: StateFlow<Long?> = _finishedAt.asStateFlow()

    /**
     * Scores [picks] on the user's labels, one after another, keeping each as it's done. It keeps
     * going when the user leaves the screen or Winnow (see WorkService).
     */
    fun run(picks: List<Pick>, serviceName: String) {
        if (job?.isActive == true || picks.isEmpty()) return
        // Under way before anything watches for it: the service keeping it going stops when it isn't.
        _progress.value = "Reading your labels…"
        com.ericflo.winnow.classify.WorkService.start(container.appContext)
        job = scope.launch {
            val at = System.currentTimeMillis()
            _error.value = null
            try {
                _progress.value = "Reading your labels…"
                val data = withContext(Dispatchers.IO) {
                    EvalData.of(container.correctionDao.all(), container.verdictDao.all(), container.runDao.allAnswers())
                }
                val settings = container.settings.current()
                val evaluator = Evaluator(policy = settings.actionPolicy, providerWeight = settings.providerWeight)
                for (pick in picks) {
                    val subject = subjectOf(pick, serviceName) ?: continue
                    _progress.value = "Scoring ${subject.label}…"
                    val ctx = currentCoroutineContext()
                    val result = withContext(Dispatchers.Default) {
                        evaluator.evaluate(subject, data, onProgress = { _progress.value = it }, stopped = { !ctx.isActive })
                    }
                    keep(at, result)
                }
                _finishedAt.value = System.currentTimeMillis()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A scoring that fails is said so, never a crash of the app.
                android.util.Log.e("WinnowEvaluations", "Scoring failed", e)
                _error.value = "Scoring stopped: ${e::class.simpleName}: ${e.message}"
            } finally {
                _progress.value = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Replays how the model learned as the user labeled (see Evaluator.learningCurve). */
    fun replay() {
        if (_curving.value) return
        scope.launch {
            _curving.value = true
            try {
                val data = withContext(Dispatchers.IO) { EvalData.of(container.correctionDao.all(), emptyList(), emptyList()) }
                val ctx = currentCoroutineContext()
                val weight = container.settings.current().providerWeight
                _curve.value = withContext(Dispatchers.Default) { Evaluator(providerWeight = weight).learningCurve(data, stopped = { !ctx.isActive }) }
            } finally {
                _curving.value = false
            }
        }
    }

    /** The texts [eval] got wrong, with what they said, newest labels first. */
    suspend fun misses(eval: EvalEntity): List<Miss> = withContext(Dispatchers.IO) {
        val wrong = container.evalDao.items(eval.id).filter { it.label != it.predicted }
        val texts = MessageTexts(container.appContext).of(wrong.map { it.messageKey })
        wrong.mapNotNull { i ->
            val t = texts[i.messageKey]
            Miss(i.messageKey, i.threadId, t?.address, t?.body, Category.fromKey(i.label) ?: return@mapNotNull null, Category.fromKey(i.predicted) ?: return@mapNotNull null, i.confidence)
        }
    }

    /** A labeled text to open, with what [LabDiagnosis] says of it. */
    data class Shown(val key: String, val threadId: Long?, val address: String?, val text: String?, val label: Category, val predicted: Category? = null, val confidence: Double? = null)

    /** Two texts the user labeled differently that read alike, for them to look at together. */
    data class Review(val id: String, val a: Shown, val b: Shown)

    /** Everything [LabDiagnosis] says of one scoring, with the texts to open. */
    data class Diagnosis(
        val eval: EvalEntity,
        val accuracy: Double,
        val fitAccuracy: Double?,
        val confusions: List<LabDiagnosis.Confusion>,
        val categories: List<LabDiagnosis.CategoryRow>,
        /** The model's surest mistakes (see LabDiagnosis.surestMistakes). */
        val mistakes: List<Shown>,
        /** Alike texts with different labels the user hasn't looked at yet, and how many they kept apart. */
        val toReview: List<Review>,
        val keptApart: Int,
        val suggestions: List<LabDiagnosis.Suggestion>,
    )

    /** Pairs of alike texts the user chose to keep labeled apart: never asked about again. */
    private val reviewed by lazy { container.appContext.getSharedPreferences("lab_review", android.content.Context.MODE_PRIVATE) }

    private fun keptApart(): Set<String> = runCatching { reviewed.getStringSet(KEY_KEPT, null) }.getOrNull().orEmpty()

    /** The user keeps both labels of a pair (see [Review]): they're different to them. */
    suspend fun keepBoth(review: Review) = withContext(Dispatchers.IO) {
        runCatching { reviewed.edit().putStringSet(KEY_KEPT, keptApart() + review.id).apply() }
    }

    /**
     * The user picks [category] for [shown] (one of a pair they're reviewing): labeled as any
     * label they give is. False if the text is gone.
     */
    suspend fun relabel(shown: Shown, category: Category): Boolean = withContext(Dispatchers.IO) {
        val threadId = shown.threadId ?: return@withContext false
        val all = container.messages.messages(threadId).first()
        val message = all.firstOrNull { it.key == shown.key } ?: return@withContext false
        container.labeler.labelMessages(threadId, container.messages.recipientsFor(threadId), listOf(message), category, all)
        true
    }

    /**
     * What [eval]'s results say about how the model follows the user's labels (see LabDiagnosis):
     * [fitAccuracy] is how well it fits the labels it learned from, if known, and [serviceWeight]
     * how much the service's labels counted in it.
     */
    suspend fun diagnosis(eval: EvalEntity, fitAccuracy: Double? = null, serviceWeight: Double? = null): Diagnosis? = withContext(Dispatchers.IO) {
        val items = container.evalDao.items(eval.id)
        val scored = items.mapNotNull { i -> LabDiagnosis.Scored(i.messageKey, Category.fromKey(i.label) ?: return@mapNotNull null, Category.fromKey(i.predicted) ?: return@mapNotNull null, i.confidence) }
        if (scored.isEmpty()) return@withContext null
        // The user's own labels on texts still on the phone, to find alike ones labeled differently.
        val mine = container.correctionDao.all().filter { !it.fromProvider && it.messageKey != null }
        val texts = MessageTexts(container.appContext).of((mine.mapNotNull { it.messageKey } + items.map { it.messageKey }).distinct())
        val labeled = mine.mapNotNull { c ->
            val t = texts[c.messageKey!!] ?: return@mapNotNull null
            LabDiagnosis.LabeledText(c.messageKey, t.body, Category.fromKey(c.label) ?: return@mapNotNull null)
        }.distinctBy { it.key }
        val kept = keptApart()
        val apart = LabDiagnosis.labeledApart(labeled)
        val toReview = apart.filter { it.id !in kept }
        // How often the service's recorded answers agree with the user's labels, from its latest scoring.
        val agreement = container.evalDao.observeAll().first().filter { it.model.startsWith("provider") && it.dataset == EvalEntity.DATASET_MINE && it.examples >= 10 }
            .maxByOrNull { it.at }?.accuracy
        fun shown(key: String, label: Category, predicted: Category? = null, confidence: Double? = null): Shown {
            val t = texts[key]
            return Shown(key, t?.threadId ?: items.firstOrNull { it.messageKey == key }?.threadId, t?.address, t?.body, label, predicted, confidence)
        }
        Diagnosis(
            eval = eval,
            accuracy = scored.count { it.label == it.predicted }.toDouble() / scored.size,
            fitAccuracy = fitAccuracy,
            confusions = LabDiagnosis.confusions(scored),
            categories = LabDiagnosis.categories(scored),
            mistakes = LabDiagnosis.surestMistakes(scored).map { shown(it.key, it.label, it.predicted, it.confidence) },
            toReview = toReview.map { Review(it.id, shown(it.a.key, it.a.label), shown(it.b.key, it.b.label)) },
            keptApart = apart.count { it.id in kept },
            suggestions = LabDiagnosis.suggestions(scored, toReview.size, apart.count { it.id in kept }, fitAccuracy, serviceWeight, agreement),
        )
    }

    /** The whole result of [eval], for its charts. */
    fun metricsOf(eval: EvalEntity): ClassifierMetrics? =
        eval.metrics?.let { runCatching { json.decodeFromString(ClassifierMetrics.serializer(), it) }.getOrNull() }

    suspend fun delete(eval: EvalEntity) = withContext(Dispatchers.IO) {
        container.evalDao.deleteItems(eval.id)
        container.evalDao.delete(eval.id)
    }

    private fun subjectOf(pick: Pick, serviceName: String): EvalSubject? = when (pick) {
        Pick.Now -> EvalSubject.Now
        Pick.Shipped -> EvalSubject.Shipped
        Pick.YoursOnly -> EvalSubject.YoursOnly
        Pick.Service -> EvalSubject.Service(serviceName)
        is Pick.Weight -> EvalSubject.ServiceWeight(pick.weight)
        is Pick.Kept -> container.modelKeeper.load(pick.fit.fit)?.let { EvalSubject.Kept(pick.fit.fit, pick.fit.fittedAt, it, pick.fit.name) }
    }

    private suspend fun keep(at: Long, r: EvalResult) = withContext(Dispatchers.IO) {
        val m = r.metrics
        val id = container.evalDao.insert(
            EvalEntity(
                at = at,
                model = r.subject.key,
                label = r.subject.label,
                dataset = EvalEntity.DATASET_MINE,
                method = r.method,
                examples = r.items.size,
                // SQLite keeps no NaN: an undefined score is stored as 0 beside zero examples, or left out.
                accuracy = m?.accuracy?.finiteOr(0.0) ?: 0.0,
                macroF1 = m?.macroF1?.finiteOr(0.0) ?: 0.0,
                kappa = m?.kappa?.finiteOr(0.0) ?: 0.0,
                unwantedAuc = m?.unwanted?.auc?.takeIf { it.isFinite() },
                falsePositiveRate = m?.unwanted?.operatingPoint?.falsePositiveRate?.takeIf { it.isFinite() },
                metrics = m?.let { json.encodeToString(ClassifierMetrics.serializer(), it) },
                note = r.how,
            ),
        )
        container.evalDao.insertItems(r.items.map { EvalItemEntity(id, it.key, it.threadId, it.label.key, it.predicted.key, it.confidence) })
    }

    companion object {
        private const val KEY_KEPT = "kept_apart"
        /**
         * A result's metrics as kept: a score with nothing to measure (the AUC of a category with
         * no texts, a κ of perfect agreement by chance) is NaN, which plain JSON can't hold.
         */
        private val json = kotlinx.serialization.json.Json { allowSpecialFloatingPointValues = true; ignoreUnknownKeys = true }

        private fun Double.finiteOr(fallback: Double) = if (isFinite()) this else fallback

        /** A method, in a word or two, for a result's badge. */
        fun methodLabel(method: String): String = when (method) {
            EvalEntity.METHOD_CROSS_VALIDATED -> "cross-validated"
            EvalEntity.METHOD_SINCE -> "on labels made since"
            EvalEntity.METHOD_TRAINED_ON -> "on what it learned from"
            EvalEntity.METHOD_RECORDED -> "its recorded answers"
            Evaluator.METHOD_UNTAUGHT -> "never saw them"
            com.ericflo.winnow.classify.ExamplesExperiment.METHOD_ASKED -> "asked now"
            else -> method
        }

        /** Whether a method's score is a fair test (on texts the subject didn't learn from). */
        fun fair(method: String) = method != EvalEntity.METHOD_TRAINED_ON
    }
}
