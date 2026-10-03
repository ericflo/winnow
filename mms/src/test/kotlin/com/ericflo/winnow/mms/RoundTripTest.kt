package com.ericflo.winnow.mms

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoundTripTest {
    private val image = MmsPart(
        contentType = "image/jpeg",
        data = ByteArray(20_000) { (it * 31 + 7).toByte() },
        name = "photo.jpg",
        filename = "photo.jpg",
        contentId = "image0",
        contentLocation = "photo.jpg",
    )
    private val text = MmsPart.plainText("Dinner at 8? 🍝 ¿Sí? 東京", contentId = "text0", contentLocation = "text0.txt")

    private fun roundTrip(pdu: MmsPdu): ByteArray {
        val wire = PduComposer.compose(pdu)
        val parsed = PduParser.parse(wire)
        assertEquals(pdu, parsed)
        assertContentEquals(wire, PduComposer.compose(parsed), "re-encoding is stable")
        return wire
    }

    @Test
    fun `send-req with smil, utf-8 text, an image and three recipients`() {
        val smil = Smil.forParts(listOf(image, text))
        val req = SendReq(
            transactionId = "T7d3673e01",
            to = listOf("+15551230001", "+15551230002", "friend@example.com"),
            cc = listOf("+442071234567"),
            bcc = listOf("hidden@example.org"),
            subject = "Ünïcödé subject that is longer than thirty octets 🎉",
            dateSeconds = 1_759_500_000,
            deliveryReport = true,
            readReport = false,
            parts = listOf(smil, image, text),
        )
        val wire = roundTrip(req)

        assertTrue(wire.indexOf(bytes(0x97, "+15551230001/TYPE=PLMN", 0x00)) > 0)
        assertTrue(wire.indexOf(bytes(0x97, "friend@example.com", 0x00)) > 0, "emails carry no type suffix")
        assertTrue(wire.indexOf(bytes(0x89, 0x01, 0x81)) > 0, "From: insert-address-token")
        assertTrue(
            wire.indexOf(bytes(0x84, 0x1B, 0xB3, 0x8A, "<smil>", 0x00, 0x89, "application/smil", 0x00, 0x03)) > 0,
            "multipart/related; start=<smil>; type=application/smil; three entries",
        )
        val envelope = bytes(0x8C, 0x80, 0x98, "T7d3673e01", 0x00, 0x8D, 0x92)
        assertContentEquals(envelope, wire.copyOfRange(0, envelope.size), "type, transaction id, version lead")

        val parsed = PduParser.parse(wire) as SendReq
        assertEquals("Dinner at 8? 🍝 ¿Sí? 東京", parsed.parts[2].text)
        assertEquals(20_000, parsed.parts[1].data.size)
    }

    @Test
    fun `send-req without smil is multipart mixed, with an explicit sender`() {
        val req = SendReq(
            transactionId = "abc",
            to = listOf("+15551230001"),
            from = "+15559870000",
            messageClass = null,
            parts = listOf(image),
            mmsVersion = "1.3",
        )
        val wire = roundTrip(req)
        assertTrue(wire.indexOf(bytes(0x84, 0xA3, 0x01)) > 0)
        assertTrue(wire.indexOf(bytes(0x89, 0x18, 0x80, "+15559870000/TYPE=PLMN", 0x00)) > 0)
        assertTrue(wire.indexOf(bytes(0x8D, 0x93)) > 0)
    }

    @Test
    fun `retrieve-conf with from, to, cc and a non-ASCII subject`() {
        val smil = Smil.forParts(listOf(image, text))
        roundTrip(
            RetrieveConf(
                transactionId = "R-42",
                messageId = "msg-0001@mmsc.example.com",
                dateSeconds = 1_759_499_999,
                from = "+15551230009",
                to = listOf("+15551230001", "+15551230002"),
                cc = listOf("pal@example.com"),
                subject = "Grüße aus Köln — 東京 🚆",
                contentType = ContentTypes.MULTIPART_RELATED,
                rootType = ContentTypes.SMIL,
                rootContentId = "smil",
                parts = listOf(smil, image, text),
            ),
        )
    }

    @Test
    fun `retrieve-conf variants`() {
        roundTrip(RetrieveConf(dateSeconds = 0, retrieveStatus = ResponseStatus.ERROR_PERMANENT_FAILURE + 4))
        roundTrip(RetrieveConf(contentType = ContentTypes.MULTIPART_MIXED, parts = listOf(text, image.copy(charset = null))))
        roundTrip(
            RetrieveConf(
                contentType = "text/plain",
                parts = listOf(MmsPart("text/plain", "just text".encodeToByteArray(), charset = MmsCharsets.US_ASCII)),
            ),
        )
        roundTrip(
            RetrieveConf(
                parts = listOf(
                    MmsPart("text/x-vCard", "BEGIN:VCARD".encodeToByteArray(), name = "card.vcf"),
                    MmsPart("image/heic", byteArrayOf(1, 2, 3), contentLocation = "IMG_0001.HEIC"),
                    MmsPart("audio/amr", ByteArray(0), contentId = "a1"),
                ),
            ),
        )
    }

    @Test
    fun `send-conf`() {
        roundTrip(SendConf("T1", ResponseStatus.OK, messageId = "0123@mmsc"))
        roundTrip(SendConf("T2", ResponseStatus.ERROR_TRANSIENT_FAILURE + 3, responseText = "Réseau indisponible"))
    }

    @Test
    fun `notification-ind`() {
        roundTrip(
            NotificationInd(
                transactionId = "N1",
                contentLocation = "http://mmsc.example.com/get?id=1&x=2",
                from = "alerts@example.com",
                subject = "Photos 📷",
                messageClass = MessageClass.ADVERTISEMENT,
                messageSize = 302_144,
                expiry = Expiry(1_760_000_000, absolute = true),
                mmsVersion = "1.3",
            ),
        )
        roundTrip(NotificationInd("N2", "http://m/2", expiry = Expiry(604_800)))
        roundTrip(NotificationInd("N3", "http://m/3", messageClass = "custom-class"))
    }

    @Test
    fun `notifyresp-ind, acknowledge-ind and delivery-ind`() {
        roundTrip(NotifyRespInd("T1"))
        roundTrip(NotifyRespInd("T2", MmsStatus.DEFERRED, reportAllowed = false))
        roundTrip(AcknowledgeInd("T3"))
        roundTrip(AcknowledgeInd("T4", reportAllowed = true))
        roundTrip(DeliveryInd("msg-1", MmsStatus.RETRIEVED, to = "+15551230001", dateSeconds = 1_759_500_100))
        roundTrip(DeliveryInd("msg-2", MmsStatus.EXPIRED))
    }

    @Test
    fun `small PDUs are byte-exact`() {
        assertEquals(
            bytes(0x8C, 0x83, 0x98, "TID123", 0x00, 0x8D, 0x92, 0x95, 0x81).hex(),
            PduComposer.compose(NotifyRespInd("TID123")).hex(),
        )
        assertEquals(
            bytes(0x8C, 0x85, 0x98, "TID123", 0x00, 0x8D, 0x92, 0x91, 0x80).hex(),
            PduComposer.compose(AcknowledgeInd("TID123", reportAllowed = true)).hex(),
        )
    }

    @Test
    fun `unsupported message types parse to their envelope and are refused by the composer`() {
        // m-read-orig-ind
        val pdu = PduParser.parse(bytes(0x8C, 0x88, 0x8D, 0x92, 0x8B, "id", 0x00, 0x9B, 0x80))
        assertEquals(UnsupportedPdu(0x88, null, "1.2"), pdu)
        assertFailsWith<IllegalArgumentException> { PduComposer.compose(pdu) }
    }

    @Test
    fun `composer rejects values the wire can't carry`() {
        assertFailsWith<IllegalArgumentException> { PduComposer.compose(NotifyRespInd("T", mmsVersion = "2.15")) }
        assertFailsWith<IllegalArgumentException> { PduComposer.compose(NotifyRespInd("T", mmsVersion = "one")) }
        assertFailsWith<IllegalArgumentException> { PduComposer.compose(NotifyRespInd("T", status = 0x10)) }
        assertFailsWith<IllegalArgumentException> { PduComposer.compose(SendReq("T", dateSeconds = -1)) }
        assertFailsWith<IllegalArgumentException> {
            PduComposer.compose(RetrieveConf(contentType = "text/plain", parts = listOf(text, text)))
        }
    }

    @Test
    fun `mms version forms`() {
        assertEquals("1.0", (PduParser.parse(PduComposer.compose(AcknowledgeInd("T", mmsVersion = "1.0"))) as AcknowledgeInd).mmsVersion)
        assertEquals("1", PduParser.parse(bytes(0x8C, 0x85, 0x98, "T", 0x00, 0x8D, 0x9F)).mmsVersion)
        assertIs<AcknowledgeInd>(PduParser.parse(PduComposer.compose(AcknowledgeInd("T", mmsVersion = "1"))))
    }
}
