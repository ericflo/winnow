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

    suspend fun forget() {
        dao.deleteAll()
        retrain()
    }

    /** Called after a restore adds corrections. */
    suspend fun reload() {
        retrain()
    }

    private suspend fun retrain(): OnDeviceClassifier = lock.withLock {
        val classes = LocalModel.bundled.classes
        val corrections = dao.all().mapNotNull { e ->
            val label = classes.indexOf(e.label).takeIf { it >= 0 && e.featurizerVersion == Featurizer.VERSION } ?: return@mapNotNull null
            Correction(e.buckets.split(',').mapNotNull(String::toIntOrNull).toIntArray(), label)
        }
        withContext(Dispatchers.Default) { base.learn(corrections) }.also { trained = it }
    }
}
