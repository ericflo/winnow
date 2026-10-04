package com.ericflo.winnow

import com.ericflo.winnow.backup.MessageBackup
import com.ericflo.winnow.backup.matchExisting
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreMatchTest {
    // Two photos from the same person in the same second, no text: the same fingerprint.
    private val photo = MessageBackup(kind = "mms", date = 1_000_000L, outgoing = false, sender = "+14155550101")
    private val twinStillHere = mapOf(photo.fingerprint to listOf("mms:7"))

    @Test
    fun `a deleted photo comes back though its twin stayed`() {
        val kept = photo.copy(was = "mms:5")
        val (here, missing) = matchExisting(listOf(kept), threadId = 3, existing = twinStillHere, identity = { null }) { null }
        assertEquals(0, here.size)
        assertEquals(listOf(kept), missing)
    }

    @Test
    fun `one whose delete didn't happen is there already`() {
        val kept = photo.copy(was = "mms:5")
        val (here, missing) = matchExisting(listOf(kept), threadId = 3, existing = twinStillHere, identity = { "mms:5" }) { null }
        assertEquals(listOf("mms:5" to 3L), here.map { it.second })
        assertEquals(0, missing.size)
    }

    @Test
    fun `a backup's message is there when a lookalike is, in this conversation or (a text) another`() {
        val (inThread, _) = matchExisting(listOf(photo), threadId = 3, existing = twinStillHere, identity = { null }) { null }
        assertEquals(listOf("mms:7" to 3L), inThread.map { it.second })
        // A text sent to a group one person at a time sits in that person's conversation.
        val text = MessageBackup(kind = "sms", date = 5L, outgoing = true, body = "hi", to = "+14155550102")
        val (elsewhere, missing) = matchExisting(listOf(text), threadId = 3, existing = emptyMap(), identity = { null }) { "sms:4" to 8L }
        assertEquals(listOf("sms:4" to 8L), elsewhere.map { it.second })
        assertEquals(0, missing.size)
    }

    @Test
    fun `a mass text in a group, one row a person, isn't added again`() {
        // Same time and words to three people: one fingerprint, three rows, all here.
        val rows = listOf("+14155550101", "+14155550102", "+14155550103").map { MessageBackup(kind = "sms", date = 9L, outgoing = true, body = "Party at 8", to = it) }
        val existing = mapOf(rows[0].fingerprint to listOf("sms:21", "sms:22", "sms:23"))
        val (here, missing) = matchExisting(rows, threadId = 3, existing = existing, identity = { null }) { null }
        assertEquals(3, here.size)
        assertEquals(0, missing.size)
    }
}
