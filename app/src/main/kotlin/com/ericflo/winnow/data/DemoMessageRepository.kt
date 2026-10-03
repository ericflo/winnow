package com.ericflo.winnow.data

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.ActionPolicy
import com.ericflo.winnow.classifier.message.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Sample conversations, with verdicts as Winnow would record them, shown until SMS access is granted. */
class DemoMessageRepository : MessageRepository {
    private data class DemoThread(
        val threadId: Long,
        val address: String,
        val name: String,
        val messages: List<ChatMessage>,
        val unreadCount: Int,
    )

    private val threads = MutableStateFlow(seed())

    override fun conversations(): Flow<List<ConversationSummary>> = threads.map { list ->
        list.filter { it.messages.isNotEmpty() }.map { t ->
            val last = t.messages.last()
            ConversationSummary(
                threadId = t.threadId,
                address = t.address,
                displayName = t.name,
                snippet = if (last.outgoing) "You: ${last.body}" else last.body,
                timestamp = last.timestamp,
                unreadCount = t.unreadCount,
                verdict = t.messages.lastOrNull { !it.outgoing }?.verdict,
            )
        }.sortedByDescending { it.timestamp }
    }

    override fun messages(threadId: Long): Flow<List<ChatMessage>> =
        threads.map { list -> list.firstOrNull { it.threadId == threadId }?.messages.orEmpty() }

    override fun displayName(address: String): String =
        find(address)?.name ?: ContactLookup.formatAddress(address)

    override suspend fun threadIdFor(address: String): Long {
        find(address)?.let { return it.threadId }
        val id = threads.value.maxOf { it.threadId } + 1
        threads.update { it + DemoThread(id, address, ContactLookup.formatAddress(address), emptyList(), unreadCount = 0) }
        return id
    }

    override suspend fun send(address: String, body: String) {
        val threadId = threadIdFor(address)
        threads.update { list ->
            list.map { t ->
                if (t.threadId != threadId) t
                else t.copy(messages = t.messages + ChatMessage(nextId(), threadId, body, System.currentTimeMillis(), true, ChatMessage.Status.SENT, null))
            }
        }
    }

    override suspend fun markRead(threadId: Long) {
        threads.update { list -> list.map { if (it.threadId == threadId) it.copy(unreadCount = 0) else it } }
    }

    override suspend fun markAllRead() {
        threads.update { list -> list.map { it.copy(unreadCount = 0) } }
    }

    override suspend fun overrideVerdict(threadId: Long, address: String, action: Action) {
        threads.update { list ->
            list.map { t ->
                if (t.threadId != threadId) t
                else t.copy(messages = t.messages.map { m -> m.copy(verdict = m.verdict?.copy(userAction = action)) })
            }
        }
    }

    private fun find(address: String) =
        threads.value.firstOrNull { normalizeAddress(it.address) == normalizeAddress(address) }

    private fun nextId() = threads.value.flatMap { it.messages }.maxOfOrNull { it.id }?.plus(1) ?: 1

    private companion object {
        fun seed(): List<DemoThread> {
            val now = System.currentTimeMillis()
            fun ago(minutes: Long) = now - minutes * 60_000
            var id = 0L
            fun inbound(thread: Long, body: String, minutesAgo: Long, verdict: StoredVerdict) =
                ChatMessage(++id, thread, body, ago(minutesAgo), outgoing = false, ChatMessage.Status.RECEIVED, verdict)
            fun outbound(thread: Long, body: String, minutesAgo: Long) =
                ChatMessage(++id, thread, body, ago(minutesAgo), outgoing = true, ChatMessage.Status.SENT, null)

            val contact = StoredVerdict(Category.PERSONAL, 1.0, Action.ALLOW, "Sender is in your contacts")
            val code = StoredVerdict(Category.TRANSACTIONAL, 1.0, Action.ALLOW, "Verification code, kept on this phone")
            fun jev(category: Category, p: Double) =
                StoredVerdict(category, p, ActionPolicy().resolve(category, p, fromHeuristic = false), "Classified by ${ProviderKind.TYPESAFE_JEV.label}")
            // Filtered messages are marked read on arrival, as IncomingMessageHandler does.
            fun stranger(threadId: Long, number: String, unread: Boolean, vararg messages: ChatMessage) =
                DemoThread(
                    threadId, number, ContactLookup.formatAddress(number), messages.toList(),
                    unreadCount = if (unread && messages.none { it.verdict?.effectiveAction == Action.FILTER }) messages.size else 0,
                )

            return listOf(
                DemoThread(
                    1, "+15555550101", "Mom",
                    listOf(
                        outbound(1, "Landed! Grabbing my bag now", 60 * 26),
                        inbound(1, "Safe travels home 💛", 60 * 26 - 3, contact),
                        inbound(1, "Are you still coming Sunday? Dad's making his chili", 14, contact),
                    ),
                    unreadCount = 1,
                ),
                DemoThread(
                    2, "+15555550102", "Sam Rivera",
                    listOf(
                        inbound(2, "running 10 late, grab us a table?", 52, contact),
                        outbound(2, "On it. Back corner by the window", 50),
                    ),
                    unreadCount = 0,
                ),
                stranger(3, "72975", false, inbound(3, "Your Northwind Bank verification code is 482913. Don't share it with anyone.", 95, code)),
                stranger(
                    4, "+13185550182", true,
                    inbound(4, "E-ZPass: Your toll balance of \$4.35 is unpaid. Avoid a \$50 late fee, pay today: ezpass-tolls.top/pay", 33, jev(Category.PHISHING, 0.98)),
                ),
                stranger(
                    5, "+16595550147", true,
                    inbound(5, "Hi, is this Jessica? We met at the wine tasting last weekend 😊", 60 * 3, jev(Category.SCAM, 0.86)),
                ),
                stranger(
                    6, "827438", false,
                    inbound(6, "Harbor & Pine: 30% off fall decor this weekend only! Shop now: hpine.co/fall Reply STOP to opt out", 60 * 5, jev(Category.MARKETING, 0.95)),
                ),
                stranger(
                    7, "+12025550199", true,
                    inbound(7, "Election Day is 31 days away and we're \$12K short of our goal. Chip in \$5 before midnight?", 60 * 7, jev(Category.POLITICAL, 0.97)),
                ),
                stranger(
                    8, "+447700900123", true,
                    inbound(8, "USPS: Your package is on hold due to an incomplete address. Update within 12 hours: usps-redelivery.vip/track", 60 * 20, jev(Category.PHISHING, 0.99)),
                ),
                stranger(
                    11, "+17715550142", true,
                    inbound(11, "BREAKING: The House just passed a CATASTROPHIC bill. Add your name before midnight >>", 6, jev(Category.POLITICAL, 0.98)),
                ),
                stranger(
                    12, "+17715550143", true,
                    inbound(12, "Hi, it's Mark! Can you complete your Approval Poll? Due to low response we need yours by 11:59pm", 60 * 26, jev(Category.POLITICAL, 0.91)),
                ),
                stranger(
                    9, "+14155550177", false,
                    inbound(9, "Reminder: you have an appointment Tue Oct 7 at 2:30 PM with Dr. Patel. Reply C to confirm or R to reschedule.", 60 * 30, jev(Category.TRANSACTIONAL, 0.93)),
                ),
                stranger(
                    10, "+14155550110", true,
                    inbound(10, "Hey it's Jordan from the climbing gym, still down for Thursday?", 60 * 2, jev(Category.PERSONAL, 0.84)),
                ),
            )
        }
    }
}
