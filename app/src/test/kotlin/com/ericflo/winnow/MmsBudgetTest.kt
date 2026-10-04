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
}
