package com.ericflo.winnow

import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.ui.thread.latestSpoken
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestSpokenTest {
    private fun text(id: Long, body: String) = ChatMessage(id, 1, body, id * 1_000, outgoing = id % 2 == 0L, status = ChatMessage.Status.RECEIVED, verdict = null)

    @Test
    fun theNewestTextsThatArentReactionsOldestFirst() {
        val messages = listOf(text(1, "hi"), text(2, "dinner?"), text(3, "Loved “dinner?”"), text(4, "7pm"), text(5, "Liked “7pm”"))
        assertEquals(listOf(2L, 4L), latestSpoken(messages, 2).map { it.id })
        assertEquals(listOf(1L, 2L, 4L), latestSpoken(messages, 10).map { it.id })
        assertEquals(emptyList<Long>(), latestSpoken(listOf(text(1, "Loved “x”")), 5).map { it.id })
    }

    @Test
    fun aLongConversationIsReadOnlyAsFarAsNeeded() {
        var read = 0
        val huge = object : AbstractList<ChatMessage>() {
            override val size = 50_000
            override fun get(index: Int): ChatMessage { read++; return text(index.toLong(), "message $index") }
        }
        assertEquals((49_980L until 50_000L).toList(), latestSpoken(huge, 20).map { it.id })
        assertEquals(20, read)
    }
}
