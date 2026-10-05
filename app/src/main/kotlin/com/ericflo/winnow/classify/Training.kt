package com.ericflo.winnow.classify

import android.content.Context
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.db.VerdictDao
import kotlinx.coroutines.Dispatchers
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
    ) {
        /** What the model reads to guess: the newest text, as they sent it. */
        fun message() = InboundMessage(sender = recipients.first(), body = text, senderInContacts = false, userHasMessagedSender = repliedTo)
    }

    data class Round(val candidates: List<Candidate>, val backlog: Int, val labeled: Int)

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
     * The next round: up to [size] unlabeled conversations, guessed by the model as it is now.
     * Mostly the ones it's least sure of (they teach it most), with a few at random so the ones
     * it's confidently wrong about come up too.
     */
    suspend fun nextRound(size: Int = ROUND_SIZE, seed: Long = System.currentTimeMillis()): Round = withContext(Dispatchers.IO) {
        val judged = verdicts.judgedThreads().toSet()
        val all = repo.conversations().first()
        val backlog = all.filter { c -> eligible(c) && c.threadId !in judged && !knownEmpty(c) }
        val classifier = learner.classifier()
        // A first, cheap guess from each conversation's latest text, to choose the batch.
        val guessed = backlog.map { c ->
            val p = classifier.classify(InboundMessage(sender = c.address, body = c.snippet.removePrefix("You: ")))
            c.threadId to p.confidence
        }
        val byId = backlog.associateBy { it.threadId }
        val candidates = mutableListOf<Candidate>()
        // In the order they'd be picked, skipping any with nothing received to label, until the round is full.
        for (threadId in rank(guessed, size, Random(seed))) {
            if (candidates.size >= size) break
            val c = byId.getValue(threadId)
            val messages = repo.messagesNow(threadId)
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
            )
        }
        Round(
            // Ranked by doubt, like the batch was chosen; the screen groups them by guess.
            candidates,
            backlog = backlog.count { !knownEmpty(it) },
            labeled = all.count { it.threadId in judged && eligible(it) },
        )
    }

    private fun eligible(c: ConversationSummary): Boolean = !c.isGroup && !contacts.isContact(c.address)

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

    suspend fun record(result: RoundResult) = withContext(Dispatchers.IO) {
        val rounds = (history() + result).takeLast(MAX_ROUNDS_KEPT)
        prefs.edit().putString(KEY_ROUNDS, json.encodeToString(ListSerializer(RoundResult.serializer()), rounds)).apply()
    }

    companion object {
        const val ROUND_SIZE = 20
        private const val KEY_ROUNDS = "rounds"
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
