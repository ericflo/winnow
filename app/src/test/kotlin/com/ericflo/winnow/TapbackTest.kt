package com.ericflo.winnow

import com.ericflo.winnow.data.Tapback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapbackTest {
    @Test
    fun `parses iPhone tapbacks`() {
        assertEquals(Tapback("❤️", "see you soon"), Tapback.parse("Loved “see you soon”"))
        assertEquals(Tapback("😂", "lol ok"), Tapback.parse("Laughed at “lol ok”"))
        assertEquals(Tapback("‼️", "Flight lands at 6"), Tapback.parse("Emphasized \"Flight lands at 6\""))
        assertEquals(Tapback("👍", "an image"), Tapback.parse("Liked “an image”"))
    }

    @Test
    fun `parses removals and Google Messages reactions`() {
        assertEquals(Tapback("❤️", "see you soon", removal = true), Tapback.parse("Removed a heart from “see you soon”"))
        assertEquals(Tapback("🔥", "new job!"), Tapback.parse("Reacted 🔥 to “new job!”"))
    }

    @Test
    fun `ordinary texts are not tapbacks`() {
        assertNull(Tapback.parse("Loved the movie, thanks!"))
        assertNull(Tapback.parse("I liked “Dune” a lot"))
        assertNull(Tapback.parse("Loved"))
    }

    @Test
    fun `summaries read naturally in previews`() {
        assertEquals("Reacted ❤️ to “see you soon”", Tapback.summarize("Loved “see you soon”"))
        assertEquals("Removed 😂 from “lol”", Tapback.summarize("Removed a laugh from “lol”"))
        assertEquals("just a text", Tapback.summarize("just a text"))
    }

    @Test
    fun `matches the quoted message, including truncated quotes`() {
        assertTrue(Tapback("❤️", "see you soon").matches("see you soon"))
        assertTrue(Tapback("❤️", "Lake house is booked for…").matches("Lake house is booked for the 18th!"))
        assertFalse(Tapback("❤️", "see you soon").matches("see you later"))
    }
}
