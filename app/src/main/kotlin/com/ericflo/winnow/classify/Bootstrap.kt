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
    /**
     * What a run would send: [texts] from [conversations] conversations, under [privacy] (what
     * the confirmation describes: whether people the user wrote to, codes or the sender's number
     * go, and what's masked).
     */
    data class Plan(val conversations: Int, val texts: Int, val privacy: com.ericflo.winnow.classifier.message.PrivacyPolicy)

    /**
     * How a run went: texts [labeled] (taught to the model), [unsure] (answered, too unsure to
     * teach), [kept] on the phone by a privacy rule at send time, [failed] (tried again next
     * time), and what the answers cost.
     */
    data class Tally(val labeled: Int = 0, val unsure: Int = 0, val kept: Int = 0, val failed: Int = 0, val costUsd: Double = 0.0)

    private class Text(
        val key: String,
        val threadId: Long,
        val sender: String,
        val body: String,
        val date: Long,
        val repliedTo: Boolean,
        val senderRule: com.ericflo.winnow.classifier.message.SenderRule? = null,
    )

    private val _status = MutableStateFlow<BootstrapStatus>(BootstrapStatus.Idle)
    val status: StateFlow<BootstrapStatus> = _status.asStateFlow()

    /** Labels a provider has given so far, over every run. */
    val taught: Flow<Int> = corrections.observeProviderCount()

    private var job: Job? = null
    private val base by lazy { OnDeviceClassifier() }

    /** The texts of the last plan shown, which a run started from it sends (checked again as it goes). */
    @Volatile private var planned: List<Text>? = null

    /** Texts the service has answered, sure or not: never sent again. */
    private val prefs by lazy { context.getSharedPreferences("bootstrap", Context.MODE_PRIVATE) }

    private fun asked(): Set<String> = prefs.getStringSet(KEY_ASKED, emptySet()).orEmpty().toSet()

    private fun remember(keys: Collection<String>) {
        if (keys.isNotEmpty()) prefs.edit().putStringSet(KEY_ASKED, asked() + keys).apply()
    }

    /** Why a run can't start with these [settings], in words; null when it can. */
    fun unavailable(settings: WinnowSettings): String? {
        val kind = settings.provider
        if (kind == ProviderKind.ON_DEVICE) return "Choose a classifier service in Settings first: this sends texts to it."
        if (classifiers.provider(settings) == null) return "Finish setting up ${kind.label} in Settings first."
        if (!settings.settingsFor(kind).zeroRetention) return "Turn on zero data retention for ${kind.label} in Settings first."
        if (DataHandling.REMOTE_ZERO_RETENTION !in settings.effectivePrivacy.allowedDataHandling) return "Your privacy settings don't allow sending texts to ${kind.label}."
        // Without contacts, a contact can't be told from a stranger, and their texts would go.
        if (!contacts.canRead()) return "Let Winnow see your contacts first, so texts from them stay on your phone."
        return null
    }

    suspend fun plan(): Plan {
        val current = settings.current()
        val texts = candidates(current)
        planned = texts
        return Plan(texts.map { it.threadId }.distinct().size, texts.size, current.effectivePrivacy)
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val first = settings.current()
            if (unavailable(first) != null) return@launch
            val texts = planned ?: candidates(first)
            planned = null
            var tally = Tally()
            var done = 0
            _status.value = BootstrapStatus.Running(0, texts.size, tally)
            val gate = Semaphore(CONCURRENCY)
            var failuresInARow = 0
            var error: String? = null
            try {
                for (batch in texts.chunked(BATCH)) {
                    // Everything is checked again before each batch: the user may have changed a
                    // setting, set a sender rule or labeled a conversation since the plan was made.
                    val current = settings.current()
                    unavailable(current)?.let { reason ->
                        _status.value = BootstrapStatus.Finished(texts.size, tally, stopped = true, error = reason)
                        return@launch
                    }
                    // The provider decides every text: no deciding on the phone when sure, a generous wait.
                    val classifier = classifiers.create(current.copy(decideOnPhoneWhenSure = false), timeoutMillis = PROVIDER_TIMEOUT_MILLIS)
                    val judged = verdicts.judgedThreads().toSet()
                    val rules = senderRules()
                    val (stay, go) = batch
                        .map { t -> t.withRule(rules[com.ericflo.winnow.data.normalizeAddress(t.sender)]) }
                        .partition { t -> t.threadId in judged || contacts.isContact(t.sender) || classifier.staysOnPhone(t.message()) }
                    tally = tally.copy(kept = tally.kept + stay.size)
                    val results = go.map { t -> async(Dispatchers.IO) { gate.withPermit { t to runCatching { classifier.classify(t.message()) }.getOrNull() } } }.awaitAll()
                    val labels = mutableListOf<CorrectionEntity>()
                    val filed = mutableListOf<VerdictEntity>()
                    val answered = mutableListOf<String>()
                    for ((t, verdict) in results) {
                        val answer = verdict?.takeIf { it.source is VerdictSource.Provider && it.category != null }
                        when {
                            answer != null -> {
                                failuresInARow = 0
                                answered += t.key
                                tally = tally.copy(costUsd = tally.costUsd + answer.costUsd)
                                val row = label(t, answer)
                                tally = if (row != null) tally.copy(labeled = tally.labeled + 1) else tally.copy(unsure = tally.unsure + 1)
                                row?.let { labels += it }
                                // Dated by the message: a verdict on an old text mustn't become the conversation's latest.
                                filed += VerdictEntity.from(t.key, t.threadId, t.sender, answer, t.date).copy(summarized = true)
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
                    remember(answered)
                    done += batch.size
                    _status.value = BootstrapStatus.Running(done, texts.size, tally)
                    if (failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                        _status.value = BootstrapStatus.Finished(texts.size, tally, stopped = true, error = failure(current.provider.label, error.orEmpty()))
                        return@launch
                    }
                }
                _status.value = BootstrapStatus.Finished(texts.size, tally, stopped = false)
            } catch (e: CancellationException) {
                _status.value = BootstrapStatus.Finished(texts.size, tally, stopped = true)
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Bootstrap run failed", e)
                _status.value = BootstrapStatus.Finished(texts.size, tally, stopped = true, error = "Something went wrong (${e.message ?: e::class.simpleName}). What was learned is kept.")
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

    /** After the user forgets what the service taught: a later run (confirmed again) may ask afresh. */
    fun forgetAsked() {
        prefs.edit().remove(KEY_ASKED).apply()
    }

    /** Back to idle once the user has seen how a run ended. */
    fun dismiss() {
        if (job?.isActive != true) _status.value = BootstrapStatus.Idle
    }

    private fun label(t: Text, verdict: Verdict): CorrectionEntity? {
        // An unsure answer would teach the model a guess.
        if (verdict.confidence < MIN_CONFIDENCE) return null
        val category = verdict.category ?: return null
        val correction = base.correction(t.message(), setOf(category)) ?: return null
        return CorrectionEntity(
            threadId = t.threadId,
            buckets = correction.buckets.joinToString(","),
            label = category.key,
            featurizerVersion = Featurizer.VERSION,
            createdAt = System.currentTimeMillis(),
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

    private suspend fun senderRules() =
        verdicts.allSenderRules().associate { it.address to runCatching { com.ericflo.winnow.classifier.message.SenderRule.valueOf(it.rule) }.getOrNull() }

    /**
     * The texts a run sends, newest conversations first. Of each conversation with someone who
     * isn't a contact, that the user hasn't labeled or corrected: its newest [PER_CONVERSATION]
     * received texts, and never older ones, less any the service has answered or anything has
     * taught the model. So a later run sends only what's arrived since. What the pipeline would
     * keep on the phone (people the user wrote to, codes, sender rules, filtered words, as their
     * privacy settings say) isn't planned, and is checked again when sent.
     */
    private suspend fun candidates(current: WinnowSettings): List<Text> = withContext(Dispatchers.IO) {
        val conversations = repo.conversations().first().filter { !it.isGroup && !contacts.isContact(it.address) }
        val judged = verdicts.judgedThreads().toSet()
        val wanted = conversations.filter { it.threadId !in judged }.associateBy { it.threadId }
        val replied = threadsWithOutgoing()
        val done = corrections.taughtKeys().toHashSet().apply { addAll(asked()) }
        val newest = HashMap<Long, MutableList<Text>>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}", null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val threadId = c.getLong(1)
                if (threadId !in wanted) continue
                val body = c.getString(3).orEmpty()
                if (body.isBlank()) continue
                // The newest few, whatever becomes of them: an answered one keeps its slot.
                val list = newest.getOrPut(threadId) { mutableListOf() }
                if (list.size >= PER_CONVERSATION) continue
                val sender = c.getString(2).orEmpty().ifBlank { wanted.getValue(threadId).address }
                list += Text(ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0)), threadId, sender, body, c.getLong(4), threadId in replied)
            }
        }
        val rules = senderRules()
        val gate = classifiers.create(current)
        conversations.filter { it.threadId in newest }.flatMap { newest.getValue(it.threadId) }
            .filterNot { it.key in done }
            .map { t -> t.withRule(rules[com.ericflo.winnow.data.normalizeAddress(t.sender)]) }
            .filterNot { gate.staysOnPhone(it.message()) }
    }

    private fun Text.withRule(rule: com.ericflo.winnow.classifier.message.SenderRule?) = Text(key, threadId, sender, body, date, repliedTo, rule)

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
        private const val TAG = "WinnowBootstrap"

        /**
         * Why a run stopped, in words first and the provider's own detail after: a refused key or
         * an unreachable server are what the user can do something about. Pure, so it's unit-tested.
         */
        fun failure(provider: String, detail: String): String {
            val plain = when {
                Regex("""HTTP 40[13]\b""").containsMatchIn(detail) -> "$provider refused the API key. Check it in Settings."
                Regex("""HTTP 402\b""").containsMatchIn(detail) -> "$provider says the account needs credit."
                Regex("""HTTP 429\b""").containsMatchIn(detail) -> "$provider is limiting how fast it answers. Try again in a while."
                Regex("""HTTP 5\d\d\b""").containsMatchIn(detail) -> "$provider is having trouble. Try again later."
                else -> "$provider couldn't be reached. Check your connection and try again."
            }
            // "Provider unavailable (systemone:x: systemone:x: …)": the id once is plenty.
            val tidy = detail.removePrefix("Provider unavailable (").removeSuffix(")")
                .replace(Regex("""^([\w:.-]+): \1: """), "$1: ").take(160)
            return if (tidy.isBlank()) plain else "$plain ($tidy)"
        }
        private const val KEY_ASKED = "asked"

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
