package com.ericflo.winnow

import com.ericflo.winnow.backup.MessageBackup
import com.ericflo.winnow.backup.matchExisting
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreMatchTest {
    // Two photos from the same person in the same second, no text: the same fingerprint.
    private val photo = MessageBackup(kind = "mms", date = 1_000_000L, outgoing = false, sender = "+14155550101")
    private val existing = mapOf(photo.fingerprint to listOf("mms:7"))

    @Test
    fun `a deleted lookalike comes back though its twin stayed`() {
        val (here, missing) = matchExisting(listOf(photo.copy(alongside = 1)), threadId = 3, existing = existing) { null }
        assertEquals(0, here.size)
        assertEquals(1, missing.size)
    }

    @Test
    fun `restored twice, it's there the second time`() {
        val both = mapOf(photo.fingerprint to listOf("mms:7", "mms:9"))
        val (here, missing) = matchExisting(listOf(photo.copy(alongside = 1)), threadId = 3, existing = both) { null }
        assertEquals(listOf("mms:9"), here.map { it.second.first })
        assertEquals(0, missing.size)
    }

    @Test
    fun `two lookalikes in a backup and one on the phone leave one missing`() {
        val (here, missing) = matchExisting(listOf(photo, photo), threadId = 3, existing = existing) { null }
        assertEquals(1, here.size)
        assertEquals(1, missing.size)
    }

    @Test
    fun `a text in another conversation counts as here, one in this one only by count`() {
        val text = MessageBackup(kind = "sms", date = 5L, outgoing = true, body = "hi", to = "+14155550102")
        val (inOther, _) = matchExisting(listOf(text), threadId = 3, existing = emptyMap()) { "sms:4" to 8L }
        assertEquals(listOf("sms:4" to 8L), inOther.map { it.second })
        // Found by the lookup in this very thread but not by count: still missing.
        val (inThis, missing) = matchExisting(listOf(text), threadId = 3, existing = emptyMap()) { "sms:4" to 3L }
        assertEquals(0, inThis.size)
        assertEquals(1, missing.size)
    }
}
