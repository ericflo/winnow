package com.ericflo.winnow

import com.ericflo.winnow.data.SimChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimChoiceTest {
    @Test
    fun `one SIM or none means Android's default`() {
        assertNull(SimChoice.pick(emptyList(), preferred = 2, lastIncoming = 2, systemDefault = 1))
        assertNull(SimChoice.pick(listOf(1), preferred = 1, lastIncoming = 1, systemDefault = 1))
    }

    @Test
    fun `the conversation's choice wins, then the SIM they last texted, then the default`() {
        val sims = listOf(1, 2)
        assertEquals(2, SimChoice.pick(sims, preferred = 2, lastIncoming = 1, systemDefault = 1))
        assertEquals(2, SimChoice.pick(sims, preferred = null, lastIncoming = 2, systemDefault = 1))
        assertEquals(1, SimChoice.pick(sims, preferred = null, lastIncoming = null, systemDefault = 1))
    }

    @Test
    fun `choices for SIMs that are gone are skipped`() {
        val sims = listOf(3, 4)
        assertEquals(4, SimChoice.pick(sims, preferred = 2, lastIncoming = 4, systemDefault = 1))
        // Nothing known is available: the first SIM.
        assertEquals(3, SimChoice.pick(sims, preferred = 2, lastIncoming = 5, systemDefault = 1))
    }
}
