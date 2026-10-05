package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.local.Featurizer
import com.ericflo.winnow.data.MessageTexts
import com.ericflo.winnow.data.ProviderKind
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.db.CorrectionDao
import com.ericflo.winnow.data.db.ModelFitDao
import com.ericflo.winnow.data.db.RunDao
import com.ericflo.winnow.data.db.VerdictDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gathers what's kept about one text's verdict and explains it (see [Provenance.explain]). */
class ProvenanceSource(
    private val verdicts: VerdictDao,
    private val corrections: CorrectionDao,
    private val runs: RunDao,
    private val fits: ModelFitDao,
    private val settings: SettingsRepository,
    private val texts: MessageTexts,
) {
    /** One text's explanation, with what it said; null if Winnow kept no verdict for it. */
    data class Explained(val key: String, val text: String?, val explanation: Explanation)

    suspend fun explain(key: String): Explained? = withContext(Dispatchers.IO) {
        val v = verdicts.forKey(key) ?: return@withContext null
        val taught = corrections.forMessages(listOf(key))
        val run = v.runId?.let { runs.get(it) }
        // A run that asked again about a text decided as it arrived taught the model, without deciding it.
        val teachingRun = taught.firstOrNull { it.fromProvider }?.runId?.takeIf { it > 0 && it != v.runId }?.let { runs.get(it) }
        val fit = (v.localModel ?: v.model)?.substringAfter('·', "")?.takeIf { it.isNotEmpty() }?.let { fits.get(it) }
        val text = texts.of(listOf(key))[key]
        // Whether it had anything a scammer could use: worked out again from its words.
        val hook = text?.let { Featurizer.hasHook(Featurizer.features(Featurizer.Input(v.address, it.body))) }
        val current = settings.current()
        Explained(
            key,
            text?.body,
            Provenance.explain(v, taught, run, fit, current.actionPolicy, ProviderKind::labelFor, current.learnFromProvider, hook, teachingRun, current.providerWeight),
        )
    }
}
