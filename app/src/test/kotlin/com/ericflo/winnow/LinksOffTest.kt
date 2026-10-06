package com.ericflo.winnow

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.StoredVerdict
import com.ericflo.winnow.ui.thread.ThreadUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksOffTest {
    private val phishing = StoredVerdict(Category.SPAM, 0.9, Action.FILTER, "Classified on this phone")
    private fun text(id: Long, verdict: StoredVerdict?, outgoing: Boolean = false, sender: String? = null) =
        ChatMessage(id, 1, "Pay now: ezpass-tolls.top/pay", id, outgoing, ChatMessage.Status.RECEIVED, verdict, sender = sender)

    @Test
    fun `an unjudged text from a sender who sent phishing here has its links off`() {
        val old = text(1, verdict = null)
        val new = text(2, verdict = phishing)
        val state = ThreadUiState("(318) 555-0182", null, listOf("+13185550182"), messages = listOf(old, new))
        assertTrue(state.linksOff(new))
        assertTrue(state.linksOff(old))
        // The user's own messages are theirs.
        assertFalse(state.linksOff(text(3, verdict = null, outgoing = true)))
    }

    @Test
    fun `one person whose number came written two ways isn't a group`() {
        val a = text(1, verdict = null, sender = "+14155550100")
        val b = text(2, verdict = null, sender = "4155550100")
        assertFalse(ThreadUiState("(415) 555-0100", null, listOf("+14155550100"), messages = listOf(a, b)).showsSenders)
        val other = text(3, verdict = null, sender = "+14155550101")
        assertTrue("two people are", ThreadUiState("x", null, listOf("+14155550100"), messages = listOf(a, b, other)).showsSenders)
    }

    @Test
    fun `not once the user says it isn't spam`() {
        val old = text(1, verdict = null)
        val cleared = text(2, verdict = phishing.copy(userAction = Action.ALLOW))
        val state = ThreadUiState("(318) 555-0182", null, listOf("+13185550182"), messages = listOf(old, cleared))
        assertFalse(state.linksOff(old))
        assertFalse(state.linksOff(cleared))
    }

    @Test
    fun `in a group only that sender's texts`() {
        val members = listOf("+14155550181", "+14155550182")
        val bad = text(1, verdict = phishing, sender = "+14155550181")
        val friend = text(2, verdict = null, sender = "(415) 555-0182")
        val badAgain = text(3, verdict = null, sender = "4155550181")
        val state = ThreadUiState("Group", null, members, messages = listOf(bad, friend, badAgain))
        assertFalse(state.linksOff(friend))
        assertTrue(state.linksOff(badAgain))
    }
}
