package com.ericflo.winnow.classify

import android.content.Context
import android.provider.Telephony
import com.ericflo.winnow.classifier.DataHandling
import com.ericflo.winnow.classifier.local.Featurizer
import com.ericflo.winnow.classifier.local.OnDeviceClassifier
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classifier.message.Verdict
import com.ericflo.winnow.classifier.message.VerdictSource
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.ProviderKind
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.WinnowSettings
import com.ericflo.winnow.data.db.CorrectionDao
import com.ericflo.winnow.data.db.CorrectionEntity
import com.ericflo.winnow.data.db.VerdictDao
import com.ericflo.winnow.data.db.VerdictEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface BootstrapStatus {
    data object Idle : BootstrapStatus

    data class Running(val done: Int, val total: Int, val tally: Bootstrap.Tally) : BootstrapStatus

    data class Finished(val total: Int, val tally: Bootstrap.Tally, val stopped: Boolean, val error: String? = null) : BootstrapStatus
}

/**
 * Teaches the on-device model the user's backlog with the classifier service they chose (Jev,
 * say), so the model knows their traffic before they've labeled much, and Train Winnow is left
 * the conversations it still can't settle.
 *
 * The newest few received texts of each conversation with someone who isn't a contact go to the
 * provider one by one, through the same pipeline as a new text: the same privacy rules (contacts,
 * people the user has written to, codes and sender rules never leave the phone) and the same
 * redaction. Each answer becomes a label (feature buckets and a category, never the text) that
 * counts for less than the user's own, and a text Winnow never filed is filed by it. It only
 * runs with a provider marked zero data retention (which OpenRouter is asked to enforce), only
 * when the user starts it, and can be stopped; a later run picks up where it left off.
 */
class Bootstrap(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: MessageRepository,
    private val verdicts: VerdictDao,
    private val corrections: CorrectionDao,
    private val learner: Learner,
    private val contacts: ContactLookup,
    private val settings: SettingsRepository,
    private val classifiers: ClassifierFactory,
    /** Told when a run ends, so counts elsewhere (older conversations to review) catch up. */
    private val onFinished: () -> Unit = {},
) {
    /** What a run would send: [texts] from [conversations] conversations. */
    data class Plan(val conversations: Int, val texts: Int)

    /** How a run went: texts [labeled], [kept] on the phone by a privacy rule, [failed] (tried again next time), what it cost. */
    data class Tally(val labeled: Int = 0, val kept: Int = 0, val failed: Int = 0, val costUsd: Double = 0.0)

    private class Text(
        val key: String,
        val threadId: Long,
        val sender: String,
        val body: String,
        val repliedTo: Boolean,
        val senderRule: com.ericflo.winnow.classifier.message.SenderRule? = null,
    )

    private val _status = MutableStateFlow<BootstrapStatus>(BootstrapStatus.Idle)
    val status: StateFlow<BootstrapStatus> = _status.asStateFlow()

    /** Labels a provider has given so far, over every run. */
    val taught: Flow<Int> = corrections.observeProviderCount()

    private var job: Job? = null
    private val base by lazy { OnDeviceClassifier() }

    /** Why a run can't start with these [settings], in words; null when it can. */
    fun unavailable(settings: WinnowSettings): String? {
        val kind = settings.provider
        if (kind == ProviderKind.ON_DEVICE) return "Choose a classifier service in Settings first: this sends texts to it."
        if (classifiers.provider(settings) == null) return "Finish setting up ${kind.label} in Settings first."
        if (!settings.settingsFor(kind).zeroRetention) return "Turn on zero data retention for ${kind.label} in Settings first."
        if (DataHandling.REMOTE_ZERO_RETENTION !in settings.effectivePrivacy.allowedDataHandling) return "Your privacy settings don't allow sending texts to ${kind.label}."
        return null
    }

    suspend fun plan(): Plan {
        val texts = candidates(settings.current())
        return Plan(texts.map { it.threadId }.distinct().size, texts.size)
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val current = settings.current()
            if (unavailable(current) != null) return@launch
            val texts = candidates(current)
            var tally = Tally()
            var done = 0
            _status.value = BootstrapStatus.Running(0, texts.size, tally)
            // The provider decides every text: no deciding on the phone when sure, a generous wait.
            val classifier = classifiers.create(current.copy(decideOnPhoneWhenSure = false), timeoutMillis = PROVIDER_TIMEOUT_MILLIS)
            val gate = Semaphore(CONCURRENCY)
            var failuresInARow = 0
            var error: String? = null
            try {
                for (batch in texts.chunked(BATCH)) {
                    val results = batch.map { t -> async(Dispatchers.IO) { gate.withPermit { t to runCatching { classifier.classify(t.message()) }.getOrNull() } } }.awaitAll()
                    val labels = mutableListOf<CorrectionEntity>()
                    val filed = mutableListOf<VerdictEntity>()
                    val now = System.currentTimeMillis()
                    for ((t, verdict) in results) {
                        val answer = verdict?.takeIf { it.source is VerdictSource.Provider && it.category != null }
                        when {
                            answer != null -> {
                                failuresInARow = 0
                                tally = tally.copy(labeled = tally.labeled + 1, costUsd = tally.costUsd + answer.costUsd)
                                label(t, answer, now)?.let { labels += it }
                                filed += VerdictEntity.from(t.key, t.threadId, t.sender, answer, now).copy(summarized = true)
                            }
                            verdict?.source is VerdictSource.Rule -> tally = tally.copy(kept = tally.kept + 1)
                            else -> {
                                failuresInARow++
                                tally = tally.copy(failed = tally.failed + 1, costUsd = tally.costUsd + (verdict?.costUsd ?: 0.0))
                                error = (verdict?.source as? VerdictSource.OnDevice)?.fallbackReason ?: "no answer"
                            }
                        }
                    }
                    save(labels, filed, retrain = (done / BATCH) % RETRAIN_EVERY_BATCHES == RETRAIN_EVERY_BATCHES - 1)
                    done += batch.size
                    _status.value = BootstrapStatus.Running(done, texts.size, tally)
                    if (failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                        _status.value = BootstrapStatus.Finished(texts.size, tally, stopped = true, error = "${current.provider.label} stopped answering ($error). Try again later.")
                        return@launch
                    }
                }
                _status.value = BootstrapStatus.Finished(texts.size, tally, stopped = false)
            } catch (e: CancellationException) {
                _status.value = BootstrapStatus.Finished(texts.size, tally, stopped = true)
                throw e
            } finally {
                // Whatever was learned is in the model, however the run ended.
                withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { learner.reload() }
                    onFinished()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** Back to idle once the user has seen how a run ended. */
    fun dismiss() {
        if (job?.isActive != true) _status.value = BootstrapStatus.Idle
    }

    private fun label(t: Text, verdict: Verdict, now: Long): CorrectionEntity? {
        // An unsure answer would teach the model a guess.
        if (verdict.confidence < MIN_CONFIDENCE) return null
        val correction = base.correction(t.message(), setOf(verdict.category ?: return null)) ?: return null
        return CorrectionEntity(
            threadId = t.threadId,
            buckets = correction.buckets.joinToString(","),
            label = verdict.category!!.key,
            featurizerVersion = Featurizer.VERSION,
            createdAt = now,
            messageKey = t.key,
            source = CorrectionEntity.SOURCE_PROVIDER,
        )
    }

    private suspend fun save(labels: List<CorrectionEntity>, filed: List<VerdictEntity>, retrain: Boolean) {
        learner.teach(labels, retrain)
        // Filed only where Winnow had no verdict: never over one it made as the text arrived, or the user's.
        val had = verdicts.existingKeys(filed.map { it.messageKey }).toSet()
        filed.filterNot { it.messageKey in had }.forEach { verdicts.upsert(it) }
    }

    /**
     * The texts a run sends, newest conversations first: up to [PER_CONVERSATION] of each one's
     * newest received texts, from people who aren't contacts, in conversations of two the user
     * hasn't labeled or corrected, skipping texts anything has taught the model already. People
     * the user has written to, and verification codes, are left out unless their privacy settings
     * send those.
     */
    private suspend fun candidates(current: WinnowSettings): List<Text> = withContext(Dispatchers.IO) {
        val conversations = repo.conversations().first().filter { !it.isGroup && !contacts.isContact(it.address) }
        val judged = verdicts.judgedThreads().toSet()
        val wanted = conversations.filter { it.threadId !in judged }.associateBy { it.threadId }
        val replied = threadsWithOutgoing()
        val knownAllowed = current.effectivePrivacy.classifyKnownConversations
        // Codes stay on the phone unless the privacy settings send them: not planned, never sent.
        val codesAllowed = current.effectivePrivacy.classifyVerificationCodes
        val taught = corrections.taughtKeys().toHashSet()
        val byThread = HashMap<Long, MutableList<Text>>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}", null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val threadId = c.getLong(1)
                if (threadId !in wanted || (threadId in replied && !knownAllowed)) continue
                val list = byThread.getOrPut(threadId) { mutableListOf() }
                if (list.size >= PER_CONVERSATION) continue
                val body = c.getString(3).orEmpty()
                if (body.isBlank() || (!codesAllowed && com.ericflo.winnow.classifier.message.VerificationCodes.find(body) != null)) continue
                val key = ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0))
                if (key in taught) continue
                list += Text(key, threadId, c.getString(2).orEmpty().ifBlank { wanted.getValue(threadId).address }, body, threadId in replied)
            }
        }
        // The pipeline's own first checks decide what stays on the phone; the plan agrees with them exactly.
        val rules = verdicts.allSenderRules().associate { it.address to runCatching { com.ericflo.winnow.classifier.message.SenderRule.valueOf(it.rule) }.getOrNull() }
        val gate = classifiers.create(current)
        conversations.filter { it.threadId in byThread }.flatMap { byThread.getValue(it.threadId) }
            .map { t -> Text(t.key, t.threadId, t.sender, t.body, t.repliedTo, rules[com.ericflo.winnow.data.normalizeAddress(t.sender)]) }
            .filterNot { gate.staysOnPhone(it.message()) }
    }

    private fun threadsWithOutgoing(): Set<Long> {
        val threads = HashSet<Long>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_SENT}", null, null,
        )?.use { c -> while (c.moveToNext()) threads += c.getLong(0) }
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.THREAD_ID),
            "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_SENT}", null, null,
        )?.use { c -> while (c.moveToNext()) threads += c.getLong(0) }
        return threads
    }

    private fun Text.message() = InboundMessage(sender = sender, body = body, senderInContacts = false, userHasMessagedSender = repliedTo, senderRule = senderRule)

    companion object {
        /** Newest received texts sent per conversation: enough to know it, few enough to keep sending down. */
        const val PER_CONVERSATION = 3
        /** Below this, the provider's answer is a guess, and isn't taught. */
        const val MIN_CONFIDENCE = 0.7
        private const val CONCURRENCY = 3
        private const val BATCH = 24
        /** The model is refit every this many batches as a run goes, and at its end. */
        private const val RETRAIN_EVERY_BATCHES = 10
        private const val MAX_FAILURES_IN_A_ROW = 8
        private const val PROVIDER_TIMEOUT_MILLIS = 15_000L
    }
}
