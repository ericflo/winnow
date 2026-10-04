package com.ericflo.winnow

import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.data.Nudge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgeTest {
    private val day = 24 * 60 * 60_000L
    private val now = 100 * day
    private fun convo(ago: Long, asks: Boolean = true, fromMe: Boolean = false) = ConversationSummary(
        threadId = 1, recipients = listOf("+13125550101"), displayName = "Casey", snippet = "Lunch tomorrow?",
        timestamp = now - ago, unreadCount = 0, verdict = null, lastAsks = asks, lastFromMe = fromMe,
    )

    @Test
    fun `questions ask, links with query strings don't`() {
        assertTrue(Nudge.asks("Dinner Friday? I'm thinking tacos"))
        assertTrue(Nudge.asks("you coming?? 🙂"))
        assertFalse(Nudge.asks("Thanks!"))
        assertFalse(Nudge.asks("Look: https://example.com/page?id=4 for the menu"))
    }

    @Test
    fun `a contact's question waits two days, then nudges for two weeks`() {
        assertNull(Nudge.of(convo(day), now, isContact = true))
        assertEquals(Nudge.Kind.REPLY, Nudge.of(convo(3 * day), now, isContact = true))
        assertEquals(Nudge.Kind.FOLLOW_UP, Nudge.of(convo(3 * day, fromMe = true), now, isContact = true))
        assertNull(Nudge.of(convo(15 * day), now, isContact = true))
    }

    @Test
    fun `a contact's birthday nudges, unless they've been texted today`() {
        val startOfToday = now - 6 * 60 * 60_000L
        assertEquals(Nudge.Kind.BIRTHDAY, Nudge.of(convo(20 * day, asks = false), now, isContact = true, birthday = true, startOfToday = startOfToday))
        assertEquals(Nudge.Kind.BIRTHDAY, Nudge.of(convo(3 * day), now, isContact = true, birthday = true, startOfToday = startOfToday))
        assertNull(Nudge.of(convo(60 * 60_000L, asks = false, fromMe = true), now, isContact = true, birthday = true, startOfToday = startOfToday))
        assertNull(Nudge.of(convo(20 * day), now, isContact = false, birthday = true, startOfToday = startOfToday))
    }

    @Test
    fun `strangers, statements, groups, drafts and mutes don't nudge`() {
        assertNull(Nudge.of(convo(3 * day), now, isContact = false))
        assertNull(Nudge.of(convo(3 * day, asks = false), now, isContact = true))
        assertNull(Nudge.of(convo(3 * day).copy(recipients = listOf("+13125550101", "+13125550102")), now, isContact = true))
        assertNull(Nudge.of(convo(3 * day).copy(draft = "Sure, what"), now, isContact = true))
        assertNull(Nudge.of(convo(3 * day).copy(muted = true), now, isContact = true))
        assertNull(Nudge.of(convo(3 * day).copy(archived = true), now, isContact = true))
    }
}
