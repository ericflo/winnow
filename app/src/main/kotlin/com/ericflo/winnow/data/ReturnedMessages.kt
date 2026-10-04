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
    data class Returned(val text: String, val attachments: List<OutgoingAttachment>, val separately: Boolean)

    private val byThread = ConcurrentHashMap<Long, Returned>()
    private val _arrived = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    /** Thread ids as messages come back, for a conversation that's open again by then. */
    val arrived: SharedFlow<Long> = _arrived

    fun put(threadId: Long, returned: Returned) {
        byThread.merge(threadId, returned) { a, b ->
            Returned(listOf(a.text, b.text).filter { it.isNotBlank() }.joinToString("\n"), a.attachments + b.attachments, a.separately || b.separately)
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
    }
}
