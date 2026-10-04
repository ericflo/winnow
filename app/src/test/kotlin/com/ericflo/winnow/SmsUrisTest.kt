package com.ericflo.winnow

import com.ericflo.winnow.sms.SmsUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsUrisTest {
    @Test
    fun `reads recipients and body from sms-style URIs`() {
        assertEquals(listOf("+15551234567", "+15557654321"), SmsUris.recipients("smsto", "+15551234567,+15557654321?body=hi"))
        assertEquals(listOf("+1 (415) 555-0123"), SmsUris.recipients("sms", "+1%20(415)%20555-0123"))
        assertEquals("see you at 7 🎉", SmsUris.body("sms", "+15551234567?body=see%20you%20at%207%20%F0%9F%8E%89"))
        assertEquals(listOf("4155550100"), SmsUris.recipients("MMSTO", "4155550100"))
    }

    @Test
    fun `other URIs have no recipients, like a photo shared from Files`() {
        assertEquals(emptyList<String>(), SmsUris.recipients("content", "//com.android.externalstorage.documents/document/primary:Pictures/sunset.png"))
        assertEquals(emptyList<String>(), SmsUris.recipients(null, "+15551234567"))
        assertNull(SmsUris.body("content", "//x?body=hi"))
    }
}
