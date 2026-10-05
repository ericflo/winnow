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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Teaches the on-device model from the user's corrections. "Not spam" and "Filter sender"
 * record the corrected message's feature buckets (never its text) under the likeliest
 * category with the action the user chose. The model is then refit, so texts like it from
 * other senders follow. Sender rules already cover the sender themself.
 */
class Learner(private val dao: CorrectionDao, private val settings: SettingsRepository) {
    private val base by lazy { OnDeviceClassifier() }
    private val lock = Mutex()
    @Volatile private var trained: OnDeviceClassifier? = null

    val count: Flow<Int> = dao.observeCount()

    /** How many messages the user has labeled (see Labeler). */
    val labelCount: Flow<Int> = dao.observeLabelCount()

    /**
     * Teaches [category] for each message in [examples] (by message key): their feature
     * buckets, never their text, replacing any label they had. Retrains once, unless told not
     * to (a round of many labels retrains at the end). Returns the label rows it replaced.
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
        val replaced = lock.withLock {
            val before = dao.forMessages(examples.keys)
            dao.deleteForMessages(examples.keys)
            dao.insertAll(rows)
            before
        }
        if (retrain) retrain()
        return replaced
    }

    /** Takes labels back: [keys]' labels go, and [restore]'s (what they replaced) come back. */
    suspend fun unlabel(keys: Collection<String>, restore: List<CorrectionEntity> = emptyList()) {
        lock.withLock {
            dao.deleteForMessages(keys)
            if (restore.isNotEmpty()) dao.insertAll(restore.map { it.copy(id = 0) })
        }
        retrain()
    }

    /** Conversations the user has labeled. */
    suspend fun labeledThreads(): Set<Long> = dao.labeledThreads().toSet()

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

    private suspend fun retrain(): OnDeviceClassifier = lock.withLock {
        val rows = dao.all()
        withContext(Dispatchers.Default) {
            // Loading the model happens here too, off the main thread.
            val classes = LocalModel.bundled.classes
            val corrections = rows.mapNotNull { e ->
                val label = classes.indexOf(e.label).takeIf { it >= 0 && e.featurizerVersion == Featurizer.VERSION } ?: return@mapNotNull null
                Correction(e.buckets.split(',').mapNotNull(String::toIntOrNull).toIntArray(), label)
            }
            // A bad correction must never stop classification: fall back to the bundled model.
            runCatching { base.learn(corrections) }.getOrElse { base }
        }.also { trained = it }
    }
}
