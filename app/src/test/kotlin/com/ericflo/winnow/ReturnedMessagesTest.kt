package com.ericflo.winnow

import com.ericflo.winnow.data.OutgoingAttachment
import com.ericflo.winnow.data.ReturnedMessages
import com.ericflo.winnow.data.ReturnedMessages.Companion.appendTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReturnedMessagesTest {
    @Test
    fun `a returned text goes on a line of its own after the draft`() {
        assertEquals("Running late\nSee you at 8", appendTo("Running late", "See you at 8"))
        assertEquals("See you at 8", appendTo("", "See you at 8"))
        assertEquals("See you at 8", appendTo("   ", "See you at 8"))
    }

    @Test
    fun `one already saved into the draft isn't added twice`() {
        assertEquals("See you at 8", appendTo("See you at 8", "See you at 8"))
        assertEquals("Running late\nSee you at 8", appendTo("Running late\nSee you at 8", "See you at 8"))
    }

    @Test
    fun `only a whole trailing line counts as already there`() {
        // "Yes" isn't in "Yesterday was fun", and the end of a longer line isn't it either.
        assertEquals("Yesterday was fun\nYes", appendTo("Yesterday was fun", "Yes"))
        assertEquals("Oh yes\nyes", appendTo("Oh yes", "yes"))
        assertEquals("Hello", appendTo("Hello", " "))
    }

    @Test
    fun `failures for the same conversation merge until it's opened`() {
        val returned = ReturnedMessages()
        val photo = OutgoingAttachment("file:///drafts/a.jpg", "image/jpeg", null)
        val clip = OutgoingAttachment("file:///drafts/b.mp4", "video/mp4", null)
        returned.put(7, ReturnedMessages.Returned("first", listOf(photo), separately = false))
        returned.put(7, ReturnedMessages.Returned("second", listOf(clip), separately = true))
        val taken = returned.take(7)!!
        assertEquals("first\nsecond", taken.text)
        assertEquals(listOf(photo, clip), taken.attachments)
        assertEquals(true, taken.separately)
        assertNull(returned.take(7))
    }

    @Test
    fun `a returned subject joins the one being written`() {
        assertEquals("Dinner", ReturnedMessages.mergeSubjects("Dinner", null))
        assertEquals("Dinner", ReturnedMessages.mergeSubjects("Dinner", " "))
        assertEquals("Dinner", ReturnedMessages.mergeSubjects("Dinner", "Dinner"))
        // Saved with the draft and read back already: not twice.
        assertEquals("Dinner · Movie", ReturnedMessages.mergeSubjects("Dinner", "Dinner · Movie"))
        assertEquals("Dinner · Movie", ReturnedMessages.mergeSubjects("Dinner", "Movie"))
        assertEquals("Movie", ReturnedMessages.mergeSubjects(null, "Movie"))
        assertNull(ReturnedMessages.mergeSubjects(null, null))
    }

    @Test
    fun `two failures keep both subjects`() {
        val returned = ReturnedMessages()
        returned.put(7, ReturnedMessages.Returned("first", emptyList(), separately = false, subject = "Dinner"))
        returned.put(7, ReturnedMessages.Returned("second", emptyList(), separately = false, subject = "Movie"))
        assertEquals("Dinner · Movie", returned.take(7)!!.subject)
    }
}
