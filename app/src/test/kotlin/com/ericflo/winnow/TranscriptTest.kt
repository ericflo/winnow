package com.ericflo.winnow

import com.ericflo.winnow.data.Attachment
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.Transcript
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class TranscriptTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `days, times, names, attachments and multi-line texts`() {
        val messages = listOf(
            ChatMessage(3, 1, "Booked! Two nights.\nCheck-in at 4", at(3, 9, 5), outgoing = false, status = ChatMessage.Status.RECEIVED, verdict = null, sender = "+14155550192"),
            ChatMessage(1, 1, "Lake house this weekend?", at(2, 20, 30), outgoing = true, status = ChatMessage.Status.SENT, verdict = null),
            ChatMessage(
                4, 1, "", at(3, 9, 6), outgoing = false, status = ChatMessage.Status.RECEIVED, verdict = null, kind = ChatMessage.Kind.MMS,
                sender = "+14155550192", attachments = listOf(Attachment("content://mms/part/9", "image/jpeg", "dock.jpg")),
            ),
        )
        val text = Transcript.render(
            "Priya Natarajan",
            messages,
            nameOf = { if (it.outgoing) "You" else "Priya" },
            exportedOn = LocalDate.of(2026, 10, 4),
            zone = zone,
            locale = Locale.US,
        )
        assertEquals(
            """
            Conversation with Priya Natarajan
            Exported from Winnow on October 4, 2026

            Friday, October 2, 2026
            8:30 PM  You: Lake house this weekend?

            Saturday, October 3, 2026
            9:05 AM  Priya: Booked! Two nights.
                            Check-in at 4
            9:06 AM  Priya: [Photo: dock.jpg]

            """.trimIndent(),
            text,
        )
    }
}
