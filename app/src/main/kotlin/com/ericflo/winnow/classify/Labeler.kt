package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.db.CorrectionEntity
import com.ericflo.winnow.data.db.VerdictDao
import com.ericflo.winnow.data.db.VerdictEntity
import kotlinx.coroutines.flow.first

/**
 * The user's labels: "this is spam", "this is personal". A label does two things at once. It
 * files the message where its category goes (a spam label sends the conversation to Filtered,
 * a personal one back to the inbox), and it teaches the on-device model, which is refit on
 * the spot (see Learner). Labels stay on the phone; only the message's feature buckets are
 * kept for learning, never its text.
 */
class Labeler(
    private val repo: MessageRepository,
    private val verdicts: VerdictDao,
    private val learner: Learner,
    private val contacts: ContactLookup,
    private val settings: SettingsRepository,
) {
    /** What a labeling changed, so it can be taken back exactly. */
    class Undo internal constructor(
        internal val keys: List<String>,
        internal val verdictsBefore: List<VerdictEntity>,
        internal val labelsBefore: List<CorrectionEntity>,
    )

    /** [labeled] messages in [conversations] conversations; [undo] is null when nothing was labeled. */
    data class Result(val conversations: Int, val labeled: Int, val undo: Undo?)

    /**
     * Labels whole conversations ([threadId] to its recipients): each one's last few received
     * messages, which is what the model judges a conversation by. Retrains once at the end.
     */
    suspend fun labelConversations(conversations: List<Pair<Long, List<String>>>, category: Category): Result {
        val keys = mutableListOf<String>()
        val verdictsBefore = mutableListOf<VerdictEntity>()
        val labelsBefore = mutableListOf<CorrectionEntity>()
        var labeledConversations = 0
        for ((threadId, recipients) in conversations) {
            val messages = repo.messages(threadId).first()
            val examples = examplesFrom(messages, recipients)
            if (examples.isEmpty()) continue
            val undo = label(threadId, recipients, examples, hasOutgoing = messages.any { it.outgoing }, category, retrain = false)
            keys += undo.keys
            verdictsBefore += undo.verdictsBefore
            labelsBefore += undo.labelsBefore
            labeledConversations++
        }
        if (keys.isEmpty()) return Result(0, 0, null)
        learner.reload()
        return Result(labeledConversations, keys.size, Undo(keys, verdictsBefore, labelsBefore))
    }

    /** Labels particular [messages] of one conversation (a message the user long-pressed, say). */
    suspend fun labelMessages(threadId: Long, recipients: List<String>, messages: List<ChatMessage>, category: Category, all: List<ChatMessage> = messages): Result {
        val examples = messages.filter(::labelable)
        if (examples.isEmpty()) return Result(0, 0, null)
        val undo = label(threadId, recipients, examples, hasOutgoing = all.any { it.outgoing }, category, retrain = true)
        return Result(1, undo.keys.size, undo)
    }

    /** Takes a labeling back: the verdicts and labels as they were before it. */
    suspend fun undo(undo: Undo) {
        val had = undo.verdictsBefore.mapTo(HashSet()) { it.messageKey }
        verdicts.deleteForMessages(undo.keys.filter { it !in had })
        undo.verdictsBefore.forEach { verdicts.upsert(it) }
        learner.unlabel(undo.keys, undo.labelsBefore)
    }

    private suspend fun label(
        threadId: Long,
        recipients: List<String>,
        messages: List<ChatMessage>,
        hasOutgoing: Boolean,
        category: Category,
        retrain: Boolean,
    ): Undo {
        val examples = messages.mapNotNull { m ->
            val sender = m.sender ?: recipients.singleOrNull() ?: return@mapNotNull null
            m to InboundMessage(
                sender = sender,
                body = textOf(m),
                senderInContacts = contacts.isContact(sender),
                userHasMessagedSender = hasOutgoing,
            )
        }
        val keys = examples.map { it.first.key }
        val before = verdicts.forKeys(keys)
        val byKey = before.associateBy { it.messageKey }
        val action = settings.current().actionPolicy.forCategory(category).name
        val now = System.currentTimeMillis()
        for ((m, inbound) in examples) {
            val row = byKey[m.key]?.copy(userCategory = category.key, userAction = action) ?: VerdictEntity(
                messageKey = m.key,
                threadId = threadId,
                address = inbound.sender,
                category = category.key,
                confidence = 1.0,
                action = action,
                sourceKind = "rule",
                sourceDetail = "Labeled by you",
                model = null,
                costUsd = 0.0,
                decidedAt = now,
                userAction = action,
                // Never news for a daily summary: the user just did it.
                summarized = true,
                userCategory = category.key,
            )
            verdicts.upsert(row)
        }
        val replaced = learner.label(threadId, examples.associate { (m, inbound) -> m.key to inbound }, category, retrain)
        return Undo(keys, before, replaced)
    }

    companion object {
        /** How many of a conversation's received messages a conversation label teaches: its newest. */
        const val PER_CONVERSATION = 5

        /** The messages a conversation label covers: its newest received ones with something in them. */
        fun examplesFrom(messages: List<ChatMessage>, recipients: List<String>): List<ChatMessage> =
            messages.filter(::labelable).takeLast(PER_CONVERSATION)

        /** Something the user received, downloaded, with words or an attachment. */
        fun labelable(m: ChatMessage): Boolean =
            !m.outgoing && !m.isPlaceholder && (m.body.isNotBlank() || !m.subject.isNullOrBlank() || m.attachments.isNotEmpty())

        /** What the model reads of a message: its subject and words, or "[photo]" for a picture alone. */
        fun textOf(m: ChatMessage): String =
            listOfNotNull(m.subject?.takeIf { it.isNotBlank() }, m.body.takeIf { it.isNotBlank() }).joinToString("\n").ifBlank { "[photo]" }
    }
}
