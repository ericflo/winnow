package com.ericflo.winnow.mms

import kotlin.test.Test
import kotlin.test.assertEquals

/** PDUs assembled by hand from OMA-MMS-ENC and WAP-230-WSP, header by header. */
class FixtureTest {
    private val notification = bytes(
        0x8C, 0x82, // X-Mms-Message-Type: m-notification-ind
        0x98, "0123456789abcdef", 0x00, // X-Mms-Transaction-ID: Text-string
        0x8D, 0x92, // X-Mms-MMS-Version: 1.2
        0x89, 0x18, 0x80, "+15551234567/TYPE=PLMN", 0x00, // From: Value-length 24, Address-present-token, Text-string
        0x96, 0x07, 0xEA, "Caf", 0xC3, 0xA9, 0x00, // Subject: Value-length 7, charset UTF-8 (0x6A), "Café"
        0x8A, 0x80, // X-Mms-Message-Class: Personal
        0x8E, 0x02, 0x1F, 0x40, // X-Mms-Message-Size: Long-integer 8000
        0x88, 0x05, 0x81, 0x03, 0x09, 0x3A, 0x80, // X-Mms-Expiry: Value-length 5, Relative-token, Long-integer 604800
        0x83, "http://mmsc.example.com/mms/abc", 0x00, // X-Mms-Content-Location: Uri-value
    )

    private val expected = NotificationInd(
        transactionId = "0123456789abcdef",
        contentLocation = "http://mmsc.example.com/mms/abc",
        from = "+15551234567",
        subject = "Café",
        messageClass = MessageClass.PERSONAL,
        messageSize = 8000,
        expiry = Expiry(604_800),
        mmsVersion = "1.2",
    )

    @Test
    fun `parses a hand-assembled m-notification-ind`() {
        assertEquals(expected, PduParser.parse(notification))
    }

    @Test
    fun `composes the same octets`() {
        assertEquals(notification.hex(), PduComposer.compose(expected).hex())
    }

    @Test
    fun `parses a notification with charset-tagged From, absolute expiry and an older version`() {
        val pdu = bytes(
            0x8C, 0x82, // X-Mms-Message-Type: m-notification-ind
            0x98, "tx-2", 0x00, // X-Mms-Transaction-ID
            0x8D, 0x90, // X-Mms-MMS-Version: 1.0
            0x89, 0x1D, 0x80, // From: Value-length 29, Address-present-token,
            0x1B, 0xEA, "+1 555-123-4567/type=plmn", 0x00, //   Encoded-string-value: Value-length 27, UTF-8, Text-string
            0x8A, 0x82, // X-Mms-Message-Class: Informational
            0x8E, 0x03, 0x04, 0x93, 0xE0, // X-Mms-Message-Size: Long-integer 300000
            0x88, 0x06, 0x80, 0x04, 0x68, 0xDF, 0x2E, 0x00, // X-Mms-Expiry: Value-length 6, Absolute-token, Long-integer
            0x83, "http://mmsc.example.com/2", 0x00, // X-Mms-Content-Location
        )
        assertEquals(
            NotificationInd(
                transactionId = "tx-2",
                contentLocation = "http://mmsc.example.com/2",
                from = "+15551234567",
                messageClass = MessageClass.INFORMATIONAL,
                messageSize = 300_000,
                expiry = Expiry(0x68DF2E00, absolute = true),
                mmsVersion = "1.0",
            ),
            PduParser.parse(pdu),
        )
    }

    @Test
    fun `unknown headers in the middle are skipped`() {
        val pdu = bytes(
            0x8C, 0x82, // X-Mms-Message-Type: m-notification-ind
            0x98, "0123456789abcdef", 0x00, // X-Mms-Transaction-ID
            0x8D, 0x92, // X-Mms-MMS-Version: 1.2
            0x89, 0x18, 0x80, "+15551234567/TYPE=PLMN", 0x00, // From
            0x8F, 0x81, // X-Mms-Priority: Normal (not modelled; a single-octet value)
            0xB7, "com.example.app", 0x00, // X-Mms-Applic-ID (not modelled; text)
            0x96, 0x07, 0xEA, "Caf", 0xC3, 0xA9, 0x00, // Subject
            0x9D, 0x06, 0x81, 0x04, 0x00, 0x01, 0x51, 0x80, // X-Mms-Reply-Charging-Deadline (not modelled; short length)
            "X-Carrier-Tag", 0x00, "opaque", 0x00, // Application-header: Token-text, Text-string
            0xFF, 0x1F, 0x28, ByteArray(40) { 0x5A }, // unassigned field 0x7F: Length-quote, uintvar 40, 40 octets
            0x8A, 0x80, // X-Mms-Message-Class: Personal
            0x8E, 0x02, 0x1F, 0x40, // X-Mms-Message-Size: 8000
            0x88, 0x05, 0x81, 0x03, 0x09, 0x3A, 0x80, // X-Mms-Expiry: relative 604800
            0x83, "http://mmsc.example.com/mms/abc", 0x00, // X-Mms-Content-Location
        )
        assertEquals(expected, PduParser.parse(pdu))
    }

    @Test
    fun `a malformed known header costs only itself`() {
        val pdu = bytes(
            0x8C, 0x82, // X-Mms-Message-Type: m-notification-ind
            0x98, "T", 0x00, // X-Mms-Transaction-ID
            0x8D, 0x92, // X-Mms-MMS-Version: 1.2
            0x88, 0x02, 0x81, 0x00, // X-Mms-Expiry: Value-length 2, Relative-token, a zero-length Long-integer
            0x89, 0x01, 0x80, // From: Address-present-token but no address
            0x8E, 0x09, ByteArray(9) { 0xFF.toByte() }, // X-Mms-Message-Size: a Long-integer past 63 bits
            0x83, "http://m/1", 0x00, // X-Mms-Content-Location
        )
        assertEquals(NotificationInd("T", "http://m/1"), PduParser.parse(pdu))
    }

    @Test
    fun `multipart retrieve-conf with wild parameter forms and unknown part headers`() {
        val textHeaders = bytes(
            0x03, 0x83, 0x81, 0xEA, // Content-Type: Value-length 3, text/plain, charset=UTF-8
            0x8E, "a.txt", 0x00, // Content-Location
            0xAE, 0x08, 0x81, 0x86, "a.txt", 0x00, // Content-Disposition: Value-length 8, attachment, filename=a.txt
            0x8D, 0x82, // Content-Length (not modelled)
            "X-Foo", 0x00, "bar", 0x00, // textual header (not modelled)
            "Content-ID", 0x00, "<t1>", 0x00, // textual Content-ID
        )
        val imageHeaders = bytes(
            0x0A, 0x9E, 0x97, "pic.jpg", 0x00, // Content-Type: Value-length 10, image/jpeg, name (WSP 1.4 token 0x17)
            0xC0, 0x22, "<img1>", 0x00, // Content-ID: Quoted-string
            0x01, 0x02, // malformed trailing header: ignored, the entry length still holds
        )
        val pdu = bytes(
            0x8C, 0x84, // X-Mms-Message-Type: m-retrieve-conf
            0x8D, 0x92, // X-Mms-MMS-Version: 1.2
            0x85, 0x04, 0x68, 0xDF, 0x2E, 0x00, // Date: Long-integer
            0x97, "+15551230001/TYPE=PLMN", 0x00, // To
            0x84, 0x0A, 0xB3, // Content-Type: Value-length 10, multipart/related,
            0x83, 0x83, //   type=text/plain as Type (0x03) with a well-known code
            0x99, 0x22, "<t1>", 0x00, //   start as a WSP 1.4 Text-value (Quoted-string)
            0x02, // nEntries
            textHeaders.size, 0x02, textHeaders, "hi", // HeadersLen, DataLen, headers, data
            imageHeaders.size, 0x04, imageHeaders, bytes(1, 2, 3, 4),
        )
        assertEquals(
            RetrieveConf(
                dateSeconds = 0x68DF2E00,
                to = listOf("+15551230001"),
                contentType = ContentTypes.MULTIPART_RELATED,
                rootType = "text/plain",
                rootContentId = "t1",
                parts = listOf(
                    MmsPart(
                        contentType = "text/plain",
                        data = "hi".encodeToByteArray(),
                        filename = "a.txt",
                        contentId = "t1",
                        contentLocation = "a.txt",
                        charset = MmsCharsets.UTF_8,
                    ),
                    MmsPart("image/jpeg", bytes(1, 2, 3, 4), name = "pic.jpg", contentId = "img1"),
                ),
            ),
            PduParser.parse(pdu),
        )
    }
}
