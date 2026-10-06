package com.ericflo.winnow.classify

import com.ericflo.winnow.data.db.ModelFitDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps fits of the on-device model (see ModelSnapshots): the one in use after a round of Train
 * Winnow and after a backlog run, and any the user keeps by hand, so each can be scored on the
 * labels made since, and the model's progress seen fit by fit. Unnamed ones beyond [MAX_KEPT]
 * go, oldest first; ones the user named stay.
 */
class ModelKeeper(
    private val learner: Learner,
    private val fits: ModelFitDao,
    private val snapshots: ModelSnapshots,
    /** The messages that have taught the model, by key (see CorrectionDao.taughtKeys). */
    private val taughtKeys: suspend () -> List<String> = { emptyList() },
) {
    /** Keeps the fit in use now, named [name] if given; false when there's nothing taught to keep. */
    suspend fun keepCurrent(name: String? = null): Boolean = withContext(Dispatchers.IO) {
        val model = learner.classifier()
        val fit = model.fit ?: return@withContext false
        if (!snapshots.has(fit) && !snapshots.save(fit, model.adjustments)) return@withContext false
        // What it learned from, so it's scored on texts it never saw: a text labeled again since is
        // newer by date, but not new to it.
        if (snapshots.loadKeys(fit) == null) runCatching { snapshots.saveKeys(fit, taughtKeys()) }
        fits.setKept(fit, true)
        if (name != null) fits.setName(fit, name)
        prune()
        true
    }

    suspend fun rename(fit: String, name: String?) = withContext(Dispatchers.IO) { fits.setName(fit, name?.trim()?.ifEmpty { null }) }

    /** Lets a kept fit go: its learned layer is deleted, its record stays in the history. */
    suspend fun forget(fit: String) = withContext(Dispatchers.IO) {
        snapshots.delete(fit)
        fits.setKept(fit, false)
        fits.setName(fit, null)
    }

    fun load(fit: String) = snapshots.load(fit)

    /** The texts [fit] learned from, by key; null for one kept before that was kept. */
    fun learnedKeys(fit: String) = snapshots.loadKeys(fit)

    private suspend fun prune() {
        val kept = fits.all().filter { it.kept }
        kept.filter { it.name == null }.sortedByDescending { it.fittedAt }.drop(MAX_KEPT).forEach { forget(it.fit) }
    }

    companion object {
        /** Unnamed kept fits, newest first, that stay. */
        const val MAX_KEPT = 30
    }
}
