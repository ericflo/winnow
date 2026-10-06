package com.ericflo.winnow

import com.ericflo.winnow.sms.MmsReceiver.Companion.participantsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who a received picture message is between: this phone left out, however its number is known. */
class MmsParticipantsTest {
    private val family = setOf(setOf("2065550121", "2065550122", "2065550123"))
    private fun none(): Set<Set<String>> = error("not asked: the phone's number settles it")

    @Test
    fun `a known number among the recipients is left out`() {
        val p = participantsOf("+12065550121", listOf("+15555215554", "+12065550122", "+12065550123"), emptyList(), setOf("5555215554"), ::none)
        assertEquals(listOf("+12065550121", "+12065550122", "+12065550123"), p.people)
        assertFalse(p.unaddressed)
    }

    @Test
    fun `a known number that isn't among them is doubted, and the existing group is found instead`() {
        val p = participantsOf("+12065550122", listOf("+15555215554", "+12065550121", "+12065550123"), emptyList(), setOf("5551234567")) { family }
        assertEquals(listOf("+12065550122", "+12065550121", "+12065550123"), p.people)
        assertTrue(p.unaddressed)
    }

    @Test
    fun `with the number unknown, a group goes in the one conversation that fits`() {
        val p = participantsOf("+12065550121", listOf("+15555215554", "+12065550122", "+12065550123"), emptyList(), emptySet()) { family }
        assertEquals(listOf("+12065550121", "+12065550122", "+12065550123"), p.people)
        assertFalse("nothing known to doubt", p.unaddressed)
    }

    @Test
    fun `with no conversation that fits, or two, everyone is kept`() {
        val to = listOf("+15555215554", "+14155550172")
        assertEquals(listOf("+14155550171") + to, participantsOf("+14155550171", to, emptyList(), emptySet()) { emptySet() }.people)
        // Leaving out either recipient fits a conversation: no telling which is this phone.
        val both = setOf(setOf("4155550171", "4155550172"), setOf("4155550171", "5555215554"))
        assertEquals(listOf("+14155550171") + to, participantsOf("+14155550171", to, emptyList(), emptySet()) { both }.people)
    }

    @Test
    fun `a one to one message is with its sender`() {
        val p = participantsOf("+14155550161", listOf("+15555215554"), emptyList(), emptySet(), ::none)
        assertEquals(listOf("+14155550161"), p.people)
        assertFalse(p.unaddressed)
    }

    @Test
    fun `numbers written two ways are one person`() {
        val p = participantsOf("2065550121", listOf("+15555215554", "+1 206 555 0122", "(206) 555-0123"), emptyList(), setOf("5555215554"), ::none)
        assertEquals(3, p.people.size)
    }
}
