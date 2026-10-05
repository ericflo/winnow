package com.ericflo.winnow

import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.mmsSenders
import org.junit.Assert.assertEquals
import org.junit.Test

class MmsSendersTest {
    private fun mms(id: Long) = ChatMessage(id, 1, "", id * 1_000, outgoing = false, status = ChatMessage.Status.RECEIVED, verdict = null, kind = ChatMessage.Kind.MMS)
    private val received = (1L..10L).map(::mms)

    @Test
    fun aConversationWithOnePersonAsksTheStoreNothing() {
        var asked = 0
        val senders = mmsSenders(received, known = { null }, onlyOther = { "+12065550121" }, newest = 3, stillWanted = { true }, ask = { asked++; "x" })
        assertEquals(0, asked)
        assertEquals(setOf("+12065550121"), senders.values.toSet())
    }

    @Test
    fun aGroupAsksOnlyForItsNewestAndNeverForWhatsKnown() {
        val asked = mutableListOf<Long>()
        val senders = mmsSenders(
            received, known = { m -> "+12065550199".takeIf { m.id == 9L } }, onlyOther = { null }, newest = 3, stillWanted = { true },
            ask = { m -> asked += m.id; "+1206555012${m.id % 3}" },
        )
        // The newest three are 10, 9 and 8; 9 was known already.
        assertEquals(listOf(10L, 8L), asked)
        assertEquals("+12065550199", senders[9L])
        assertEquals(null, senders[7L])
        assertEquals(10, senders.size)
    }

    @Test
    fun askingStopsWhenNoLongerWanted() {
        var wanted = 2
        val asked = mutableListOf<Long>()
        mmsSenders(received, known = { null }, onlyOther = { null }, newest = 10, stillWanted = { wanted-- > 0 }, ask = { m -> asked += m.id; "x" })
        assertEquals(listOf(10L, 9L), asked)
    }
}
