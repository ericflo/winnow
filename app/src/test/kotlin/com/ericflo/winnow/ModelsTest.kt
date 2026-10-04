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
}
