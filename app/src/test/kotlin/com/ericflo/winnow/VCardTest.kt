package com.ericflo.winnow

import com.ericflo.winnow.data.VCard
import com.ericflo.winnow.data.VCardContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardTest {
    @Test
    fun `reads an iPhone-style 3_0 card`() {
        val card = """
            BEGIN:VCARD
            VERSION:3.0
            N:Natarajan;Priya;;;
            FN:Priya Natarajan
            item1.TEL;type=pref:(415) 555-0192
            item1.X-ABLabel:mobile
            TEL;type=WORK;type=VOICE:+1 415 555 0100
            EMAIL;type=INTERNET:priya@example.com
            END:VCARD
        """.trimIndent()
        assertEquals(
            listOf(VCardContact("Priya Natarajan", listOf("(415) 555-0192", "+1 415 555 0100"), listOf("priya@example.com"))),
            VCard.parse(card),
        )
    }

    @Test
    fun `reads Android's 2_1 export with quoted-printable names and folded lines`() {
        val card = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Garc=C3=ADa;Jos=C3=A9;;;\r\n" +
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Jos=C3=A9 Garc=\r\n=C3=ADa\r\nTEL;CELL:555-0133\r\nEND:VCARD\r\n"
        assertEquals(listOf(VCardContact("José García", listOf("555-0133"), emptyList())), VCard.parse(card))
    }

    @Test
    fun `falls back to the structured name and unescapes values`() {
        val card = "BEGIN:VCARD\nVERSION:4.0\nN:Lovelace;Ada;M.;Dr.;\nORG:Analytical\\, Engines\nTEL;VALUE=uri:tel:+15550142\nEND:VCARD"
        assertEquals(listOf(VCardContact("Dr. Ada M. Lovelace", listOf("+15550142"), emptyList())), VCard.parse(card))
        assertEquals("Smith, Jr.", VCard.parse("BEGIN:VCARD\nFN:Smith\\, Jr.\nEND:VCARD").single().name)
    }

    @Test
    fun `several cards in one file, and junk is ignored`() {
        val two = "BEGIN:VCARD\nFN:One\nTEL:555-0101\nEND:VCARD\nBEGIN:VCARD\nFN:Two\nEND:VCARD\n"
        assertEquals(listOf("One", "Two"), VCard.parse(two).map { it.name })
        assertTrue(VCard.parse("hello there").isEmpty())
        assertTrue(VCard.parse("BEGIN:VCARD\nVERSION:3.0\nEND:VCARD").isEmpty())
    }

    @Test
    fun `photos are stripped, everything else kept`() {
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Pic Person\r\nPHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRgABAQ\r\n AAAQABAAD/2wBDAAgGBgcGBQgH\r\n BwcJCQgKDBQNDAsLDBkSEw8U\r\nTEL:555-0144\r\nEND:VCARD\r\n"
        val stripped = VCard.withoutPhotos(card)
        assertFalse(stripped.contains("PHOTO") || stripped.contains("/9j/") || stripped.contains("BwcJ"))
        assertEquals(VCard.parse(card), VCard.parse(stripped))
        assertTrue(stripped.contains("TEL:555-0144"))
    }

    @Test
    fun `content types`() {
        assertTrue(VCard.isVCard("text/x-vcard"))
        assertTrue(VCard.isVCard("text/vcard; charset=utf-8"))
        assertTrue(VCard.isVCard("TEXT/X-VCARD"))
        assertFalse(VCard.isVCard("text/plain"))
    }
}
