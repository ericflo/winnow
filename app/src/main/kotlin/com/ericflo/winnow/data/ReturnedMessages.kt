package com.ericflo.winnow.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Messages that couldn't be sent after their conversation's screen had gone, held until the
 * conversation is open again: its composer takes them back, attachments and all. (The text is
 * also saved as the draft, so it outlives the app being closed.)
 */
class ReturnedMessages {
    /** [shared]: not a failure but something shared into the conversation (Direct Share). */
    data class Returned(
        val text: String,
        val attachments: List<OutgoingAttachment>,
        val separately: Boolean,
        val shared: Boolean = false,
        /** An MMS subject it was sent with. */
        val subject: String? = null,
    )

    private val byThread = ConcurrentHashMap<Long, Returned>()
    private val _arrived = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    /** Thread ids as messages come back, for a conversation that's open again by then. */
    val arrived: SharedFlow<Long> = _arrived

    fun put(threadId: Long, returned: Returned) {
        byThread.merge(threadId, returned) { a, b ->
            Returned(
                listOf(a.text, b.text).filter { it.isNotBlank() }.joinToString("\n"),
                a.attachments + b.attachments,
                a.separately || b.separately,
                a.shared && b.shared,
                // In the order they were sent, as the text is.
                mergeSubjects(a.subject, b.subject),
            )
        }
        _arrived.tryEmit(threadId)
    }

    fun take(threadId: Long): Returned? = byThread.remove(threadId)

    companion object {
        /**
         * [draft] with [text] on a line of its own at the end, unless it's already there (it was
         * saved into the draft, and the draft was read back). An exact line, not a substring: a
         * returned "Yes" isn't already in "Yesterday was fun".
         */
        fun appendTo(draft: String, text: String): String = when {
            text.isBlank() || draft == text || draft.endsWith("\n$text") -> draft
            draft.isBlank() -> text
            else -> "$draft\n$text"
        }

        /**
         * Two subjects as one: [returned] alone if [current] is blank or already holds it (it was
         * saved, and read back), else both. Null if neither says anything.
         */
        fun mergeSubjects(returned: String?, current: String?): String? {
            val r = returned?.trim()?.takeIf { it.isNotEmpty() }
            val c = current?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                r == null -> c ?: current
                c == null || c == r -> r
                r in c -> c
                c in r -> r
                else -> "$r · $c"
            }
        }
    }
}
