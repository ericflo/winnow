package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.local.Correction
import com.ericflo.winnow.classifier.local.Featurizer
import com.ericflo.winnow.classifier.local.LocalModel
import com.ericflo.winnow.classifier.local.OnDeviceClassifier
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.db.CorrectionDao
import com.ericflo.winnow.data.db.CorrectionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Teaches the on-device model from the user's corrections. "Not spam" and "Filter sender"
 * record the corrected message's feature buckets (never its text) under the likeliest
 * category with the action the user chose. The model is then refit, so texts like it from
 * other senders follow. Sender rules already cover the sender themself.
 */
class Learner(
    private val dao: CorrectionDao,
    private val settings: SettingsRepository,
    /** Where the last fit is kept between runs of the app (see PersonalModelStore). */
    private val store: PersonalModelStore? = null,
    /** Where each fit is recorded, for the model's history (see ModelFitEntity). */
    private val fits: com.ericflo.winnow.data.db.ModelFitDao? = null,
    /** Where a refit after labels that come one at a time runs (see [learnFromAnswer]). */
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
) {
    private val base by lazy { OnDeviceClassifier() }
    private val lock = Mutex()
    @Volatile private var trained: OnDeviceClassifier? = null

    /** What the user taught it: their labels and corrections, not a classifier service's. */
    val count: Flow<Int> = dao.observeUserCount()

    /** Labels a classifier service gave the backlog (see Bootstrap). */
    val providerCount: Flow<Int> = dao.observeProviderCount()

    /**
     * Teaches [category] for each message in [examples] (by message key): their feature
     * buckets, never their text. It replaces whatever already taught the model about them: an
     * earlier label, the same label restored from a backup, and the conversation's own
     * correction when it contradicts this label. Retrains once, unless told not to (a round of
     * many labels retrains at the end). Returns every row it replaced, for an undo.
     */
    suspend fun label(threadId: Long, examples: Map<String, InboundMessage>, category: Category, retrain: Boolean = true): List<CorrectionEntity> {
        val now = System.currentTimeMillis()
        val rows = withContext(Dispatchers.Default) {
            examples.mapNotNull { (key, message) ->
                val correction = base.correction(message, setOf(category)) ?: return@mapNotNull null
                CorrectionEntity(
                    threadId = threadId,
                    buckets = correction.buckets.joinToString(","),
                    label = category.key,
                    featurizerVersion = Featurizer.VERSION,
                    createdAt = now,
                    messageKey = key,
                )
            }
        }
        val policy = settings.current().actionPolicy
        val replaced = lock.withLock {
            val before = dao.forMessages(examples.keys)
            val restored = dao.restoredFor(rows.map { it.buckets }.toSet())
            val contradicted = dao.forThread(threadId).filter { c ->
                Category.fromKey(c.label)?.let(policy::forCategory) != policy.forCategory(category)
            }
            dao.deleteForMessages(examples.keys)
            dao.deleteIds((restored + contradicted).map { it.id })
            dao.insertAll(rows)
            before + restored + contradicted
        }
        if (retrain) retrain()
        return replaced
    }

    /** Takes labels back: [keys]' labels go, and [restore]'s (what they replaced) come back. */
    suspend fun unlabel(keys: Collection<String>, restore: List<CorrectionEntity> = emptyList()) {
        lock.withLock {
            if (keys.isNotEmpty()) dao.deleteForMessages(keys)
            if (restore.isNotEmpty()) dao.insertAll(restore.map { it.copy(id = 0) })
        }
        retrain()
    }

    /**
     * Drops the labels on [keys] from what the model learned (a correction that contradicts
     * them replaces them), returning them so an undo can put them back.
     */
    suspend fun dropLabels(keys: Collection<String>): List<CorrectionEntity> {
        if (keys.isEmpty()) return emptyList()
        val dropped = lock.withLock { dao.forMessages(keys).also { dao.deleteForMessages(keys) } }
        if (dropped.isNotEmpty()) retrain()
        return dropped
    }

    /**
     * For a correction ("Not spam", "Filter sender") of [threadId] to [action]: drops the user's
     * labels on [keys] that contradict it, and every classifier-service label in the conversation
     * that does (several of those could otherwise outweigh the user's one correction). Returns
     * them all, so an undo puts them back as they were.
     */
    suspend fun dropForCorrection(threadId: Long, keys: Collection<String>, action: Action): List<CorrectionEntity> {
        val policy = settings.current().actionPolicy
        val dropped = lock.withLock {
            val provider = dao.providerForThread(threadId).filter { c -> Category.fromKey(c.label)?.let(policy::forCategory) != action }
            val mine = if (keys.isEmpty()) emptyList() else dao.forMessages(keys).filterNot { it.fromProvider }
            val all = mine + provider
            dao.deleteIds(all.map { it.id })
            all
        }
        if (dropped.isNotEmpty()) retrain()
        return dropped
    }

    /** The on-device classifier with everything the user has taught it. */
    suspend fun classifier(): OnDeviceClassifier = trained ?: retrain()

    suspend fun learn(threadId: Long, message: InboundMessage, action: Action) {
        val policy = settings.current().actionPolicy
        val acceptable = Category.entries.filter { policy.forCategory(it) == action }.toSet()
        val correction = withContext(Dispatchers.Default) { base.correction(message, acceptable) } ?: return
        lock.withLock {
            dao.deleteForThread(threadId)
            dao.insert(
                CorrectionEntity(
                    threadId = threadId,
                    buckets = correction.buckets.joinToString(","),
                    label = LocalModel.bundled.classes[correction.label],
                    featurizerVersion = Featurizer.VERSION,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        retrain()
    }

    /** Drops what was learned from one conversation, when its correction is undone. */
    suspend fun unlearn(threadId: Long) {
        lock.withLock { dao.deleteForThread(threadId) }
        retrain()
    }

    suspend fun forget() {
        dao.deleteAll()
        retrain()
    }

    /** Called after a restore adds corrections, and after a round of labels. */
    suspend fun reload() {
        retrain()
    }

    /**
     * The model as it would be with [extra] taught on top of everything saved, without saving
     * anything: Train Winnow re-guesses a round's open conversations from the answers given so
     * far, so fixing one text fixes the guesses on texts like it straight away.
     */
    suspend fun preview(extra: List<Pair<InboundMessage, Category>>): OnDeviceClassifier {
        // Everything saved, as fitted (or kept from the last fit), and the answers taught on top:
        // a few answers' worth of fitting each time, not every label's (thousands, after a
        // backlog run), which on a phone took seconds an answer.
        val current = classifier()
        return withContext(Dispatchers.Default) {
            val more = extra.mapNotNull { (message, category) -> base.correction(message, setOf(category)) }
            val job = currentCoroutineContext()
            // A newer answer cancels this preview; the fit stops then rather than running on.
            try {
                current.learnMore(more, stopped = { !job.isActive })
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                current
            }
        }
    }

    private fun corrections(rows: List<CorrectionEntity>): List<Correction> {
        val classes = LocalModel.bundled.classes
        return rows.mapNotNull { e ->
            val label = classes.indexOf(e.label).takeIf { it >= 0 && e.featurizerVersion == Featurizer.VERSION } ?: return@mapNotNull null
            Correction(e.buckets.split(',').mapNotNull(String::toIntOrNull).toIntArray(), label, if (e.fromProvider) PROVIDER_WEIGHT else 1.0)
        }
    }

    /**
     * Labels a classifier service gave the backlog (see Bootstrap): [rows], already featurized,
     * each replacing an earlier provider label on its message but never the user's. Retrains
     * only when asked: a run adds them in batches and retrains as it goes.
     */
    suspend fun teach(rows: List<CorrectionEntity>, retrain: Boolean) {
        if (rows.isEmpty()) return
        lock.withLock {
            val mine = dao.forMessages(rows.mapNotNull { it.messageKey }).filterNot { it.fromProvider }.mapNotNullTo(HashSet()) { it.messageKey }
            val fresh = rows.filter { it.messageKey !in mine }
            dao.deleteForMessages(fresh.mapNotNull { it.messageKey })
            dao.insertAll(fresh)
        }
        if (retrain) retrain()
    }

    /**
     * A classifier service's answer about [message] as it arrived, taught to the model the way a
     * backlog run's are (see [teach]): its feature buckets, never its text, counting for
     * [PROVIDER_WEIGHT] of one of the user's labels, and never over the user's own. The refit
     * waits a little, so a burst of texts makes one. False if there was nothing to learn.
     */
    suspend fun learnFromAnswer(threadId: Long, key: String, message: InboundMessage, category: Category): Boolean {
        val correction = withContext(Dispatchers.Default) { base.correction(message, setOf(category)) } ?: return false
        teach(
            listOf(
                CorrectionEntity(
                    threadId = threadId,
                    buckets = correction.buckets.joinToString(","),
                    label = category.key,
                    featurizerVersion = Featurizer.VERSION,
                    createdAt = System.currentTimeMillis(),
                    messageKey = key,
                    source = CorrectionEntity.SOURCE_PROVIDER,
                    runId = null,
                ),
            ),
            retrain = false,
        )
        refitSoon()
        return true
    }

    private var pendingRefit: kotlinx.coroutines.Job? = null

    /** A refit in a little while, replacing one already waiting. */
    private fun refitSoon() {
        val s = scope ?: return
        synchronized(this) {
            pendingRefit?.cancel()
            pendingRefit = s.launch {
                kotlinx.coroutines.delay(REFIT_AFTER_MILLIS)
                retrain()
            }
        }
    }

    /** Forgets what a classifier service taught, keeping the user's own labels and corrections. */
    suspend fun forgetProviderLabels() {
        lock.withLock { dao.deleteProviderLabels() }
        retrain()
    }

    private suspend fun retrain(): OnDeviceClassifier = lock.withLock {
        val rows = dao.all()
        withContext(Dispatchers.Default) {
            val stamp = store?.stamp(rows) ?: PersonalModelStore.stampOf(rows, 0)
            // Nothing taught: the model as it ships, which has no fit to name.
            val fit = if (rows.isEmpty()) null else fitName(stamp)
            // The last fit, if nothing that went into it has changed: no fitting on every start.
            val kept = store?.let { withContext(Dispatchers.IO) { it.load(stamp) } }
            // Loading the model happens here too, off the main thread.
            if (kept != null) {
                if (fit != null) record(fit, rows, kept.size, millis = 0, onlyIfNew = true)
                return@withContext base.withAdjustments(kept, fit)
            }
            val started = System.nanoTime()
            // A bad correction must never stop classification: fall back to the bundled model.
            val fitted = runCatching { base.learn(corrections(rows)) }.getOrNull()?.let { it.withAdjustments(it.adjustments, fit) }
            if (fitted != null) {
                withContext(Dispatchers.IO) { store?.save(stamp, fitted.adjustments) }
                if (fit != null) record(fit, rows, fitted.adjustments.size, millis = (System.nanoTime() - started) / 1_000_000, onlyIfNew = false)
            }
            fitted ?: base
        }.also { trained = it }
    }

    /** Records a fit for the model's history; a failure to is never a failure to classify. */
    private suspend fun record(fit: String, rows: List<CorrectionEntity>, buckets: Int, millis: Long, onlyIfNew: Boolean) {
        val dao = fits ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                if (onlyIfNew && dao.get(fit) != null) return@withContext
                dao.upsert(fitRecord(fit, rows, buckets, millis, System.currentTimeMillis()))
                dao.prune(FITS_KEPT)
            }
        }
    }

    companion object {
        /**
         * How much a classifier service's label counts against the user's (1): enough to teach
         * the model the backlog, little enough that one of the user's outweighs several of its.
         */
        const val PROVIDER_WEIGHT = 0.35

        /** How long a label learned as a text arrived waits for others before the model is refit. */
        const val REFIT_AFTER_MILLIS = 20_000L

        /** Below this, a classifier service's answer is a guess, and isn't taught (as in a backlog run). */
        const val MIN_TEACH_CONFIDENCE = 0.7

        /** Fits recorded for the model's history, newest first, besides any kept or named. */
        const val FITS_KEPT = 500

        /** A fit's name, from the stamp of what it learned (see PersonalModelStore): six hex digits. */
        fun fitName(stamp: Long): String = java.lang.Long.toHexString(stamp).padStart(16, '0').takeLast(6)

        /** What a fit of [rows] learned from, as its history records it. Pure, so it's unit-tested. */
        fun fitRecord(fit: String, rows: List<CorrectionEntity>, buckets: Int, millis: Long, at: Long) = com.ericflo.winnow.data.db.ModelFitEntity(
            fit = fit,
            fittedAt = at,
            // A restored label has lost its message and conversation, but it's still a label.
            userLabels = rows.count { !it.fromProvider && (it.messageKey != null || it.threadId == null) },
            corrections = rows.count { !it.fromProvider && it.messageKey == null && it.threadId != null },
            providerLabels = rows.count { it.fromProvider && it.runId != null },
            providerLive = rows.count { it.fromProvider && it.runId == null },
            buckets = buckets,
            millis = millis,
            providerWeight = PROVIDER_WEIGHT,
            epochs = com.ericflo.winnow.classifier.local.Personalizer.EPOCHS,
            l2 = com.ericflo.winnow.classifier.local.Personalizer.L2,
        )
    }
}
