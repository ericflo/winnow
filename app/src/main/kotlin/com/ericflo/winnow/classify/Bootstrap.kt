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

    /**
     * [pausedFor] is set while the run waits out [trouble] (rate limiting, no connection) before
     * retrying. [runId] is its record (see RunEntity), which outlives Winnow being closed.
     */
    data class Running(
        val done: Int,
        val total: Int,
        val tally: Bootstrap.Tally,
        val pausedFor: Long? = null,
        val trouble: Pacer.Trouble? = null,
        val runId: Long = 0,
    ) : BootstrapStatus

    data class Finished(val total: Int, val tally: Bootstrap.Tally, val stopped: Boolean, val error: String? = null, val runId: Long = 0) : BootstrapStatus
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
    /** Where each run and its answers are kept (see RunEntity). */
    private val runs: com.ericflo.winnow.data.db.RunDao,
    /** Told when a run ends, so counts elsewhere (older conversations to review) catch up. */
    private val onFinished: () -> Unit = {},
) {
    /**
     * What a run would send: [texts] from [conversations] conversations, under [privacy] (what
     * the confirmation describes: whether people the user wrote to, codes or the sender's number
     * go, and what's masked).
     */
    data class Plan(
        val conversations: Int,
        val texts: Int,
        val privacy: com.ericflo.winnow.classifier.message.PrivacyPolicy,
        /** The user's own labeled texts sent with each request as examples, and in how many categories. */
        val examples: Int = 0,
        val exampleCategories: Int = 0,
    )

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

    /** The last plan shown was a redo (see [plan]). */
    @Volatile private var plannedRedo = false

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

    /**
     * What a run would send. [redo]: ask again about texts the service has answered before (with
     * the user's latest labels as examples); its new answers replace the old, never the user's.
     */
    suspend fun plan(redo: Boolean = false): Plan {
        val current = settings.current()
        val texts = candidates(current, redo)
        planned = texts
        plannedRedo = redo
        val examples = examples(current)
        return Plan(
            texts.map { it.threadId }.distinct().size, texts.size, current.effectivePrivacy,
            examples = examples.values.sumOf { it.size }, exampleCategories = examples.count { it.value.isNotEmpty() },
        )
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val first = settings.current()
            // Can't run now (the service turned off since it was offered, say): said, and ended,
            // never left waiting, a run's notification up and the phone kept awake.
            unavailable(first)?.let { reason ->
                _status.value = BootstrapStatus.Finished(0, Tally(), stopped = true, error = reason)
                return@launch
            }
            val texts = planned ?: candidates(first)
            val redo = plannedRedo && planned != null
            planned = null
            plannedRedo = false
            var tally = Tally()
            var done = 0
            // The user's own labeled texts go with every request, so the service sorts the way they do
            // (chosen again each batch, under the settings then: see below).
            val examples = examples(first)
            val startedAt = System.currentTimeMillis()
            // Kept from the start: a run Winnow is closed during still shows how far it got.
            var run = com.ericflo.winnow.data.db.RunEntity(
                kind = if (redo) com.ericflo.winnow.data.db.RunEntity.KIND_REDO else com.ericflo.winnow.data.db.RunEntity.KIND_BACKLOG,
                provider = first.provider.label,
                startedAt = startedAt,
                updatedAt = startedAt,
                planned = texts.size,
                conversations = texts.map { it.threadId }.distinct().size,
                examples = examples.values.sumOf { it.size },
            )
            run = run.copy(id = runCatching { runs.insert(run) }.getOrDefault(0L))
            val runId = run.id
            fun running(pausedFor: Long? = null, trouble: Pacer.Trouble? = null) =
                BootstrapStatus.Running(done, texts.size, tally, pausedFor, trouble, runId)
            fun finished(stopped: Boolean, error: String? = null, failed: Int = 0) =
                BootstrapStatus.Finished(texts.size, tally.copy(failed = tally.failed + failed), stopped, error, runId)
            _status.value = running()
            // What's left to send; texts that failed for a reason waiting can fix go back to its front.
            val queue = ArrayDeque(texts)
            val pacer = Pacer(maxConcurrency = CONCURRENCY)
            var batches = 0
            var lastDetail = ""
            try {
                while (queue.isNotEmpty()) {
                    val batch = List(minOf(BATCH, queue.size)) { queue.removeFirst() }
                    // Everything is checked again before each batch: the user may have changed a
                    // setting, set a sender rule or labeled a conversation since the plan was made.
                    val current = settings.current()
                    unavailable(current)?.let { reason ->
                        _status.value = finished(stopped = true, error = reason, failed = batch.size + queue.size)
                        return@launch
                    }
                    // The provider decides every text: no deciding on the phone when sure, a generous wait.
                    // The examples, and who the user has written to, as they are now: a privacy
                    // setting changed or a reply sent since the run began counts from this batch.
                    val classifier = classifiers.create(current.copy(decideOnPhoneWhenSure = false), timeoutMillis = PROVIDER_TIMEOUT_MILLIS, examples = examples(current))
                    val judged = verdicts.judgedThreads().toSet()
                    val rules = senderRules()
                    val replied = threadsWithOutgoing()
                    val (stay, go) = batch
                        .map { t -> t.withRule(rules[com.ericflo.winnow.data.normalizeAddress(t.sender)]).withReplied(t.threadId in replied) }
                        .partition { t -> t.threadId in judged || contacts.isContact(t.sender) || classifier.staysOnPhone(t.message()) }
                    tally = tally.copy(kept = tally.kept + stay.size)
                    done += stay.size
                    val gate = Semaphore(pacer.concurrency)
                    // A model in use that learned from texts' context reads each one's.
                    val reads = runCatching { learner.classifier().custom?.readsContext == true }.getOrDefault(false)
                    val results = go.map { t -> async(Dispatchers.IO) { gate.withPermit { t to runCatching { classifier.classify(t.message(reads)) }.getOrNull() } } }.awaitAll()
                    val labels = mutableListOf<CorrectionEntity>()
                    val filed = mutableListOf<VerdictEntity>()
                    val answered = mutableListOf<String>()
                    val answers = mutableListOf<Pair<Text, Verdict>>()
                    val unsureRedone = mutableListOf<String>()
                    val setAside = mutableListOf<String>()
                    val retry = mutableListOf<Text>()
                    val troubles = mutableListOf<Pacer.Trouble>()
                    for ((t, verdict) in results) {
                        val answer = verdict?.takeIf { it.source is VerdictSource.Provider && it.category != null }
                        when {
                            answer != null -> {
                                answered += t.key
                                answers += t to answer
                                done++
                                tally = tally.copy(costUsd = tally.costUsd + answer.costUsd)
                                val row = label(t, answer)?.copy(runId = runId)
                                tally = if (row != null) tally.copy(labeled = tally.labeled + 1) else tally.copy(unsure = tally.unsure + 1)
                                row?.let { labels += it }
                                // A redo's new answer replaces the old one: too unsure to teach, the old one goes too.
                                if (row == null && redo) unsureRedone += t.key
                                // Dated by the message: a verdict on an old text mustn't become the conversation's latest.
                                filed += VerdictEntity.from(t.key, t.threadId, t.sender, answer, t.date).copy(summarized = true, runId = runId)
                                (answer.source as VerdictSource.Provider).model?.let { if (run.model == null) run = run.copy(model = it) }
                            }
                            verdict?.source is VerdictSource.Rule -> {
                                tally = tally.copy(kept = tally.kept + 1)
                                done++
                            }
                            // The user's labels of the sender decided it, or outweighed the service's
                            // answer: an answer, not a failure, and nothing for the service to teach.
                            verdict?.decidedByYourLabels == true -> {
                                tally = tally.copy(kept = tally.kept + 1, costUsd = tally.costUsd + verdict.costUsd)
                                done++
                                // Settled: never asked about again, and filed as the user's labels say.
                                answered += t.key
                                filed += VerdictEntity.from(t.key, t.threadId, t.sender, verdict, t.date).copy(summarized = true, runId = runId)
                                // Asked and outweighed: what the service said is kept with the run (it teaches nothing).
                                verdict.serviceOpinion?.let { said ->
                                    answers += t to verdict.copy(category = said.category, confidence = said.confidence, subcategory = null)
                                }
                            }
                            else -> {
                                lastDetail = (verdict?.source as? VerdictSource.OnDevice)?.fallbackReason ?: "no answer"
                                tally = tally.copy(costUsd = tally.costUsd + (verdict?.costUsd ?: 0.0))
                                val trouble = Pacer.troubleOf(lastDetail)
                                troubles += trouble
                                if (trouble == Pacer.Trouble.REJECTED) {
                                    // This one text the service won't take: skipped, offered again next run;
                                    // one it answered unusably isn't (it would answer, and be paid, the same).
                                    tally = tally.copy(failed = tally.failed + 1)
                                    done++
                                    if (com.ericflo.winnow.classifier.message.MessageClassifier.UNUSABLE_ANSWER in lastDetail) setAside += t.key
                                } else {
                                    retry += t
                                }
                            }
                        }
                    }
                    // Each answer kept beside what the model made of the text before it learned from it.
                    if (runId != 0L) record(runId, answers, labels.mapNotNullTo(HashSet()) { it.messageKey })
                    if (unsureRedone.isNotEmpty()) runCatching { learner.forgetServiceLabels(unsureRedone) }
                    save(labels, filed, retrain = ++batches % RETRAIN_EVERY_BATCHES == 0)
                    // Set-aside texts aren't asked again, but aren't answers: a service that only
                    // answers unusably still trips the pacer's "rejected too often" stop.
                    remember(answered + setAside)
                    queue.addAll(0, retry)
                    run = progress(run, done, tally)
                    _status.value = running()
                    when (val next = pacer.after(answered.size, troubles, System.currentTimeMillis())) {
                        Pacer.Next.Go -> Unit
                        is Pacer.Next.Wait -> {
                            _status.value = running(pausedFor = next.millis, trouble = next.trouble)
                            kotlinx.coroutines.delay(next.millis)
                            _status.value = running()
                        }
                        is Pacer.Next.GiveUp -> {
                            // What wasn't answered is offered again next run.
                            _status.value = finished(stopped = true, error = failure(current.provider.label, lastDetail), failed = queue.size)
                            return@launch
                        }
                    }
                }
                _status.value = finished(stopped = false)
            } catch (e: CancellationException) {
                _status.value = finished(stopped = true)
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Bootstrap run failed", e)
                _status.value = finished(stopped = true, error = "Something went wrong (${e.message ?: e::class.simpleName}). What was learned is kept.")
            } finally {
                // Whatever was learned is in the model, however the run ended.
                withContext(kotlinx.coroutines.NonCancellable) {
                    (_status.value as? BootstrapStatus.Finished)?.let { end ->
                        if (runId != 0L) runCatching {
                            runs.update(
                                run.copy(
                                    done = done, labeled = end.tally.labeled, unsure = end.tally.unsure, kept = end.tally.kept, failed = end.tally.failed,
                                    costUsd = end.tally.costUsd, updatedAt = System.currentTimeMillis(), finishedAt = System.currentTimeMillis(),
                                    stopped = end.stopped, error = end.error,
                                ),
                            )
                        }
                    }
                    runCatching { learner.reload() }
                    onFinished()
                }
            }
        }
    }

    /** A run's record brought up to [done] and [tally], saved. */
    private suspend fun progress(run: com.ericflo.winnow.data.db.RunEntity, done: Int, tally: Tally): com.ericflo.winnow.data.db.RunEntity {
        val now = run.copy(
            done = done, labeled = tally.labeled, unsure = tally.unsure, kept = tally.kept, failed = tally.failed,
            costUsd = tally.costUsd, updatedAt = System.currentTimeMillis(),
        )
        if (now.id != 0L) runCatching { runs.update(now) }
        return now
    }

    /**
     * Keeps a batch's [answers] for its run: each beside what the on-device model made of the
     * text just before (it hasn't learned from this batch yet), and the service's earlier answer
     * on a redo. A failure to keep them is never a failure of the run.
     */
    private suspend fun record(runId: Long, answers: List<Pair<Text, Verdict>>, taught: Set<String>) {
        if (answers.isEmpty()) return
        runCatching {
            val model = learner.classifier()
            val reads = model.custom?.readsContext == true
            val before = verdicts.forKeys(answers.map { it.first.key })
                .mapNotNull { v -> v.serviceAnswer?.let { v.messageKey to it.first } }.toMap()
            val now = System.currentTimeMillis()
            val rows = withContext(Dispatchers.Default) {
                answers.map { (t, v) ->
                    val opinion = runCatching { model.classify(t.message(reads)) }.getOrNull()
                    com.ericflo.winnow.data.db.RunAnswerEntity(
                        runId = runId,
                        messageKey = t.key,
                        threadId = t.threadId,
                        address = t.sender,
                        category = v.category!!.key,
                        subcategory = v.subcategory,
                        confidence = v.confidence,
                        taught = t.key in taught,
                        modelCategory = opinion?.category?.key,
                        modelConfidence = opinion?.confidence,
                        previous = before[t.key],
                        answeredAt = now,
                        latencyMillis = v.latencyMillis,
                        repliedTo = t.repliedTo,
                    )
                }
            }
            runs.insertAnswers(rows)
        }.onFailure { android.util.Log.w(TAG, "Couldn't keep a run's answers", it) }
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
        // Never over a verdict made as the text arrived, or one the user corrected or labeled: only
        // where there was none, or one from an earlier look back (this service's, or a review's).
        val kept = verdicts.forKeys(filed.map { it.messageKey })
            .filter { it.atArrival || it.userAction != null || it.userCategory != null }
            .mapTo(HashSet()) { it.messageKey }
        filed.filterNot { it.messageKey in kept }.forEach { verdicts.upsert(it) }
    }

    internal suspend fun senderRules() =
        verdicts.allSenderRules().associate { it.address to runCatching { com.ericflo.winnow.classifier.message.SenderRule.valueOf(it.rule) }.getOrNull() }

    /**
     * The texts a run sends, newest conversations first. Of each conversation with someone who
     * isn't a contact, that the user hasn't labeled or corrected: its newest [PER_CONVERSATION]
     * received texts, and never older ones, less any the service has answered or anything has
     * taught the model. So a later run sends only what's arrived since. What the pipeline would
     * keep on the phone (people the user wrote to, codes, sender rules, filtered words, as their
     * privacy settings say) isn't planned, and is checked again when sent.
     */
    private suspend fun candidates(current: WinnowSettings, redo: Boolean = false): List<Text> = withContext(Dispatchers.IO) {
        // The contact list alone, to plan: a colleague in a work profile is found, and kept, as the run sends.
        val conversations = repo.conversations().first().filter { !it.isGroup && !contacts.inContactList(it.address) }
        val judged = verdicts.judgedThreads().toSet()
        val wanted = conversations.filter { it.threadId !in judged }.associateBy { it.threadId }
        val replied = threadsWithOutgoing()
        // A redo skips only what the user taught; otherwise anything taught or answered before,
        // here or anywhere else (as texts arrived, or checking older conversations): never paid for twice.
        val done = if (redo) corrections.all().filterNot { it.fromProvider }.mapNotNullTo(HashSet()) { it.messageKey }
        else corrections.taughtKeys().toHashSet().apply { addAll(asked()); addAll(verdicts.serviceAnsweredKeys()) }
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
        val memory = runCatching { learner.classifier().memory }.getOrDefault(com.ericflo.winnow.classifier.local.SenderMemory.NONE)
        conversations.filter { it.threadId in newest }.flatMap { newest.getValue(it.threadId) }
            .filterNot { it.key in done }
            .map { t -> t.withRule(rules[com.ericflo.winnow.data.normalizeAddress(t.sender)]) }
            .filterNot { gate.staysOnPhone(it.message()) }
            // Ones the user's labels of the sender decide aren't asked about (see MessageClassifier),
            // so they aren't in the plan either: it says what the run would really send.
            .filterNot { t -> memory.decisive(t.sender, conversing = t.repliedTo) != null }
    }

    /**
     * Up to [EXAMPLES_PER_CATEGORY] of the user's own labeled texts per category, newest first:
     * only ones the pipeline would send itself (never a contact's, someone's they wrote to, a
     * code or a sender with a rule, unless their privacy settings send those), no repeats.
     */
    private suspend fun examples(current: WinnowSettings): Map<com.ericflo.winnow.classifier.message.Category, List<String>> =
        exampleTexts(current).groupBy({ it.category }, { it.body })

    /** One of the user's labeled texts fit to send as an example, with its conversation. */
    data class Example(val category: com.ericflo.winnow.classifier.message.Category, val body: String, val threadId: Long)

    /** [examples], each with the conversation it's from, so a test of a text can leave its own conversation's out. */
    internal suspend fun exampleTexts(current: WinnowSettings): List<Example> = withContext(Dispatchers.IO) {
        val mine = exampleLabels(corrections.all(), verdicts.recheckThreads().toSet())
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
        val replied = threadsWithOutgoing()
        val rules = senderRules()
        val gate = classifiers.create(current)
        val out = LinkedHashMap<com.ericflo.winnow.classifier.message.Category, MutableList<Example>>()
        for ((id, label) in mine) {
            val category = com.ericflo.winnow.classifier.message.Category.fromKey(label) ?: continue
            val list = out.getOrPut(category) { mutableListOf() }
            val m = sms[id] ?: continue
            if (list.size >= EXAMPLES_PER_CATEGORY || m.body.isBlank() || list.any { it.body == m.body }) continue
            val message = InboundMessage(
                sender = m.address, body = m.body, senderInContacts = contacts.isContact(m.address),
                userHasMessagedSender = m.threadId in replied, senderRule = rules[com.ericflo.winnow.data.normalizeAddress(m.address)],
            )
            if (!gate.staysOnPhone(message)) list += Example(category, m.body, m.threadId)
        }
        out.values.flatten()
    }

    private fun Text.withRule(rule: com.ericflo.winnow.classifier.message.SenderRule?) = Text(key, threadId, sender, body, date, repliedTo, rule)

    private fun Text.withReplied(replied: Boolean) = Text(key, threadId, sender, body, date, replied, senderRule)

    internal fun threadsWithOutgoing(): Set<Long> {
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

    /** The text as a model reads it, with its context when the model learned from texts' context. */
    private fun Text.message(withContext: Boolean) = if (withContext) message().copy(context = contexts.before(threadId, date, key)) else message()

    private val contexts by lazy { com.ericflo.winnow.data.MessageContexts(context) }

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
                Regex("""HTTP (40[45]|410)\b""").containsMatchIn(detail) ->
                    "$provider has nothing that matches what was asked for. Check the model in Settings, and the address if you set one."
                Regex("""HTTP 4\d\d\b""").containsMatchIn(detail) ->
                    "$provider turned down every text it was sent, so the run stopped there."
                Regex("""HTTP 5\d\d\b""").containsMatchIn(detail) -> "$provider is having trouble. Try again later."
                else -> "$provider couldn't be reached. Check your connection and try again."
            }
            // "Provider unavailable (systemone:x: systemone:x: …)": the id once is plenty.
            val tidy = detail.removePrefix("Provider unavailable (").removeSuffix(")")
                .replace(Regex("""^([\w:.-]+): \1: """), "$1: ").take(160)
            return if (tidy.isBlank()) plain else "$plain ($tidy)"
        }
        private const val KEY_ASKED = "asked"

        /** Why a run is waiting, in words: shown on its card and in its notification. */
        fun pauseText(provider: String, trouble: Pacer.Trouble?, millis: Long): String {
            val why = when (trouble) {
                Pacer.Trouble.RATE_LIMITED -> "$provider is limiting how fast it answers"
                else -> "$provider can't be reached right now"
            }
            val seconds = (millis + 999) / 1000
            val wait = if (seconds >= 60) "${(seconds + 59) / 60} min" else "$seconds s"
            return "$why. Trying again in $wait."
        }

        /**
         * The user's labels that can show the service how they sort, as (SMS id, category key),
         * newest first: their own (not the service's), on a text message, and not in a
         * conversation still waiting to be confirmed under the six categories, whose label may
         * mean what the old categories meant.
         */
        fun exampleLabels(corrections: List<CorrectionEntity>, recheckThreads: Set<Long>): List<Pair<Long, String>> =
            corrections
                .filter { !it.fromProvider && it.threadId !in recheckThreads && it.messageKey?.startsWith("sms:") == true }
                .sortedByDescending { it.createdAt }
                .mapNotNull { r -> r.messageKey!!.removePrefix("sms:").toLongOrNull()?.let { it to r.label } }

        /** The user's labeled texts sent per category with each request, as examples of how they sort. */
        const val EXAMPLES_PER_CATEGORY = 3

        /** Newest received texts sent per conversation: enough to know it, few enough to keep sending down. */
        const val PER_CONVERSATION = 3
        /** Below this, the provider's answer is a guess, and isn't taught. */
        const val MIN_CONFIDENCE = Learner.MIN_TEACH_CONFIDENCE
        private const val CONCURRENCY = 3
        private const val BATCH = 24
        /** The model is refit every this many batches as a run goes, and at its end. */
        private const val RETRAIN_EVERY_BATCHES = 10
        private const val PROVIDER_TIMEOUT_MILLIS = 15_000L
    }
}
