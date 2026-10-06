package com.ericflo.winnow.classify

import android.content.Context
import android.provider.Telephony
import com.ericflo.winnow.classifier.local.ClassifierMetrics
import com.ericflo.winnow.classifier.local.LocalModel
import com.ericflo.winnow.classifier.local.MetricsCalculator
import com.ericflo.winnow.classifier.local.Scored
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.ActionPolicy
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classifier.message.MessageClassifier
import com.ericflo.winnow.classifier.message.Verdict
import com.ericflo.winnow.classifier.message.VerdictSource
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.db.CorrectionDao
import com.ericflo.winnow.data.db.EvalDao
import com.ericflo.winnow.data.db.EvalEntity
import com.ericflo.winnow.data.db.EvalItemEntity
import com.ericflo.winnow.data.db.VerdictDao
import com.ericflo.winnow.data.normalizeAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One labeled text asked about twice: the user's label, and the service's answer plainly and with examples. */
data class Trial(
    val key: String,
    val threadId: Long,
    val label: Category,
    val plain: Category?,
    val plainSure: Double,
    val withExamples: Category?,
    val withSure: Double,
)

/** What an experiment found (see [ExamplesExperiment]). Pure, so it's unit-tested. */
data class ExperimentSummary(
    /** Texts answered both ways. */
    val trials: Int,
    val plainRight: Int,
    val withRight: Int,
    /** Answers that differed between the two. */
    val changed: Int,
    /** Wrong plainly, right with examples; and the other way. */
    val toward: Int,
    val away: Int,
) {
    companion object {
        fun of(trials: List<Trial>): ExperimentSummary {
            val both = trials.filter { it.plain != null && it.withExamples != null }
            return ExperimentSummary(
                trials = both.size,
                plainRight = both.count { it.plain == it.label },
                withRight = both.count { it.withExamples == it.label },
                changed = both.count { it.plain != it.withExamples },
                toward = both.count { it.plain != it.label && it.withExamples == it.label },
                away = both.count { it.plain == it.label && it.withExamples != it.label },
            )
        }
    }
}

sealed interface ExperimentStatus {
    data object Idle : ExperimentStatus
    data class Running(val done: Int, val total: Int, val costUsd: Double, val waiting: String? = null) : ExperimentStatus
    data class Finished(val summary: ExperimentSummary, val costUsd: Double, val stopped: Boolean, val error: String? = null, val at: Long) : ExperimentStatus
}

/**
 * The honest test of "do my labels make the service better?": its answers on the user's own
 * labeled texts, asked twice: once with the plain question (as texts arrive), once with the
 * user's labels as examples (as in a backlog run), never with an example from the text's own
 * conversation. Only texts the pipeline would send anyway, redacted the same way; only when the
 * user starts it, after being told how many and what it costs. Both sets of answers are kept as
 * evaluations, so they're scored and compared like everything else.
 */
class ExamplesExperiment(
    private val context: Context,
    private val scope: CoroutineScope,
    private val verdicts: VerdictDao,
    private val corrections: CorrectionDao,
    private val contacts: ContactLookup,
    private val settings: SettingsRepository,
    private val classifiers: ClassifierFactory,
    private val bootstrap: Bootstrap,
    private val evals: EvalDao,
    /** Told as a test starts, to keep it going when the user leaves Winnow (see WorkService). */
    private val onRunStarted: () -> Unit = {},
) {
    private val _status = MutableStateFlow<ExperimentStatus>(ExperimentStatus.Idle)
    val status: StateFlow<ExperimentStatus> = _status.asStateFlow()
    private var job: Job? = null

    private class Candidate(val key: String, val threadId: Long, val label: Category, val message: InboundMessage)

    /** How many of the user's labeled texts could be asked about, and how many examples go with each. */
    data class Plan(val available: Int, val examples: Int, val unavailable: String?)

    suspend fun plan(): Plan {
        val current = settings.current()
        return Plan(candidates(current).size, bootstrap.exampleTexts(current).size, bootstrap.unavailable(current))
    }

    fun start(limit: Int) {
        if (job?.isActive == true) return
        // Running before anything watches for it: the service keeping it going stops when it isn't.
        _status.value = ExperimentStatus.Running(0, 0, 0.0)
        onRunStarted()
        job = scope.launch {
            try {
                val current = settings.current()
                if (bootstrap.unavailable(current) != null) return@launch
                val chosen = spread(candidates(current), limit)
                val pool = bootstrap.exampleTexts(current)
                val at = System.currentTimeMillis()
                var cost = 0.0
                // Each way of asking's own cost: the questions with examples are much longer.
                var costPlain = 0.0
                val trials = mutableListOf<Trial>()
                _status.value = ExperimentStatus.Running(0, chosen.size, 0.0)
                val pacer = Pacer(maxConcurrency = CONCURRENCY)
                // The service's own answers, both ways: the user's labels of a sender mustn't answer for it.
                val plain = classifiers.create(current.copy(decideOnPhoneWhenSure = false), timeoutMillis = TIMEOUT_MILLIS, serviceOnly = true)
                // The examples for a text: all but its own conversation's, and any that is the text
                // itself, labeled in another conversation (the same spam sent twice would give it away).
                fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
                suspend fun classifierFor(threadId: Long, body: String): MessageClassifier = classifiers.create(
                    current.copy(decideOnPhoneWhenSure = false), timeoutMillis = TIMEOUT_MILLIS,
                    examples = pool.filter { it.threadId != threadId && !same(it.body, body) }.groupBy({ it.category }, { it.body }),
                    serviceOnly = true,
                )
                var error: String? = null
                var stopped = false
                try {
                    val queue = ArrayDeque(chosen)
                    // Texts asked both ways so far, counted as each is, not only as a batch ends.
                    val done = java.util.concurrent.atomic.AtomicInteger(0)
                    while (queue.isNotEmpty()) {
                        val batch = List(minOf(BATCH, queue.size)) { queue.removeFirst() }
                        val gate = Semaphore(pacer.concurrency)
                        val asked = batch.map { c ->
                            async(Dispatchers.IO) {
                                gate.withPermit {
                                    val a = runCatching { plain.classify(c.message) }.getOrNull()
                                    val b = runCatching { classifierFor(c.threadId, c.message.body).classify(c.message) }.getOrNull()
                                    val n = done.incrementAndGet()
                                    _status.update { if (it is ExperimentStatus.Running) it.copy(done = maxOf(it.done, n), waiting = null) else it }
                                    Triple(c, a, b)
                                }
                            }
                        }.awaitAll()
                        val troubles = mutableListOf<Pacer.Trouble>()
                        var answered = 0
                        for ((c, a, b) in asked) {
                            cost += (a?.costUsd ?: 0.0) + (b?.costUsd ?: 0.0)
                            costPlain += a?.costUsd ?: 0.0
                            val pa = a.answer()
                            val pb = b.answer()
                            if (pa != null && pb != null) {
                                answered++
                                trials += Trial(c.key, c.threadId, c.label, pa.category, pa.confidence, pb.category, pb.confidence)
                            } else {
                                listOfNotNull(a, b).filter { it.answer() == null }.forEach { v ->
                                    // Asked without the on-device model, a failure comes back as the keyword fallback, with the reason.
                                    val why = (v.source as? VerdictSource.OnDevice)?.fallbackReason ?: (v.source as? VerdictSource.Heuristic)?.reason
                                    troubles += Pacer.troubleOf(why ?: "no answer")
                                }
                            }
                        }
                        _status.value = ExperimentStatus.Running(chosen.size - queue.size, chosen.size, cost)
                        when (val next = pacer.after(answered, troubles, System.currentTimeMillis())) {
                            Pacer.Next.Go -> Unit
                            is Pacer.Next.Wait -> {
                                _status.value = ExperimentStatus.Running(chosen.size - queue.size, chosen.size, cost, waiting = Bootstrap.pauseText(current.provider.label, next.trouble, next.millis))
                                kotlinx.coroutines.delay(next.millis)
                            }
                            is Pacer.Next.GiveUp -> {
                                error = "${current.provider.label} stopped answering, so the test stopped there."
                                break
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    stopped = true
                    throw e
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (trials.isNotEmpty()) runCatching { keep(at, trials, costPlain, cost - costPlain, current.provider.label.substringBefore(" (")) }
                        _status.value = ExperimentStatus.Finished(ExperimentSummary.of(trials), cost, stopped, error, at)
                    }
                }
            } finally {
                // Ended before it began (no service to ask): nothing to report, and nothing running.
                if (_status.value is ExperimentStatus.Running) _status.value = ExperimentStatus.Idle
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun dismiss() {
        if (job?.isActive != true) _status.value = ExperimentStatus.Idle
    }

    private fun Verdict?.answer(): Verdict? = this?.takeIf { it.source is VerdictSource.Provider && it.category != null }

    /** The user's labeled texts the pipeline would send, newest first. */
    private suspend fun candidates(current: com.ericflo.winnow.data.WinnowSettings): List<Candidate> = withContext(Dispatchers.IO) {
        val mine = Bootstrap.exampleLabels(corrections.all(), verdicts.recheckThreads().toSet())
        if (mine.isEmpty()) return@withContext emptyList()
        class Sms(val address: String, val body: String, val threadId: Long)
        val sms = HashMap<Long, Sms>()
        mine.map { it.first }.distinct().chunked(500).forEach { ids ->
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.THREAD_ID),
                "${Telephony.Sms._ID} IN (${ids.joinToString(",")}) AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}", null, null,
            )?.use { c -> while (c.moveToNext()) sms[c.getLong(0)] = Sms(c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3)) }
        }
        val replied = bootstrap.threadsWithOutgoing()
        val rules = bootstrap.senderRules()
        val gate = classifiers.create(current)
        val seen = HashSet<String>()
        mine.mapNotNull { (id, label) ->
            val m = sms[id] ?: return@mapNotNull null
            val category = Category.fromKey(label) ?: return@mapNotNull null
            if (m.body.isBlank() || !seen.add(m.body)) return@mapNotNull null
            val message = InboundMessage(
                sender = m.address, body = m.body, senderInContacts = contacts.isContact(m.address),
                userHasMessagedSender = m.threadId in replied, senderRule = rules[normalizeAddress(m.address)],
            )
            if (gate.staysOnPhone(message)) null else Candidate("sms:$id", m.threadId, category, message)
        }
    }

    /** Up to [limit], taken a category at a time in turn, so a test of 20 isn't 20 of one kind. */
    private fun spread(all: List<Candidate>, limit: Int): List<Candidate> {
        val queues = all.groupBy { it.label }.values.map { ArrayDeque(it) }
        val out = mutableListOf<Candidate>()
        while (out.size < limit && queues.any { it.isNotEmpty() }) queues.forEach { q -> if (out.size < limit) q.removeFirstOrNull()?.let(out::add) }
        return out
    }

    /** Both sets of answers kept as evaluations of the service, at the same time, so they show side by side. */
    private suspend fun keep(at: Long, trials: List<Trial>, costPlain: Double, costExamples: Double, service: String) = withContext(Dispatchers.IO) {
        val classes = LocalModel.bundled.classes
        val unwanted = Category.entries.filter { it.defaultAction == Action.FILTER }.map { classes.indexOf(it.key) }.filter { it >= 0 }.toSet()
        fun scored(c: Category, sure: Double, label: Category): Scored {
            val k = classes.size
            val at = classes.indexOf(c.key)
            val rest = ((1 - sure) / (k - 1)).coerceAtLeast(0.0)
            return Scored(classes.indexOf(label.key), DoubleArray(k) { if (it == at) sure else rest })
        }
        for ((model, label, pick) in listOf(
            Triple(MODEL_PLAIN, "$service, asked plainly", { t: Trial -> t.plain!! to t.plainSure }),
            Triple(MODEL_EXAMPLES, "$service, with your labels as examples", { t: Trial -> t.withExamples!! to t.withSure }),
        )) {
            val rows = trials.map { t -> pick(t).let { (c, s) -> scored(c, s, t.label) } }
            val m: ClassifierMetrics = MetricsCalculator.compute(label, "Asked now about your labeled texts", classes, rows, unwanted, ActionPolicy().minConfidence)
            val id = evals.insert(
                EvalEntity(
                    at = at, model = model, label = label, dataset = EvalEntity.DATASET_MINE, method = METHOD_ASKED,
                    examples = rows.size, accuracy = m.accuracy.finite(), macroF1 = m.macroF1.finite(), kappa = m.kappa.finite(),
                    unwantedAuc = m.unwanted.auc.takeIf { it.isFinite() }, falsePositiveRate = m.unwanted.operatingPoint.falsePositiveRate.takeIf { it.isFinite() },
                    costUsd = if (model == MODEL_PLAIN) costPlain else costExamples, metrics = null,
                    note = "Asked now, ${rows.size} of your labeled texts, " +
                        if (model == MODEL_PLAIN) "with the plain question it gets as texts arrive." else "each with your labels as examples, never one from its own conversation or the same text.",
                ),
            )
            evals.insertItems(trials.map { t -> pick(t).let { (c, s) -> EvalItemEntity(id, t.key, t.threadId, t.label.key, c.key, s) } })
        }
    }

    private fun Double.finite() = if (isFinite()) this else 0.0

    companion object {
        const val MODEL_PLAIN = "provider:plain"
        const val MODEL_EXAMPLES = "provider:examples"
        const val METHOD_ASKED = "asked"
        private const val CONCURRENCY = 3
        private const val BATCH = 12
        private const val TIMEOUT_MILLIS = 15_000L

        /** The trials of a kept experiment: its two evaluations' items, side by side. Pure, so it's unit-tested. */
        fun trialsOf(plain: List<EvalItemEntity>, withExamples: List<EvalItemEntity>): List<Trial> {
            val other = withExamples.associateBy { it.messageKey }
            return plain.mapNotNull { p ->
                val w = other[p.messageKey] ?: return@mapNotNull null
                val label = Category.fromKey(p.label) ?: return@mapNotNull null
                Trial(p.messageKey, p.threadId ?: 0, label, Category.fromKey(p.predicted), p.confidence, Category.fromKey(w.predicted), w.confidence)
            }
        }
    }
}
