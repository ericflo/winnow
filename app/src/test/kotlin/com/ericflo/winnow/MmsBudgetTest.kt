package com.ericflo.winnow

import com.ericflo.winnow.sms.MmsSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsBudgetTest {
    @Test
    fun `follows the carrier's limit, less headroom`() {
        // A 1 MB carrier and AOSP's 300 KB default.
        assertEquals(1_048_576 - 52_428 - 8_000, MmsSender.budgetFor(1_048_576))
        assertEquals(307_200 - 15_360 - 8_000, MmsSender.budgetFor(307_200))
    }

    @Test
    fun `a carrier that says nothing, or something absurd, gets a sane budget`() {
        assertEquals(MmsSender.DEFAULT_BUDGET_BYTES, MmsSender.budgetFor(0))
        assertEquals(MmsSender.DEFAULT_BUDGET_BYTES, MmsSender.budgetFor(-1))
        assertEquals(2_000_000, MmsSender.budgetFor(50_000_000))
        assertEquals(100_000, MmsSender.budgetFor(20_000))
        assertTrue(MmsSender.budgetFor(600_000) > MmsSender.MIN_PHOTO_BYTES)
    }

    @Test
    fun `long texts go as MMS only where the carrier says so`() {
        // No rules (most carriers): always a text, in as many parts as it takes.
        assertEquals(false, MmsSender.textNeedsMms(segments = 9, length = 1200, segmentThreshold = -1, lengthThreshold = -1, multipart = true))
        // Past so many parts, or so many characters.
        assertEquals(false, MmsSender.textNeedsMms(segments = 4, length = 600, segmentThreshold = 4, lengthThreshold = -1, multipart = true))
        assertEquals(true, MmsSender.textNeedsMms(segments = 5, length = 700, segmentThreshold = 4, lengthThreshold = -1, multipart = true))
        assertEquals(true, MmsSender.textNeedsMms(segments = 3, length = 400, segmentThreshold = -1, lengthThreshold = 300, multipart = true))
        // A carrier that can't send a text in parts.
        assertEquals(false, MmsSender.textNeedsMms(segments = 1, length = 150, segmentThreshold = -1, lengthThreshold = -1, multipart = false))
        assertEquals(true, MmsSender.textNeedsMms(segments = 2, length = 200, segmentThreshold = -1, lengthThreshold = -1, multipart = false))
    }
}
