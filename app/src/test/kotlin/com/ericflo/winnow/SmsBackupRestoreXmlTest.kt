package com.ericflo.winnow

import com.ericflo.winnow.backup.ConversationBackup
import com.ericflo.winnow.backup.MessageBackup
import com.ericflo.winnow.backup.PartBackup
import com.ericflo.winnow.backup.SmsBackupRestoreXml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import org.kxml2.io.KXmlSerializer
import java.io.StringWriter
import java.io.File
import java.io.StringReader
import java.util.Base64

class SmsBackupRestoreXmlTest {
    @get:Rule val temp = TemporaryFolder()

    private val photo = byteArrayOf(-1, -40, -1, -32, 1, 2, 3, 4)

    private val xml = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <smses count="6" backup_set="x" backup_date="1791000000000">
          <sms protocol="0" address="+14155550192" date="1791000000000" type="1" subject="null" body="Are you coming tonight?" read="1" status="-1" />
          <sms protocol="0" address="+14155550192" date="1791000060000" type="2" subject="null" body="Yes! &amp; bringing snacks" read="1" status="0" />
          <sms protocol="0" address="+14155550192" date="1791000070000" type="3" subject="null" body="unsent draft" read="1" status="-1" />
          <sms protocol="0" address="72277" date="1791000080000" type="5" subject="null" body="STOP" read="1" status="-1" />
          <mms date="1791000123456" msg_box="1" address="+14155550181~+14155550182" read="0" sub="null" m_type="132">
            <parts>
              <part seq="-1" ct="application/smil" text="&lt;smil/&gt;" />
              <part seq="0" ct="text/plain" text="Lake view from the dock" />
              <part seq="1" ct="image/jpeg" fn="dock.jpg" data="${Base64.getEncoder().encodeToString(photo)}" />
            </parts>
            <addrs>
              <addr address="+14155550181" type="137" charset="106" />
              <addr address="+15551234567" type="151" charset="106" />
              <addr address="+14155550182" type="151" charset="106" />
            </addrs>
          </mms>
          <mms date="1791000200000" msg_box="2" address="null" read="1" sub="null" m_type="128">
            <parts><part seq="0" ct="text/plain" text="See you both there" /></parts>
            <addrs>
              <addr address="insert-address-token" type="137" />
              <addr address="+14155550181" type="151" />
              <addr address="+14155550182" type="151" />
            </addrs>
          </mms>
        </smses>
    """.trimIndent()

    private fun read(own: Set<String> = setOf("+15551234567")): Pair<SmsBackupRestoreXml.Result, File> {
        val spool = temp.newFolder("spool")
        val parser = KXmlParser().apply { setInput(StringReader(xml)) }
        return SmsBackupRestoreXml.read(parser, spool, own) to spool
    }

    @Test
    fun `texts, drafts skipped, statuses kept`() {
        val (result, _) = read()
        val priya = result.backup.conversations.single { it.recipients == listOf("+14155550192") }
        assertEquals(listOf("Are you coming tonight?", "Yes! & bringing snacks"), priya.messages.map { it.body })
        assertEquals("+14155550192", priya.messages[0].sender)
        assertEquals("delivered", priya.messages[1].status)
        assertNull(priya.messages[1].sender)
        assertEquals("failed", result.backup.conversations.single { it.recipients == listOf("72277") }.messages.single().status)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `a group's picture message lands in one conversation without this phone in it`() {
        val (result, spool) = read()
        val group = result.backup.conversations.single { it.recipients.size == 2 }
        assertEquals(setOf("+14155550181", "+14155550182"), group.recipients.toSet())
        assertEquals(2, group.messages.size)
        val received = group.messages[0]
        assertEquals("Lake view from the dock", received.body)
        assertEquals("+14155550181", received.sender)
        // Whole seconds, as the MMS store keeps them.
        assertEquals(1791000123000, received.date)
        assertTrue(!received.read)
        val part = received.parts.single()
        assertEquals("image/jpeg", part.contentType)
        assertEquals("dock.jpg", part.name)
        assertArrayEquals(photo, File(spool, part.file).readBytes())
        // Sent, with no address list: participants come from To, minus the placeholder.
        assertTrue(group.messages[1].outgoing)
        assertEquals("See you both there", group.messages[1].body)
    }

    @Test
    fun `without knowing this phone's number, received group texts keep everyone listed`() {
        val (result, _) = read(own = emptySet())
        // The address list doesn't include this phone, so it still groups correctly.
        assertEquals(1, result.backup.conversations.count { it.recipients.size == 2 })
    }

    @Test
    fun `emoji written as numeric surrogate pairs, as SMS Backup & Restore does, come through`() {
        val file = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <smses count="1"><sms address="+14155550192" date="1791000000000" type="1" body="Party! &#55356;&#57225;" read="1" status="-1" /></smses>"""
        val parser = KXmlParser().apply { setInput(StringReader(file)) }
        val result = SmsBackupRestoreXml.read(parser, temp.newFolder("emoji"), emptySet())
        assertEquals("Party! \uD83C\uDF89", result.backup.conversations.single().messages.single().body)
    }

    @Test
    fun `an export reads back as the same conversations`() {
        val sms = MessageBackup(kind = "sms", date = 1791000000000, outgoing = false, sender = "+14155550192", body = "Dinner at 7? <3 & \"stuff\"", read = false)
        val reply = MessageBackup(kind = "sms", date = 1791000060000, outgoing = true, body = "Yes!\nSee you", status = "delivered")
        val failed = MessageBackup(kind = "sms", date = 1791000070000, outgoing = true, body = "bell\u0007 dropped", status = "failed")
        val photo = MessageBackup(
            kind = "mms", date = 1791000123000, outgoing = false, sender = "+14155550181", body = "Dock at sunset",
            parts = listOf(PartBackup("image/jpeg", "dock.jpg", "p1")),
        )
        val groupReply = MessageBackup(kind = "mms", date = 1791000200000, outgoing = true, body = "Gorgeous", subject = "Lake")
        val conversations = listOf(
            ConversationBackup(recipients = listOf("+14155550192"), messages = listOf(sms, reply, failed)),
            ConversationBackup(recipients = listOf("+14155550181", "+14155550182"), messages = listOf(photo, groupReply)),
        )
        val out = StringWriter()
        val serializer = KXmlSerializer().apply { setOutput(out) }
        var progress = 0
        SmsBackupRestoreXml.write(serializer, conversations, ownNumber = "+15551234567", media = { if (it.file == "p1") photoBytes else null }) { done, total ->
            progress = done
            assertEquals(5, total)
        }
        assertEquals(5, progress)

        val spool = temp.newFolder("roundtrip")
        val back = SmsBackupRestoreXml.read(KXmlParser().apply { setInput(StringReader(out.toString())) }, spool, setOf("+15551234567"))
        assertEquals(0, back.skipped)
        val one = back.backup.conversations.single { it.recipients == listOf("+14155550192") }.messages
        assertEquals(listOf(sms.body, reply.body, "bell dropped"), one.map { it.body })
        assertEquals(listOf(false, true, true), one.map { it.outgoing })
        assertEquals(listOf(null, "delivered", "failed"), one.map { it.status })
        assertEquals("+14155550192", one[0].sender)
        assertTrue(!one[0].read)
        val group = back.backup.conversations.single { it.recipients.size == 2 }
        assertEquals(setOf("+14155550181", "+14155550182"), group.recipients.toSet())
        val (received, sent) = group.messages
        assertEquals("+14155550181", received.sender)
        assertEquals(photo.date, received.date)
        assertEquals("Dock at sunset", received.body)
        assertEquals("dock.jpg", received.parts.single().name)
        assertArrayEquals(photoBytes, File(spool, received.parts.single().file).readBytes())
        assertTrue(sent.outgoing)
        assertEquals("Lake", sent.subject)
        // The same fingerprints, so importing an export onto the phone it came from adds nothing.
        assertEquals(conversations.flatMap { c -> c.messages.map { it.copy(body = it.body.replace("\u0007", "")).fingerprint } }.toSet(),
            back.backup.conversations.flatMap { c -> c.messages.map { it.fingerprint } }.toSet())
    }

    private val photoBytes = byteArrayOf(-1, -40, -1, -31, 9, 8, 7)

    @Test
    fun `characters XML can't carry are cleaned up, emoji kept`() {
        val emoji = "\uD83C\uDF89"
        assertEquals("ok $emoji \uFFFD\uFFFD tab\there", SmsBackupRestoreXml.xmlSafe("ok $emoji \uD83C\uFFFF\u0001 tab\there"))
        assertEquals("\uFFFD", SmsBackupRestoreXml.xmlSafe("\uDF89"))
    }

    @Test
    fun `outbox and queued texts come back as not sent, and a text saying null is kept`() {
        val file = """<smses count="3">
            <sms address="+14155550192" date="1791000000000" type="4" body="stuck in outbox" read="1" status="-1" />
            <sms address="+14155550192" date="1791000001000" type="6" body="queued" read="1" status="-1" />
            <sms address="+14155550192" date="1791000002000" type="1" body="null" read="1" status="-1" />
            </smses>"""
        val messages = SmsBackupRestoreXml.read(KXmlParser().apply { setInput(StringReader(file)) }, temp.newFolder("boxes"), emptySet())
            .backup.conversations.single().messages
        assertEquals(listOf("failed", "failed", null), messages.map { it.status })
        assertEquals("null", messages[2].body)
    }
}
