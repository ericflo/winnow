package com.ericflo.winnow.data

import com.ericflo.winnow.R
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.ActionPolicy
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.data.ChatMessage.Kind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Sample conversations, with verdicts as Winnow would record them, shown until SMS access is granted. */
class DemoMessageRepository(private val packageName: String) : MessageRepository {
    private data class DemoThread(
        val threadId: Long,
        val recipients: List<String>,
        val messages: List<ChatMessage>,
        val unreadCount: Int,
    )

    private val threads = MutableStateFlow(seed())

    override fun conversations(): Flow<List<ConversationSummary>> = threads.map { list ->
        list.filter { it.messages.isNotEmpty() }.map { t ->
            val last = t.messages.last()
            val text = last.body.ifBlank { if (last.attachments.any { it.isImage }) "Photo" else "Attachment" }
            ConversationSummary(
                threadId = t.threadId,
                recipients = t.recipients,
                displayName = displayNameFor(t.recipients, ::displayName),
                snippet = if (last.outgoing) "You: $text" else text,
                timestamp = last.timestamp,
                unreadCount = t.unreadCount,
                verdict = t.messages.lastOrNull { !it.outgoing }?.verdict,
            )
        }.sortedByDescending { it.timestamp }
    }

    override fun messages(threadId: Long): Flow<List<ChatMessage>> =
        threads.map { list -> list.firstOrNull { it.threadId == threadId }?.messages.orEmpty() }

    override fun displayName(address: String): String =
        NAMES[normalizeAddress(address)] ?: ContactLookup.formatAddress(address)

    override suspend fun threadIdFor(recipients: List<String>): Long {
        find(recipients)?.let { return it.threadId }
        val id = threads.value.maxOf { it.threadId } + 1
        threads.update { it + DemoThread(id, recipients, emptyList(), unreadCount = 0) }
        return id
    }

    override suspend fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment>) {
        val threadId = threadIdFor(recipients)
        val message = ChatMessage(
            id = nextId(), threadId = threadId, body = body, timestamp = System.currentTimeMillis(), outgoing = true,
            status = ChatMessage.Status.SENT, verdict = null,
            kind = if (recipients.size > 1 || attachments.isNotEmpty()) Kind.MMS else Kind.SMS,
            attachments = attachments.map { Attachment(it.uri, it.contentType, it.name) },
        )
        updateThread(threadId) { it.copy(messages = it.messages + message) }
    }

    override suspend fun retry(message: ChatMessage) = updateThread(message.threadId) { t ->
        t.copy(messages = t.messages.map { if (it.key == message.key) it.copy(status = ChatMessage.Status.SENT) else it })
    }

    override suspend fun markRead(threadId: Long) = updateThread(threadId) { it.copy(unreadCount = 0) }

    override suspend fun markUnread(threadId: Long) = updateThread(threadId) { it.copy(unreadCount = maxOf(1, it.unreadCount)) }

    override suspend fun markAllRead() = threads.update { list -> list.map { it.copy(unreadCount = 0) } }

    override suspend fun deleteThreads(threadIds: Collection<Long>) = threads.update { list -> list.filterNot { it.threadId in threadIds } }

    override suspend fun deleteMessage(message: ChatMessage) =
        updateThread(message.threadId) { t -> t.copy(messages = t.messages.filterNot { it.key == message.key }) }

    override suspend fun search(query: String): List<SearchHit> =
        threads.value.flatMap { t ->
            t.messages.filter { it.body.contains(query, ignoreCase = true) }.map {
                SearchHit(t.threadId, t.recipients, displayNameFor(t.recipients, ::displayName), it.body, it.timestamp)
            }
        }.sortedByDescending { it.timestamp }

    override suspend fun overrideVerdict(threadId: Long, address: String, action: Action) =
        updateThread(threadId) { t -> t.copy(messages = t.messages.map { m -> m.copy(verdict = m.verdict?.copy(userAction = action)) }) }

    private fun updateThread(threadId: Long, transform: (DemoThread) -> DemoThread) =
        threads.update { list -> list.map { if (it.threadId == threadId) transform(it) else it } }

    private fun find(recipients: List<String>): DemoThread? {
        val wanted = recipients.map(::normalizeAddress).toSet()
        return threads.value.firstOrNull { t -> t.recipients.map(::normalizeAddress).toSet() == wanted }
    }

    private fun nextId() = threads.value.flatMap { it.messages }.maxOfOrNull { it.id }?.plus(1) ?: 1

    private fun seed(): List<DemoThread> {
        val now = System.currentTimeMillis()
        var id = 0L
        fun ago(minutes: Long) = now - minutes * 60_000
        fun inbound(body: String, minutesAgo: Long, verdict: StoredVerdict, from: String? = null, photo: Boolean = false) =
            ChatMessage(
                ++id, 0, body, ago(minutesAgo), outgoing = false, ChatMessage.Status.RECEIVED, verdict,
                kind = if (photo || from != null) Kind.MMS else Kind.SMS,
                sender = from,
                attachments = if (photo) listOf(Attachment("android.resource://$packageName/${R.drawable.demo_sunset}", "image/png", "sunset.png")) else emptyList(),
            )
        fun outbound(body: String, minutesAgo: Long, mms: Boolean = false) =
            ChatMessage(++id, 0, body, ago(minutesAgo), outgoing = true, ChatMessage.Status.SENT, null, kind = if (mms) Kind.MMS else Kind.SMS)

        val contact = StoredVerdict(Category.PERSONAL, 1.0, Action.ALLOW, "Sender is in your contacts")
        val code = StoredVerdict(Category.TRANSACTIONAL, 1.0, Action.ALLOW, "Verification code, kept on this phone")
        fun jev(category: Category, p: Double) =
            StoredVerdict(category, p, ActionPolicy().resolve(category, p, fromHeuristic = false), "Classified by ${ProviderKind.TYPESAFE_JEV.label}")
        // Filtered messages are marked read on arrival, as IncomingMessageHandler does.
        fun thread(n: Long, recipients: List<String>, unread: Boolean, vararg messages: ChatMessage): DemoThread {
            val incoming = messages.filterNot { it.outgoing }
            val filtered = incoming.any { it.verdict?.effectiveAction == Action.FILTER }
            return DemoThread(BASE + n, recipients, messages.map { it.copy(threadId = BASE + n) }, if (unread && !filtered) incoming.size else 0)
        }

        return listOf(
            thread(
                1, listOf(MOM), true,
                outbound("Landed! Grabbing my bag now", 60 * 26),
                inbound("Safe travels home 💛", 60 * 26 - 3, contact),
                inbound("Are you still coming Sunday? Dad's making his chili", 14, contact),
            ),
            thread(
                2, listOf(SAM), false,
                inbound("running 10 late, grab us a table?", 52, contact),
                outbound("On it. Back corner by the window", 50),
            ),
            thread(
                13, listOf(ALEX, PRIYA, SAM), true,
                inbound("Lake house is booked for the 18th!", 60 * 3, contact, from = ALEX),
                outbound("Amazing. I'll bring the paddleboards", 60 * 3 - 4, mms = true),
                inbound("", 41, contact, from = PRIYA, photo = true),
                inbound("Last year's sunset, for motivation", 40, contact, from = PRIYA),
                inbound("ok now I'm counting the days", 22, contact, from = SAM),
            ),
            thread(3, listOf("72975"), false, inbound("Your Northwind Bank verification code is 482913. Don't share it with anyone.", 95, code)),
            thread(
                4, listOf("+13185550182"), true,
                inbound("E-ZPass: Your toll balance of \$4.35 is unpaid. Avoid a \$50 late fee, pay today: ezpass-tolls.top/pay", 33, jev(Category.PHISHING, 0.98)),
            ),
            thread(5, listOf("+16595550147"), true, inbound("Hi, is this Jessica? We met at the wine tasting last weekend 😊", 60 * 3, jev(Category.SCAM, 0.86))),
            thread(
                6, listOf("827438"), false,
                inbound("Harbor & Pine: 30% off fall decor this weekend only! Shop now: hpine.co/fall Reply STOP to opt out", 60 * 5, jev(Category.MARKETING, 0.95)),
            ),
            thread(
                7, listOf("+12025550199"), true,
                inbound("Election Day is 31 days away and we're \$12K short of our goal. Chip in \$5 before midnight?", 60 * 7, jev(Category.POLITICAL, 0.97)),
            ),
            thread(
                11, listOf("+17715550142"), true,
                inbound("BREAKING: The House just passed a CATASTROPHIC bill. Add your name before midnight >>", 6, jev(Category.POLITICAL, 0.98)),
            ),
            thread(
                12, listOf("+17715550143"), true,
                inbound("Hi, it's Mark! Can you complete your Approval Poll? Due to low response we need yours by 11:59pm", 60 * 26, jev(Category.POLITICAL, 0.91)),
            ),
            thread(
                8, listOf("+447700900123"), true,
                inbound("USPS: Your package is on hold due to an incomplete address. Update within 12 hours: usps-redelivery.vip/track", 60 * 20, jev(Category.PHISHING, 0.99)),
            ),
            thread(
                9, listOf("+14155550177"), false,
                inbound("Reminder: you have an appointment Tue Oct 7 at 2:30 PM with Dr. Patel. Reply C to confirm or R to reschedule.", 60 * 30, jev(Category.TRANSACTIONAL, 0.93)),
            ),
            thread(10, listOf("+14155550110"), true, inbound("Hey it's Jordan from the climbing gym, still down for Thursday?", 60 * 2, jev(Category.PERSONAL, 0.84))),
        )
    }

    private companion object {
        /** Keeps demo thread ids clear of real ones, so Winnow-local state never collides. */
        const val BASE = 1_000_000_000L

        const val MOM = "+15555550101"
        const val SAM = "+15555550102"
        const val ALEX = "+15555550103"
        const val PRIYA = "+15555550104"
        val NAMES = mapOf(MOM to "Mom", SAM to "Sam Rivera", ALEX to "Alex Chen", PRIYA to "Priya Natarajan")
            .mapKeys { normalizeAddress(it.key) }
    }
}
