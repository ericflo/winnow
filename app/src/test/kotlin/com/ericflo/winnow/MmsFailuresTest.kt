package com.ericflo.winnow

import com.ericflo.winnow.sms.MmsFailures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsFailuresTest {
    @Test
    fun androidsMmsErrorsSayWhatWentWrong() {
        assertEquals("mobile data is off, and picture messages come over mobile data", MmsFailures.describe(11, 0))
        assertEquals("the carrier's MMS server refused it (HTTP 404)", MmsFailures.describe(4, 404))
        assertEquals("no mobile data network to download it over", MmsFailures.describe(8, 0))
        // One this Android doesn't name still says what it was.
        assertTrue(MmsFailures.describe(42, 500).contains("code 42") && MmsFailures.describe(42, 500).contains("HTTP 500"))
    }
}
