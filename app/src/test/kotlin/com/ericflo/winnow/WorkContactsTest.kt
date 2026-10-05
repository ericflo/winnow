package com.ericflo.winnow

import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.ContactLookup.Companion.WORK_ANSWER_MILLIS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkContactsTest {
    @Test
    fun anAnswerStandsForAWhileThenIsAskedAgain() {
        assertTrue(ContactLookup.workAnswerStands(at = 0, contact = false, now = WORK_ANSWER_MILLIS - 1, paused = false))
        assertFalse(ContactLookup.workAnswerStands(at = 0, contact = false, now = WORK_ANSWER_MILLIS, paused = false))
        assertFalse(ContactLookup.workAnswerStands(at = 0, contact = true, now = WORK_ANSWER_MILLIS, paused = false))
    }

    @Test
    fun aColleagueStaysOneWhileWorkAppsArePaused() {
        // Android hides work contacts then: asking again would make a colleague a stranger.
        assertTrue(ContactLookup.workAnswerStands(at = 0, contact = true, now = 10 * WORK_ANSWER_MILLIS, paused = true))
        // A number found to be no one is asked about again, paused or not.
        assertFalse(ContactLookup.workAnswerStands(at = 0, contact = false, now = 10 * WORK_ANSWER_MILLIS, paused = true))
    }
}
