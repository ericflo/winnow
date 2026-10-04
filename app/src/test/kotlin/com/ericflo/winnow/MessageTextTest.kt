package com.ericflo.winnow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import com.ericflo.winnow.ui.components.allWebLinks
import com.ericflo.winnow.ui.components.isEmojiOnly
import com.ericflo.winnow.ui.components.linkify
import com.ericflo.winnow.ui.components.shortTimestamp
import com.ericflo.winnow.ui.thread.scheduleLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MessageTextTest {
    private fun links(text: String, enabled: Boolean = true) =
        linkify(text, enabled, Color.Blue).getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }

    @Test
    fun `a sentence break isn't a link`() {
        assertEquals(emptyList<String>(), links("Ok.Coming over now"))
        assertEquals(emptyList<String>(), links("Thanks.Me too"))
        assertEquals(emptyList<String>(), links("Gate 5.Usually it's late"))
        assertEquals(listOf("https://STORE.COM/deals"), links("VISIT STORE.COM/deals"))
        assertEquals(listOf("https://Google.com"), links("search Google.com"))
        assertEquals(listOf("https://bit.ly/x"), links("bit.ly/x"))
    }

    @Test
    fun `links open with a scheme Android matches`() {
        assertEquals(listOf("https://BIT.LY/x"), allWebLinks("HTTPS://BIT.LY/x"))
        assertEquals(listOf("https://httpbin.org"), allWebLinks("try httpbin.org"))
        assertEquals(listOf("http://example.com/a"), allWebLinks("Http://example.com/a."))
    }

    @Test
    fun `finds web links, emails and phone numbers`() {
        assertEquals(
            listOf("mailto:help@example.com", "https://example.com/a", "tel:4155550123"),
            links("Mail help@example.com or see example.com/a, or call (415) 555-0123."),
        )
        assertEquals(listOf("https://www.example.org"), links("https://www.example.org"))
    }

    @Test
    fun `fraud text gets no links at all`() {
        assertEquals(emptyList<String>(), links("Pay now: ezpass-tolls.top/pay", enabled = false))
        assertEquals("Pay now: ezpass-tolls.top/pay", linkify("Pay now: ezpass-tolls.top/pay", false, Color.Blue).text)
    }

    @Test
    fun `emoji-only detection`() {
        assertTrue(isEmojiOnly("😂"))
        assertTrue(isEmojiOnly("👍🏽"))
        assertTrue(isEmojiOnly("❤️🔥"))
        assertFalse(isEmojiOnly("ok 👍"))
        assertFalse(isEmojiOnly("😂😂😂😂"))
        assertFalse(isEmojiOnly(""))
    }

    @Test
    fun `list timestamps read like Messages`() {
        val now = System.currentTimeMillis()
        assertEquals("Now", shortTimestamp(now - 20_000, now))
        assertEquals("6 min", shortTimestamp(now - 6 * 60_000, now))
    }

    @Test
    fun `schedule labels name today and tomorrow`() {
        val today = LocalDate.of(2026, 10, 3)
        fun at(date: LocalDate, hour: Int) = LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(scheduleLabel(at(today, 18), today).startsWith("Today, "))
        assertTrue(scheduleLabel(at(today.plusDays(1), 8), today).startsWith("Tomorrow, "))
        assertTrue(scheduleLabel(at(today.plusDays(3), 9), today).startsWith("Tue, Oct 6"))
    }
}
