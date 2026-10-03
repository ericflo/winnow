package com.ericflo.winnow.classifier.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactorTest {
    private val policy = RedactionPolicy()

    @Test
    fun `masks codes, account numbers and emails but keeps shape`() {
        assertEquals(
            "Code ###### for acct ending ####. Questions? [email]",
            Redactor.redact("Code 482913 for acct ending 7781. Questions? help@bank.example.com", policy),
        )
    }

    @Test
    fun `keeps link hosts and drops paths`() {
        assertEquals(
            "Pay now: https://usps.com-track.info/… or bit.ly/… thanks",
            Redactor.redact("Pay now: https://usps.com-track.info/us/pkg?id=99 or bit.ly/3xYz thanks", policy),
        )
        assertEquals("see example.com", Redactor.redact("see example.com", policy))
    }

    @Test
    fun `everything can be turned off`() {
        val raw = "Code 482913 at a.b/c from x@y.com"
        assertEquals(raw, Redactor.redact(raw, RedactionPolicy(maskDigitRuns = false, maskEmails = false, stripUrlPaths = false)))
    }

    @Test
    fun `verification code detection`() {
        assertTrue(LocalRules.looksLikeVerificationCode("Your Uber code is 4821"))
        assertTrue(LocalRules.looksLikeVerificationCode("G-482913 is your Google verification code."))
        assertFalse(LocalRules.looksLikeVerificationCode("Lunch at 1230?"))
        assertFalse(LocalRules.looksLikeVerificationCode("Use code SAVE20 for 20% off"))
    }

    @Test
    fun `sender kinds`() {
        assertEquals(SenderKind.SHORT_CODE, SenderKind.of("72975"))
        assertEquals(SenderKind.PHONE_NUMBER, SenderKind.of("+1 (555) 555-0123"))
        assertEquals(SenderKind.ALPHANUMERIC, SenderKind.of("AMAZON"))
        assertEquals(SenderKind.EMAIL, SenderKind.of("someone@example.com"))
    }
}
