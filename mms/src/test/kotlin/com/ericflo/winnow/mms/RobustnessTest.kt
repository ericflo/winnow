package com.ericflo.winnow.mms

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.fail

class RobustnessTest {
    private val image = MmsPart("image/png", ByteArray(600) { it.toByte() }, contentId = "img", contentLocation = "img.png")
    private val text = MmsPart.plainText("héllo 👋")
    private val pdus: List<MmsPdu> = listOf(
        SendReq("T1", to = listOf("+15551230001", "a@b.c"), subject = "Sübject", parts = listOf(Smil.forParts(listOf(image, text)), image, text)),
        RetrieveConf("R1", "m1", 1_759_500_000, "+15551230009", listOf("+15551230001"), subject = "hi", parts = listOf(text, image)),
        NotificationInd("N1", "http://mmsc.example.com/x", from = "+15551230009", subject = "Ünï", messageSize = 9000, expiry = Expiry(3600)),
        SendConf("T1", messageId = "m1"),
        DeliveryInd("m1", MmsStatus.RETRIEVED, "+15551230001", 1_759_500_000),
    )
    private val samples = pdus.map(PduComposer::compose)

    /** Parses, or checks the rejection came from the reader itself rather than the parser's safety net. */
    private fun parseOrReject(bytes: ByteArray): MmsPdu? =
        try {
            PduParser.parse(bytes)
        } catch (e: MmsPduException) {
            assertNull(e.cause, "a reader bug reached the safety net on ${bytes.hex()}: ${e.cause}")
            null
        } catch (e: Throwable) {
            fail("${e::class.simpleName} escaped on ${bytes.hex()}", e)
        }

    @Test
    fun `every truncation is rejected or yields a different, partial PDU`() {
        for ((pdu, wire) in pdus.zip(samples)) {
            for (length in 0 until wire.size) {
                val parsed = parseOrReject(wire.copyOf(length))
                assertNotEquals(pdu, parsed, "a ${length}-octet prefix parsed as the whole PDU")
            }
        }
    }

    @Test
    fun `a truncated notification is always rejected`() {
        val wire = samples[2]
        for (length in 0 until wire.size) {
            assertFailsWith<MmsPduException> { PduParser.parse(wire.copyOf(length)) }
        }
    }

    @Test
    fun `random bytes`() {
        val random = Random(0x5EED)
        repeat(500) { parseOrReject(random.nextBytes(random.nextInt(0, 400))) }
    }

    @Test
    fun `random tails after a valid envelope`() {
        val random = Random(7)
        repeat(500) {
            val type = 0x80 + random.nextInt(0, 9)
            parseOrReject(bytes(0x8C, type, 0x98, "T", 0x00, 0x8D, 0x92, random.nextBytes(random.nextInt(0, 200))))
        }
    }

    @Test
    fun `mutated valid PDUs`() {
        val random = Random(99)
        repeat(1000) { i ->
            val wire = samples[i % samples.size].copyOf()
            repeat(random.nextInt(1, 5)) { wire[random.nextInt(wire.size)] = random.nextInt(256).toByte() }
            parseOrReject(wire)
        }
    }

    @Test
    fun `hostile lengths don't allocate or loop`() {
        // multipart claiming 2^32-1 entries, each claiming a 4 GiB data length
        parseOrReject(bytes(0x8C, 0x84, 0x8D, 0x92, 0x84, 0xA3, 0x8F, 0xFF, 0xFF, 0xFF, 0x7F, 0x01, 0x8F, 0xFF, 0xFF, 0xFF, 0x7F, 0x83))
        // a length-quoted header value claiming more than the PDU holds
        parseOrReject(bytes(0x8C, 0x82, 0xB7, 0x1F, 0x8F, 0xFF, 0xFF, 0xFF, 0x7F, 0x00))
        // a 30-octet long-integer date
        parseOrReject(bytes(0x8C, 0x84, 0x85, 0x1E, ByteArray(30) { 0xFF.toByte() }))
    }

    @Test
    fun `structural problems name what is wrong`() {
        assertEquals("missing X-Mms-Message-Type", assertFailsWith<MmsPduException> { PduParser.parse(ByteArray(0)) }.message)
        assertEquals(
            "missing X-Mms-Content-Location",
            assertFailsWith<MmsPduException> { PduParser.parse(bytes(0x8C, 0x82, 0x98, "T", 0x00, 0x8D, 0x92)) }.message,
        )
        assertFailsWith<MmsPduException> { PduParser.parse(bytes(0x8C, 0x83, 0x05)) }
    }
}
