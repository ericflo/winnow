package com.ericflo.winnow

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.data.StoredVerdict
import com.ericflo.winnow.data.attachmentSummary
import com.ericflo.winnow.data.db.ConversationStateEntity
import com.ericflo.winnow.data.displayNameFor
import com.ericflo.winnow.data.joinAddresses
import com.ericflo.winnow.data.normalizeAddress
import com.ericflo.winnow.data.splitAddresses
import com.ericflo.winnow.data.withState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun `addresses normalize so the same sender matches however it's written`() {
        assertEquals("4155550123", normalizeAddress("+1 (415) 555-0123"))
        assertEquals("4155550123", normalizeAddress("14155550123"))
        assertEquals("4155550123", normalizeAddress("415-555-0123"))
        assertEquals("72975", normalizeAddress("72975"))
        assertEquals("AMAZON", normalizeAddress("Amazon"))
    }

    @Test
    fun `recipient lists survive the route round trip`() {
        val people = listOf("+14155550181", "+14155550182")
        assertEquals(people, splitAddresses(joinAddresses(people)))
        assertEquals(emptyList<String>(), splitAddresses(""))
    }

    @Test
    fun `group names use first names where known and numbers otherwise`() {
        val names = mapOf("1" to "Alex Chen", "2" to "Priya Natarajan", "3" to "(555) 555-0199")
        assertEquals("Alex Chen", displayNameFor(listOf("1")) { names.getValue(it) })
        assertEquals("Alex, Priya, (555) 555-0199", displayNameFor(listOf("1", "2", "3")) { names.getValue(it) })
    }

    @Test
    fun `pinned conversations sort first, then by recency, and state is applied`() {
        fun c(id: Long, at: Long) = ConversationSummary(id, listOf("+1555555010$id"), "c$id", "", at, 0, null)
        val merged = listOf(c(1, 300), c(2, 200), c(3, 100)).withState(
            mapOf(
                3L to ConversationStateEntity(3, pinned = true),
                2L to ConversationStateEntity(2, archived = true, draft = "brb"),
            ),
        )
        assertEquals(listOf(3L, 1L, 2L), merged.map { it.threadId })
        assertTrue(merged.first().pinned)
        assertEquals("brb", merged.last().draft)
        assertTrue(merged.last().archived)
    }

    @Test
    fun `fraud verdicts stay fraud until the user allows them`() {
        val phishing = StoredVerdict(Category.PHISHING, 0.98, Action.FILTER, "test")
        assertTrue(phishing.isFraud)
        assertFalse(phishing.copy(userAction = Action.ALLOW).isFraud)
        assertFalse(StoredVerdict(Category.POLITICAL, 0.98, Action.FILTER, "test").isFraud)
    }

    @Test
    fun `attachment-only previews say what's attached`() {
        assertEquals("Photo", attachmentSummary(listOf("image/jpeg")))
        assertEquals("Contact", attachmentSummary(listOf("text/x-vcard")))
        assertEquals("Voice message", attachmentSummary(listOf("audio/amr")))
        assertEquals("2 photos", attachmentSummary(listOf("image/jpeg", "image/png")))
        assertEquals("3 attachments", attachmentSummary(listOf("image/jpeg", "video/mp4", "text/vcard")))
        assertEquals("Attachment", attachmentSummary(listOf("application/pdf")))
    }

    @Test
    fun `a timed mute ends on its own`() {
        val now = 1_000_000L
        assertTrue(ConversationStateEntity(1, muted = true).isMuted(now))
        assertTrue(ConversationStateEntity(1, muted = true, mutedUntil = now + 1).isMuted(now))
        assertFalse(ConversationStateEntity(1, muted = true, mutedUntil = now).isMuted(now))
        assertFalse(ConversationStateEntity(1, muted = false, mutedUntil = now + 1).isMuted(now))
    }

    @Test
    fun `muted labels say until when`() {
        val zone = java.time.ZoneId.of("UTC")
        val today = java.time.LocalDate.of(2026, 10, 3)
        fun at(day: Int, hour: Int) = java.time.ZonedDateTime.of(2026, 10, day, hour, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Muted", com.ericflo.winnow.ui.components.mutedLabel(null, today, zone))
        assertTrue(com.ericflo.winnow.ui.components.mutedLabel(at(3, 18), today, zone).startsWith("Muted until 6:30"))
        assertTrue(com.ericflo.winnow.ui.components.mutedLabel(at(4, 9), today, zone).startsWith("Muted until tomorrow, 9:30"))
        assertTrue(com.ericflo.winnow.ui.components.mutedLabel(at(9, 9), today, zone).startsWith("Muted until Oct 9, 9:30"))
    }

    @Test
    fun `placeholder MMS subjects aren't shown`() {
        assertEquals(null, com.ericflo.winnow.data.meaningfulSubject(null))
        assertEquals(null, com.ericflo.winnow.data.meaningfulSubject("  "))
        assertEquals(null, com.ericflo.winnow.data.meaningfulSubject("NoSubject"))
        assertEquals(null, com.ericflo.winnow.data.meaningfulSubject("<no subject>"))
        assertEquals(null, com.ericflo.winnow.data.meaningfulSubject("<Subject>"))
        assertEquals("Dinner Friday", com.ericflo.winnow.data.meaningfulSubject(" Dinner Friday "))
    }

    @Test
    fun `an MMS is classified on its subject and its text`() {
        assertEquals("URGENT: account locked\nVerify at x.top", com.ericflo.winnow.data.subjectAndText("URGENT: account locked", "Verify at x.top"))
        assertEquals("Only the subject", com.ericflo.winnow.data.subjectAndText("Only the subject", " "))
        assertEquals("Just text", com.ericflo.winnow.data.subjectAndText("NoSubject", "Just text"))
        assertEquals("", com.ericflo.winnow.data.subjectAndText(null, ""))
    }

    @Test
    fun `a group's avatar shows faces before blank glyphs`() {
        fun m(address: String, name: String, photo: String? = null) = com.ericflo.winnow.data.Member(address, name, photo)
        val stranger1 = m("+12065550150", "(206) 555-0150")
        val stranger2 = m("+12065550151", "(206) 555-0151")
        val casey = m("+12065550142", "Casey Lin")
        val morgan = m("+14155550177", "Morgan Reyes", photo = "content://photo/1")
        fun faces(members: List<com.ericflo.winnow.data.Member>) = com.ericflo.winnow.data.groupFaces(members)
        // A photo in front, then a name.
        assertEquals(listOf(morgan, casey), faces(listOf(stranger1, casey, stranger2, morgan)))
        // A named contact before a bare number.
        assertEquals(listOf(casey, stranger1), faces(listOf(stranger1, stranger2, casey)))
        // All alike: by number, whatever order they come in.
        val stranger3 = m("+12065550152", "(206) 555-0152")
        assertEquals(listOf(stranger1, stranger2), faces(listOf(stranger3, stranger2, stranger1)))
        assertEquals(faces(listOf(casey, morgan, stranger1)), faces(listOf(stranger1, morgan, casey)))
    }

    @Test
    fun `a search result starts near what was found`() {
        val long = "Hey! Long day at work, the train was late again and then the meeting ran over, but are we still on for dinner tomorrow?"
        val snippet = com.ericflo.winnow.data.searchSnippet(long, "dinner")
        assertTrue(snippet, snippet.startsWith("…"))
        assertTrue(snippet, snippet.contains("dinner tomorrow?"))
        // Never half a word.
        assertTrue(snippet, long.contains(" " + snippet.removePrefix("…")))
        // Near the start: as it is, on one line.
        assertEquals("Dinner Friday? I'm in", com.ericflo.winnow.data.searchSnippet("Dinner Friday?\nI'm in", "friday"))
        assertEquals("no match here", com.ericflo.winnow.data.searchSnippet("no match here", "zebra"))
    }

    @Test
    fun `email addresses are told from numbers and names`() {
        fun email(a: String) = com.ericflo.winnow.data.isEmailAddress(a)
        assertTrue(email("ann@example.com"))
        assertTrue(email(" first.last+tag@mail.example.co.uk "))
        assertFalse(email("4155550177"))
        assertFalse(email("AMAZON"))
        assertFalse(email("ann@example"))
        assertFalse(email("ann @example.com"))
        assertFalse(email("a@b.c,d@e.f"))
        assertFalse(email("pat@example.com."))
        assertFalse(email("a@b..com"))
        assertFalse(email("mailto:pat@example.com"))
        assertFalse(email("12@34.56"))
        assertTrue(com.ericflo.winnow.data.ContactLookup.isReachable("ann@example.com"))
        assertFalse(com.ericflo.winnow.data.ContactLookup.isReachable("72975"))
    }
}
