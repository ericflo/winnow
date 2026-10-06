package com.ericflo.winnow.classify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classifier.message.SenderRule
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.db.VerdictDao
import com.ericflo.winnow.data.db.VerdictEntity
import com.ericflo.winnow.data.normalizeAddress
import com.ericflo.winnow.data.subjectAndText
import com.ericflo.winnow.sms.MmsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import com.ericflo.winnow.classifier.message.MessageClassifier
import com.ericflo.winnow.classifier.message.VerdictSource
import com.ericflo.winnow.classifier.message.Verdict
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

sealed interface ReviewStatus {
    data object Unknown : ReviewStatus
    data class Ready(val pending: Int) : ReviewStatus
    data class Running(val done: Int, val total: Int) : ReviewStatus
    /** [unreached]: conversations left for next time because the classifier service couldn't be reached. */
    data class Finished(val reviewed: Int, val filtered: Int, val silenced: Int, val unreached: Int = 0) : ReviewStatus
}

/**
 * Classifies conversations that arrived before Winnow could see them (or while it couldn't
 * decide): the newest incoming message of each thread without a verdict. Runs only when the
 * user asks, on the app scope so it survives leaving the screen, and never notifies.
 */
class HistoryReviewer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: VerdictDao,
    private val contacts: ContactLookup,
    private val settings: SettingsRepository,
    private val classifiers: ClassifierFactory,
) {
    private data class Candidate(val key: String, val threadId: Long, val sender: String, val body: String, val date: Long)

    private val _status = MutableStateFlow<ReviewStatus>(ReviewStatus.Unknown)
    val status: StateFlow<ReviewStatus> = _status.asStateFlow()
    private var job: Job? = null

    /**
     * Recounts what's pending, unless a review is running, or one finished and the user hasn't
     * said they've seen how it went ([dismissed]: they have). Without SMS access there's nothing
     * to count.
     */
    fun refresh(dismissed: Boolean = false) {
        if (job?.isActive == true) return
        if (_status.value is ReviewStatus.Finished && !dismissed) return
        if (!canReadSms()) {
            _status.value = ReviewStatus.Unknown
            return
        }
        scope.launch { _status.value = ReviewStatus.Ready(candidates().size) }
    }

    fun start() {
        if (job?.isActive == true || !canReadSms()) return
        // Under way before anything watches for it: the service keeping it going (screen off,
        // Winnow left) stops when it isn't.
        _status.value = ReviewStatus.Running(0, 0)
        WorkService.start(context)
        job = scope.launch {
            try {
                val pending = candidates()
                val classifier = classifiers.create(settings.current())
                val replied = threadsWithOutgoing()
                val filtered = AtomicInteger()
                val silenced = AtomicInteger()
                val done = AtomicInteger()
                val decided = AtomicInteger()
                // The service asked and not answering, again and again (no signal, say): the rest wait
                // for a review that can reach it, rather than each waiting out a timeout and being
                // settled on the phone for good.
                val failedInARow = AtomicInteger()
                // Left for next time: the service didn't answer, or the review had stopped by then.
                val unreached = AtomicInteger()
                // A few at once, as a backlog run asks: one at a time, a thousand conversations sent to
                // a classifier service take many minutes. Each is saved as it's decided, so one stopped
                // partway (Winnow closed) goes on from there next time.
                val gate = Semaphore(CONCURRENCY)
                _status.value = ReviewStatus.Running(0, pending.size)
                pending.map { c ->
                    async {
                        gate.withPermit {
                            if (failedInARow.get() >= GIVE_UP_AFTER) {
                                unreached.incrementAndGet()
                                return@withPermit
                            }
                            when (review(c, classifier, replied, filtered, silenced)) {
                                true -> { decided.incrementAndGet(); failedInARow.set(0) }
                                false -> { unreached.incrementAndGet(); failedInARow.incrementAndGet() }
                                null -> Unit
                            }
                        }
                        _status.value = ReviewStatus.Running(done.incrementAndGet(), pending.size)
                    }
                }.awaitAll()
                _status.value = ReviewStatus.Finished(decided.get(), filtered.get(), silenced.get(), unreached.get())
            } finally {
                // Stopped or failed partway: what's left is counted again, to check another time.
                if (_status.value is ReviewStatus.Running) {
                    _status.value = ReviewStatus.Unknown
                    withContext(kotlinx.coroutines.NonCancellable) { runCatching { _status.value = ReviewStatus.Ready(candidates().size) } }
                }
            }
        }
    }

    /** Stops a review partway; each conversation decided so far stays decided. */
    fun stop() {
        job?.cancel()
    }

    /**
     * Classifies [c] and saves the verdict: true once decided, null if a verdict came meanwhile,
     * false if the service was asked and didn't answer (then nothing is saved: it's asked again
     * next time, not settled on the phone because the network was down).
     */
    private suspend fun review(
        c: Candidate,
        classifier: MessageClassifier,
        replied: Set<Long>,
        filtered: AtomicInteger,
        silenced: AtomicInteger,
    ): Boolean? {
        // Labeled, or arrived and classified, since the review began: that verdict stands.
        if (dao.forKey(c.key) != null) return null
        val verdict = classifier.classify(
            InboundMessage(
                sender = c.sender,
                body = c.body,
                senderInContacts = contacts.isContact(c.sender),
                userHasMessagedSender = c.threadId in replied,
                senderRule = dao.senderRule(normalizeAddress(c.sender))?.let { runCatching { SenderRule.valueOf(it) }.getOrNull() },
            ),
        )
        if (providerFailed(verdict)) return false
        // An older text, reviewed now: never news for a daily summary. Asking can take seconds, and
        // a label or "Not spam" given meanwhile stands: only where there's still no verdict.
        val row = VerdictEntity.from(c.key, c.threadId, c.sender, verdict, System.currentTimeMillis()).copy(summarized = true)
        if (dao.insertIfAbsent(row) == -1L) return null
        when (verdict.action) {
            Action.FILTER -> filtered.incrementAndGet()
            Action.SILENCE -> silenced.incrementAndGet()
            Action.ALLOW -> Unit
        }
        return true
    }

    private companion object {
        /** Conversations sent to the classifier at once (as a backlog run does). */
        const val CONCURRENCY = 3

        /** The service failing this many times in a row, with no answer between, ends a review. */
        const val GIVE_UP_AFTER = 6
    }

    private fun canReadSms() =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** The newest incoming SMS or downloaded MMS of each thread, if it has no verdict yet. */
    /** How many conversations a check would classify now, to ask the user before it does. */
    suspend fun pending(): Int = candidates().size

    private suspend fun candidates(): List<Candidate> = withContext(Dispatchers.IO) {
        val newest = HashMap<Long, Candidate>()
        fun offer(c: Candidate) {
            if ((newest[c.threadId]?.date ?: Long.MIN_VALUE) < c.date) newest[c.threadId] = c
        }
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                offer(Candidate(ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0)), c.getLong(1), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getLong(4)))
            }
        }
        val store = MmsStore(context)
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE),
            "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX} AND ${Telephony.Mms.MESSAGE_TYPE} = ${MmsStore.MESSAGE_TYPE_RETRIEVE_CONF}",
            null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                offer(Candidate(ChatMessage.messageKey(ChatMessage.Kind.MMS, id), c.getLong(1), "", "", c.getLong(2) * 1000))
            }
        }
        // MMS bodies and senders are only fetched for the threads where an MMS is the newest message.
        val filled = newest.values.map { c ->
            if (!c.key.startsWith("mms:")) return@map c
            val id = c.key.removePrefix("mms:").toLong()
            c.copy(sender = store.sender(id).orEmpty(), body = subjectAndText(store.subject(id), store.text(id)).ifBlank { "[photo]" })
        }.filter { it.sender.isNotBlank() }
        val reviewed = filled.map { it.key }.chunked(500).flatMap { dao.existingKeys(it) }.toSet()
        filled.filterNot { it.key in reviewed }.sortedByDescending { it.date }
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
}

/**
 * Whether [verdict] stands in for the classifier service's answer: it was asked, and didn't give
 * one. Not when it answered and the user's labels of the sender outweighed it.
 */
internal fun providerFailed(verdict: Verdict): Boolean = verdict.providerContacted && verdict.source !is VerdictSource.Provider && !verdict.decidedByYourLabels

