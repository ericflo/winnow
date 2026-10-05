package com.ericflo.winnow.ui.model

import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.local.ClassifierMetrics
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classify.CurvePoint
import com.ericflo.winnow.classify.EvalData
import com.ericflo.winnow.classify.EvalResult
import com.ericflo.winnow.classify.EvalSubject
import com.ericflo.winnow.classify.Evaluator
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

    /** Scores [picks] on the user's labels, one after another, keeping each as it's done. */
    fun run(picks: List<Pick>, serviceName: String) {
        if (job?.isActive == true || picks.isEmpty()) return
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
            else -> method
        }

        /** Whether a method's score is a fair test (on texts the subject didn't learn from). */
        fun fair(method: String) = method != EvalEntity.METHOD_TRAINED_ON
    }
}
