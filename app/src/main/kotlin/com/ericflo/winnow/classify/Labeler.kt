package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classifier.message.SenderRule
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.normalizeAddress
import com.ericflo.winnow.data.db.CorrectionEntity
import com.ericflo.winnow.data.db.SenderRuleEntity
import com.ericflo.winnow.data.db.VerdictDao
import com.ericflo.winnow.data.db.VerdictEntity

/**
 * The user's labels: "this is spam", "this is personal". A label does two things at once. It
 * files the message where its category goes (a spam label sends the conversation to Filtered,
 * a personal one back to the inbox), and it teaches the on-device model, which is refit on
 * the spot (see Learner). Labels stay on the phone; only the message's feature buckets are
 * kept for learning, never its text.
 *
 * A conversation label is also a word about the sender, so a sender rule that disagrees with it
 * ("always allow" on texts labeled spam) is removed, and their next texts are judged afresh. A
 * single message's label isn't (a friend's one forwarded chain letter says nothing about them),
 * and leaves any rule in place.
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
        /** Sender rules the labeling removed, because they disagreed with it. */
        internal val rulesBefore: List<SenderRuleEntity> = emptyList(),
    )

    /**
     * [labeled] messages in [conversations] conversations; [undo] is null when nothing was
     * labeled. [rulesRemoved] are the disagreeing sender rules a conversation label removed;
     * [ruleKept] is one a message label left in place.
     */
    data class Result(
        val conversations: Int,
        val labeled: Int,
        val undo: Undo?,
        val rulesRemoved: List<SenderRule> = emptyList(),
        val ruleKept: SenderRule? = null,
    )

    /**
     * Labels whole conversations ([threadId] to its recipients): each one's last few received
     * messages, which is what the model judges a conversation by. Retrains once at the end.
     */
    suspend fun labelConversations(conversations: List<Pair<Long, List<String>>>, category: Category): Result {
        val keys = mutableListOf<String>()
        val verdictsBefore = mutableListOf<VerdictEntity>()
        val labelsBefore = mutableListOf<CorrectionEntity>()
        val rulesBefore = mutableListOf<SenderRuleEntity>()
        var labeledConversations = 0
        val action = settings.current().actionPolicy.forCategory(category)
        for ((threadId, recipients) in conversations) {
            val messages = repo.messagesNow(threadId)
            val examples = examplesFrom(messages, recipients)
            if (examples.isEmpty()) continue
            val undo = label(threadId, recipients, examples, hasOutgoing = messages.any { it.outgoing }, category, retrain = false)
            keys += undo.keys
            verdictsBefore += undo.verdictsBefore
            labelsBefore += undo.labelsBefore
            // Sender rules are one person's; a group's label leaves its members' rules alone.
            recipients.singleOrNull()?.let { disagreeingRule(it, action) }?.let { rule ->
                verdicts.deleteSenderRule(rule.address)
                rulesBefore += rule
            }
            labeledConversations++
        }
        if (keys.isEmpty()) return Result(0, 0, null)
        learner.reload()
        return Result(
            labeledConversations,
            keys.size,
            Undo(keys, verdictsBefore, labelsBefore, rulesBefore),
            rulesRemoved = rulesBefore.mapNotNull { ruleOf(it) },
        )
    }

    /** Labels particular [messages] of one conversation (a message the user long-pressed, say). */
    suspend fun labelMessages(threadId: Long, recipients: List<String>, messages: List<ChatMessage>, category: Category, all: List<ChatMessage> = messages): Result {
        val examples = messages.filter(::labelable)
        if (examples.isEmpty()) return Result(0, 0, null)
        val undo = label(threadId, recipients, examples, hasOutgoing = all.any { it.outgoing }, category, retrain = true)
        val action = settings.current().actionPolicy.forCategory(category)
        val kept = recipients.singleOrNull()?.let { disagreeingRule(it, action) }?.let(::ruleOf)
        return Result(1, undo.keys.size, undo, ruleKept = kept)
    }

    /** Takes a labeling back: the verdicts and labels as they were before it. */
    suspend fun undo(undo: Undo) {
        val had = undo.verdictsBefore.mapTo(HashSet()) { it.messageKey }
        verdicts.deleteForMessages(undo.keys.filter { it !in had })
        undo.verdictsBefore.forEach { verdicts.upsert(it) }
        undo.rulesBefore.forEach { verdicts.upsertSenderRule(it) }
        learner.unlabel(undo.keys, undo.labelsBefore)
    }

    /** [address]'s sender rule, when it would do something other than [action] with their texts. */
    private suspend fun disagreeingRule(address: String, action: Action): SenderRuleEntity? =
        verdicts.senderRuleEntity(normalizeAddress(address))?.takeIf { e -> ruleOf(e)?.let { disagrees(it, action) } == true }

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
                // Dated by the message, not the labeling: after a reply, the conversation is
                // filed by its latest verdict, and labeling an old text mustn't make it that.
                decidedAt = m.timestamp,
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
        private fun ruleOf(e: SenderRuleEntity): SenderRule? = runCatching { SenderRule.valueOf(e.rule) }.getOrNull()

        /** Whether a sender [rule] files texts other than where a label's [action] does. */
        fun disagrees(rule: SenderRule, action: Action): Boolean = when (rule) {
            SenderRule.ALWAYS_ALLOW -> action != Action.ALLOW
            SenderRule.ALWAYS_FILTER -> action != Action.FILTER
        }

        /** What to tell the user once [result] is in: what was labeled, and what became of sender rules. */
        fun summary(category: Category, result: Result): String = buildString {
            append(
                if (result.conversations > 1) "${result.conversations} labeled ${category.label}. Winnow learned from them."
                else "Labeled ${category.label}. Winnow learned from it.",
            )
            val removed = result.rulesRemoved
            when {
                removed.isEmpty() -> Unit
                result.conversations <= 1 -> append(
                    if (removed.single() == SenderRule.ALWAYS_ALLOW) " This sender is no longer always allowed."
                    else " This sender is no longer always filtered.",
                )
                removed.size == 1 -> append(" Removed a sender rule that disagreed.")
                else -> append(" Removed ${removed.size} sender rules that disagreed.")
            }
            when (result.ruleKept) {
                SenderRule.ALWAYS_ALLOW -> append(" You still always allow this sender.")
                SenderRule.ALWAYS_FILTER -> append(" You still always filter this sender.")
                null -> Unit
            }
        }

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
