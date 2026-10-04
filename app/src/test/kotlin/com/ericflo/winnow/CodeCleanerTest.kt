package com.ericflo.winnow

import com.ericflo.winnow.sms.CodeCleaner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeCleanerTest {
    @Test
    fun `codes from services are disposable`() {
        assertTrue(CodeCleaner.isDisposable("72975", "Your Northwind verification code is 482913. It expires in 10 minutes."))
        assertTrue(CodeCleaner.isDisposable("GOOGLE", "G-482913 is your Google verification code."))
    }

    @Test
    fun `people's messages are never disposable, even with a code in them`() {
        assertFalse(CodeCleaner.isDisposable("+14155550192", "the door code is 4821, see you inside"))
        assertFalse(CodeCleaner.isDisposable("4155550192", "Your verification code is 482913"))
    }

    @Test
    fun `other service messages are kept`() {
        assertFalse(CodeCleaner.isDisposable("72975", "Your package was delivered to the front porch."))
        assertFalse(CodeCleaner.isDisposable("CHASE", "Chase: A $64.20 purchase at WHOLE FOODS was made on your card ending 4412."))
    }
}
