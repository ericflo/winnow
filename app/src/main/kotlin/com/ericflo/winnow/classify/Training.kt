package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.local.Featurizer

import android.content.Context
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.db.VerdictDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Training Winnow in rounds: it guesses at a small batch of the user's unlabeled conversations,
 * the user confirms or fixes each guess, those become labels (see Labeler), the model is refit,
 * and the next batch is guessed by the better model. Only conversations with people who aren't
 * contacts take part: Winnow lets contacts through without asking the model.
 */
class Training(
    private val context: Context,
    private val repo: MessageRepository,
    private val verdicts: VerdictDao,
    private val corrections: com.ericflo.winnow.data.db.CorrectionDao,
    private val learner: Learner,
    private val contacts: ContactLookup,
) {
    /** One conversation in a round, with the model's guess. */
    data class Candidate(
        val threadId: Long,
        val recipients: List<String>,
        val name: String,
        val photoUri: String?,
        /** The newest received text, what the guess is about. */
        val text: String,
        val guess: Category,
        val confidence: Double,
        /** The other received texts a label on this conversation covers, newest first. */
        val earlier: List<String> = emptyList(),
        /** Whether the user has written to them: the model reads that too. */
        val repliedTo: Boolean = false,
        /** The guess the round started with; [guess] moves as the user answers others (see Learner.preview). */
        val firstGuess: Category = guess,
        /** What the classifier service (Jev) said of this conversation, if it was asked (see Bootstrap). */
        val providerSays: Category? = null,
        /**
         * The user's own label from before the six categories, here to be confirmed or changed.
         * Null for one they labeled Reminder, which was cleared to label again (see [Round.cleared]).
         */
        val before: Category? = null,
        /** The user labeled it Reminder, cleared when that was taken out: to label again. */
        val cleared: Boolean = false,
        /** The service's fine-grained answer, if it gave one ("toll_phishing"). */
        val providerDetail: String? = null,
        /** The backlog run [providerSays] came from, if one did: which service it was is the run's. */
        val providerRunId: Long? = null,
    ) {
        /** What the model reads to guess: the newest text, as they sent it. */
        fun message() = InboundMessage(sender = recipients.first(), body = text, senderInContacts = false, userHasMessagedSender = repliedTo)
    }

    /**
     * [rechecks] of the candidates are the user's earlier labels to confirm, [cleared] of those
     * their Reminder labels, cleared when it was taken out, to label again; [toRecheck]
     * conversations wait in all.
     */
    data class Round(
        val candidates: List<Candidate>,
        val backlog: Int,
        val labeled: Int,
        val rechecks: Int = 0,
        val toRecheck: Int = 0,
        val cleared: Int = 0,
        /** Why these conversations, when they were picked for a reason (see [Focus]). */
        val focus: String? = null,
    )

    /**
     * Conversations the next round is made of, and why: the ones that read most like the user's
     * labels of a category, to give the model more of what it misses (see [likeThese]). Taken by
     * the next new round; a round already being answered comes first.
     */
    data class Focus(val threadIds: List<Long>, val category: Category, val why: String)

    private val focus = java.util.concurrent.atomic.AtomicReference<Focus?>(null)

    fun focusOn(f: Focus) = focus.set(f)

    fun takeFocus(): Focus? = focus.getAndSet(null)

    /**
     * The conversations still to label (as rounds choose them: strangers', not labeled yet) whose
     * newest text reads most like the user's own labels of [category], best first: by how alike
     * their words are to the closest of those, rarer words counting more. More of the category to
     * answer as the user sees them; nothing's labeled for them.
     */
    suspend fun likeThese(category: Category, size: Int = ROUND_SIZE): List<Long> = withContext(Dispatchers.IO) {
        val mine = corrections.all().filter { !it.fromProvider && it.label == category.key }.mapNotNull { it.messageKey }
        val texts = com.ericflo.winnow.data.MessageTexts(context).of(mine)
        val examples = texts.values.map { t -> Featurizer.features(Featurizer.Input(t.address.orEmpty(), t.body)) }
        if (examples.isEmpty()) return@withContext emptyList()
        val judged = verdicts.judgedThreads().toSet()
        val backlog = repo.conversations().first().filter { eligible(it) && it.threadId !in judged && !knownEmpty(it) }
        val pool = backlog.map { c -> c.threadId to Featurizer.features(Featurizer.Input(c.address, c.snippet.removePrefix("You: "))) }
        closest(pool, examples, size * 2)
    }

    /** A finished round, kept so progress can be shown round over round. */
    @Serializable
    data class RoundResult(
        val at: Long,
        /** Conversations the user answered; [agreed] of them were Winnow's guess. */
        val reviewed: Int,
        val agreed: Int,
        /** Ones left as "Not sure" or unchecked. */
        val skipped: Int = 0,
    )

    /**
     * A round being answered, kept until it's finished so that Winnow being closed, or killed in
     * the background while the user looks something up, doesn't lose their answers. Each answer
     * is kept with the guess it was given against, since guesses move as the model learns.
     */
    @Serializable
    data class Progress(val threadIds: List<Long>, val answers: List<Answer>) {
        /** [answer] is a category key, or null for "Not sure". */
        @Serializable
        data class Answer(val threadId: Long, val guess: String, val answer: String?)
    }

    /**
     * The next round: up to [size] unlabeled conversations, guessed by the model as it is now.
     * Mostly the ones it's least sure of (they teach it most), with a few at random so the ones
     * it's confidently wrong about come up too. With [only], those conversations in that order
     * (a round being picked up again), less any that no longer wait to be labeled.
     */
    suspend fun nextRound(size: Int = ROUND_SIZE, seed: Long = System.currentTimeMillis(), only: List<Long>? = null): Round = withContext(Dispatchers.IO) {
        val judged = verdicts.judgedThreads().toSet()
        val all = repo.conversations().first()
        // The user's labels from before the six categories, by conversation: the newest one's category
        // (none for their Reminder labels, cleared to label again).
        val before = verdicts.toRecheck().groupBy { it.threadId }
            .mapValues { (_, rows) -> rows.maxBy { it.decidedAt }.userCategory?.let(Category::fromKey) }
        // Rechecks come from any conversation the user labeled, contacts' and groups' included (a
        // group blast is labeled as often as anything); the rest are strangers' own conversations.
        val backlog = all.filter { c -> (c.threadId in before || eligible(c)) && c.threadId !in judged && !knownEmpty(c) }
        val classifier = learner.classifier()
        val details = verdicts.providerDetails().associate { it.threadId to it.subcategory }
        // What the classifier service said of each conversation, from its newest label there.
        // Its label on the conversation's newest text (message ids grow with time), not its latest run's.
        val providerRows = corrections.all().filter { it.fromProvider && it.threadId != null }
            .groupBy { it.threadId!! }.mapValues { (_, rows) -> rows.maxBy { r -> r.messageKey?.substringAfter(':')?.toLongOrNull() ?: 0 } }
        val provider = providerRows.mapValues { (_, row) -> Category.fromKey(row.label) }
        // A first, cheap guess from each conversation's latest text, to choose the batch. Where the
        // service and the model disagree comes first: that's where the user's answer counts most.
        // Across the cores: a phone has several, and a backlog can be a thousand and more conversations.
        val guessed = if (only != null) emptyList() else backlog.chunked(GUESS_CHUNK).map { chunk -> async(Dispatchers.Default) { chunk.map { c ->
            val p = classifier.classify(InboundMessage(sender = c.address, body = c.snippet.removePrefix("You: ")))
            val disagree = provider[c.threadId]?.let { it != p.category } == true
            c.threadId to priority(p.confidence, recheck = c.threadId in before, disagree = disagree)
        } } }.awaitAll().flatten()
        val byId = backlog.associateBy { it.threadId }
        val candidates = mutableListOf<Candidate>()
        // In the order they'd be picked, skipping any with nothing received to label, until the round is full.
        for (threadId in only?.filter { it in byId } ?: rank(guessed, size, Random(seed))) {
            if (candidates.size >= size) break
            val c = byId.getValue(threadId)
            // A colleague in a work profile is a contact too (asked of Android only for the ones picked).
            if (threadId !in before && contacts.isContact(c.address)) continue
            val messages = repo.messagesNow(threadId, newestSenders = Labeler.SENDERS_NEEDED)
            val covered = Labeler.examplesFrom(messages, c.recipients)
            val newest = covered.lastOrNull()
            if (newest == null) {
                empty[threadId] = c.timestamp
                continue
            }
            // The real guess, from the newest message they actually sent.
            val p = classifier.classify(
                InboundMessage(
                    sender = c.address,
                    body = Labeler.textOf(newest),
                    senderInContacts = false,
                    userHasMessagedSender = messages.any { it.outgoing },
                ),
            )
            candidates += Candidate(
                c.threadId, c.recipients, c.displayName, c.photoUri, Labeler.textOf(newest), p.category, p.confidence,
                earlier = covered.dropLast(1).asReversed().map(Labeler::textOf),
                repliedTo = messages.any { it.outgoing },
                providerSays = provider[c.threadId],
                providerRunId = providerRows[c.threadId]?.runId?.takeIf { it > 0 },
                before = before[c.threadId],
                cleared = c.threadId in before && before[c.threadId] == null,
                providerDetail = details[c.threadId],
            )
        }
        Round(
            // Ranked by doubt, like the batch was chosen; the screen groups them by guess.
            candidates,
            backlog = backlog.count { !knownEmpty(it) },
            labeled = all.count { it.threadId in judged && eligible(it) },
            rechecks = candidates.count { it.threadId in before },
            cleared = candidates.count { it.cleared },
            // Those a round can show: not a conversation deleted since, nor one with nothing received in it.
            toRecheck = backlog.count { it.threadId in before && !knownEmpty(it) },
        )
    }

    // The contact list alone: a colleague in a work profile is only looked for among the conversations a round picks.
    private fun eligible(c: ConversationSummary): Boolean = !c.isGroup && !contacts.inContactList(c.address)

    /**
     * Conversations found to have nothing received to label (only the user's own texts, or
     * pictures not downloaded), by when they last changed: they're left out until they do.
     */
    private val empty = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private fun knownEmpty(c: ConversationSummary): Boolean = empty[c.threadId] == c.timestamp

    // --- Round history, for progress ---------------------------------------------------------

    private val prefs by lazy { context.getSharedPreferences("training", Context.MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun history(): List<RoundResult> = withContext(Dispatchers.IO) {
        prefs.getString(KEY_ROUNDS, null)?.let { runCatching { json.decodeFromString(ListSerializer(RoundResult.serializer()), it) }.getOrNull() }.orEmpty()
    }

    /** The round being answered, if there is one with answers (see [Progress]). */
    suspend fun progress(): Progress? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_PROGRESS, null)?.let { runCatching { json.decodeFromString(Progress.serializer(), it) }.getOrNull() }
    }

    /** Keeps the round being answered; null once it's finished or has no answers left. */
    fun saveProgress(progress: Progress?) {
        prefs.edit().apply {
            if (progress == null || progress.answers.isEmpty()) remove(KEY_PROGRESS)
            else putString(KEY_PROGRESS, json.encodeToString(Progress.serializer(), progress))
        }.apply()
    }

    suspend fun record(result: RoundResult) = withContext(Dispatchers.IO) {
        val done = roundsDone() + 1
        val rounds = (history() + result).takeLast(MAX_ROUNDS_KEPT)
        prefs.edit().putString(KEY_ROUNDS, json.encodeToString(ListSerializer(RoundResult.serializer()), rounds)).putInt(KEY_DONE, done).apply()
    }

    /** Rounds finished, ever: only the latest [MAX_ROUNDS_KEPT] are kept, so not their count. */
    suspend fun roundsDone(): Int = withContext(Dispatchers.IO) {
        prefs.getInt(KEY_DONE, -1).takeIf { it >= 0 } ?: history().size
    }

    companion object {
        const val ROUND_SIZE = 20

        /** Conversations guessed per job when choosing a round (see nextRound). */
        private const val GUESS_CHUNK = 128

        /**
         * Of [pool] (an id and a text's features), the [size] whose words are most like the closest
         * of [examples]: cosine over words, word pairs and named signals, each weighted by how rare
         * it is across them all, so "your" and "the" don't make texts alike. Best first.
         */
        fun closest(pool: List<Pair<Long, List<String>>>, examples: List<List<String>>, size: Int): List<Long> {
            fun kept(f: List<String>) = f.filter { it.startsWith("w:") || it.startsWith("b:") || it.startsWith("h:") || it.startsWith("__") }.toSet()
            val all = pool.map { kept(it.second) } + examples.map(::kept)
            val df = HashMap<String, Int>().also { m -> all.forEach { s -> s.forEach { m.merge(it, 1, Int::plus) } } }
            val n = all.size.toDouble()
            fun vector(s: Set<String>): Map<String, Double> {
                val w = s.associateWith { kotlin.math.ln(1 + n / (df[it] ?: 1)) }
                val norm = kotlin.math.sqrt(w.values.sumOf { it * it }).takeIf { it > 0 } ?: return emptyMap()
                return w.mapValues { it.value / norm }
            }
            val ex = examples.map { vector(kept(it)) }
            return pool.map { (id, f) ->
                val v = vector(kept(f))
                id to ex.maxOf { e -> v.entries.sumOf { (k, x) -> x * (e[k] ?: 0.0) } }
            }.sortedByDescending { it.second }.take(size).map { it.first }
        }


        /**
         * Where a conversation goes in the order a round picks from (lower first): the user's
         * earlier labels to recheck (and their Reminder labels, cleared to label again), then where
         * the service and the model disagree, then by how unsure the model is. Pure, so it's
         * unit-tested.
         */
        fun priority(confidence: Double, recheck: Boolean, disagree: Boolean): Double = when {
            recheck -> confidence - 2.0
            disagree -> confidence - 1.0
            else -> confidence
        }
        private const val KEY_ROUNDS = "rounds"
        private const val KEY_DONE = "rounds_done"
        private const val KEY_PROGRESS = "round_in_progress"
        private const val MAX_ROUNDS_KEPT = 200

        /**
         * Which conversations make a round of [size] from ([threadId], model confidence): about
         * two thirds the least confident, the rest at random from what's left. Pure, so it's
         * unit-tested.
         */
        fun pick(guesses: List<Pair<Long, Double>>, size: Int, random: Random): List<Long> = rank(guesses, size, random).take(size)

        /** Every conversation, in the order a round of [size] takes them, so it can skip past ones it can't use. */
        fun rank(guesses: List<Pair<Long, Double>>, size: Int, random: Random): List<Long> {
            val byDoubt = guesses.sortedBy { it.second }
            val unsure = byDoubt.take(size * 2 / 3).map { it.first }
            return unsure + byDoubt.drop(unsure.size).map { it.first }.shuffled(random)
        }
    }
}
