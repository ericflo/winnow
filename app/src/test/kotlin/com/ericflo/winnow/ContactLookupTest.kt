package com.ericflo.winnow

import com.ericflo.winnow.data.ContactLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactLookupTest {
    @Test
    fun `numbers written different ways match`() {
        val key = ContactLookup.numberKey("+1 415-555-0192")
        assertEquals("4155550192", key)
        assertEquals(key, ContactLookup.numberKey("(415) 555-0192"))
        assertEquals(key, ContactLookup.numberKey("14155550192"))
        assertEquals(ContactLookup.numberKey("+44 7700 900123"), ContactLookup.numberKey("07700 900123"))
        assertEquals("5550133", ContactLookup.numberKey("555-0133"))
    }

    @Test
    fun `short codes match exactly, and emails and names aren't matched by digits`() {
        assertEquals("short:72277", ContactLookup.numberKey("72277"))
        assertNull(ContactLookup.numberKey("someone@example.com"))
        assertNull(ContactLookup.numberKey("AMAZON"))
    }
}
