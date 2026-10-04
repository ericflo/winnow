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

    @Test
    fun `home-country numbers drop their country code for display`() {
        assertEquals("4155550177", ContactLookup.nationalForm("+14155550177", "US"))
        assertEquals("4155550177", ContactLookup.nationalForm("+1 (415) 555-0177", "CA"))
        assertEquals("4155550177", ContactLookup.nationalForm("14155550177", "US"))
        // Already national, another country's, or not a full number: left as is.
        assertEquals(null, ContactLookup.nationalForm("4155550177", "US"))
        assertEquals(null, ContactLookup.nationalForm("+447700900123", "US"))
        assertEquals(null, ContactLookup.nationalForm("+14155550177", "GB"))
        assertEquals(null, ContactLookup.nationalForm("72975", "US"))
    }

    @Test
    fun `a contact's full photo is found from its thumbnail`() {
        assertEquals(
            "content://com.android.contacts/contacts/42/display_photo",
            ContactLookup.displayPhoto("content://com.android.contacts/contacts/42/photo"),
        )
        // Anything else has no known full-size counterpart.
        assertEquals(null, ContactLookup.displayPhoto("content://com.android.contacts/display_photo/7"))
        assertEquals(null, ContactLookup.displayPhoto("content://media/external/images/1"))
        assertEquals(null, ContactLookup.displayPhoto(null))
    }
}
