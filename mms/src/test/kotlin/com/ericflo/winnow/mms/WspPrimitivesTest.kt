package com.ericflo.winnow.mms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WspPrimitivesTest {
    private fun write(block: WspWriter.() -> Unit) = WspWriter().apply(block).toByteArray().hex()

    private fun reader(vararg items: Any) = WspReader(bytes(*items))

    @Test
    fun `uintvar edge cases`() {
        val cases = mapOf(
            0L to bytes(0x00),
            127L to bytes(0x7F),
            128L to bytes(0x81, 0x00),
            16383L to bytes(0xFF, 0x7F),
            16384L to bytes(0x81, 0x80, 0x00),
            0xFFFFFFFFL to bytes(0x8F, 0xFF, 0xFF, 0xFF, 0x7F),
        )
        for ((value, wire) in cases) {
            assertEquals(wire.hex(), write { uintvar(value) }, "encode $value")
            assertEquals(value, WspReader(wire).uintvar(), "decode $value")
        }
    }

    @Test
    fun `uintvar rejects overlong and oversized values`() {
        assertFailsWith<MmsPduException> { reader(0x81, 0x80, 0x80, 0x80, 0x80, 0x00).uintvar() }
        assertFailsWith<MmsPduException> { reader(0x9F, 0xFF, 0xFF, 0xFF, 0x7F).uintvar() }
        assertFailsWith<MmsPduException> { reader(0x81).uintvar() }
    }

    @Test
    fun `value-length switches to the length quote at 31`() {
        assertEquals("00", write { valueLength(0) })
        assertEquals("1E", write { valueLength(30) })
        assertEquals("1F 1F", write { valueLength(31) })
        assertEquals("1F 81 00", write { valueLength(128) })
        assertEquals(30, reader(0x1E).valueLength())
        assertEquals(31, reader(0x1F, 0x1F).valueLength())
        assertEquals(128, reader(0x1F, 0x81, 0x00).valueLength())
        assertFailsWith<MmsPduException> { reader(0x20).valueLength() }
    }

    @Test
    fun `long-integer uses the fewest octets`() {
        assertEquals("01 00", write { longInteger(0) })
        assertEquals("01 FF", write { longInteger(255) })
        assertEquals("02 01 00", write { longInteger(256) })
        assertEquals("04 65 53 F1 00", write { longInteger(1_700_000_000) })
        assertEquals("08 7F FF FF FF FF FF FF FF", write { longInteger(Long.MAX_VALUE) })
        assertEquals(1_700_000_000, reader(0x04, 0x65, 0x53, 0xF1, 0x00).longInteger())
        assertEquals(Long.MAX_VALUE, reader(0x08, 0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF).longInteger())
        assertEquals(5, reader(0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5).longInteger(), "leading zero octets are fine")
        assertFailsWith<MmsPduException> { reader(0x08, 0x80, 0, 0, 0, 0, 0, 0, 0).longInteger() }
        assertFailsWith<MmsPduException> { reader(0x00).longInteger() }
        assertFailsWith<IllegalArgumentException> { write { longInteger(-1) } }
    }

    @Test
    fun `short-integer and integer-value`() {
        assertEquals("80", write { shortInteger(0) })
        assertEquals("FF", write { shortInteger(127) })
        assertEquals("FF", write { integerValue(127) })
        assertEquals("01 80", write { integerValue(128) })
        assertEquals(106, reader(0xEA).shortInteger())
        assertEquals(1015, reader(0x02, 0x03, 0xF7).integerValue())
        assertFailsWith<MmsPduException> { reader(0x7F).shortInteger() }
    }

    @Test
    fun `text-string quotes a leading high octet`() {
        assertEquals("61 62 63 00", write { text("abc") })
        assertEquals("00", write { text("") })
        assertEquals("7F C3 A9 74 C3 A9 00", write { text("été") })
        assertEquals("61 C3 A9 00", write { text("aé") }, "only the first octet decides")
        assertEquals("7F 22 61 00", write { text("\"a") }, "a leading quote character is protected too")
        assertEquals("été", reader(0x7F, 0xC3, 0xA9, 0x74, 0xC3, 0xA9, 0x00).text())
        assertEquals("\"a", reader(0x7F, 0x22, 0x61, 0x00).text())
        assertEquals("abc", reader("abc", 0x00).text())
        assertFailsWith<MmsPduException> { reader("abc").text() }
    }

    @Test
    fun `quoted-string`() {
        assertEquals("22 3C 61 3E 00", write { quotedString("<a>") })
        assertEquals("<a>", reader(0x22, "<a>", 0x00).text())
        assertEquals("<a>", reader(0x22, "<a>\"", 0x00).text(), "a closing quote is dropped too")
    }

    @Test
    fun `encoded-string-value is plain for ASCII and UTF-8 otherwise`() {
        assertEquals("48 69 00", write { encodedString("Hi") })
        assertEquals("07 EA 43 61 66 C3 A9 00", write { encodedString("Café") })
        assertEquals("08 EA 7F C3 A9 74 C3 A9 00", write { encodedString("été") })
        assertEquals("Café", reader(0x07, 0xEA, "Caf", 0xC3, 0xA9, 0x00).encodedString())
        assertEquals("Hi", reader("Hi", 0x00).encodedString())
        assertEquals("Ä", reader(0x03, 0x84, 0xC4, 0x00).encodedString(), "ISO-8859-1")
        assertEquals("Hé", reader(0x07, 0x02, 0x03, 0xF7, 0x00, 0x48, 0x00, 0xE9).encodedString(), "UTF-16 long-form charset")
        assertEquals("Hé", reader(0x09, 0x02, 0x03, 0xF7, 0x00, 0x48, 0x00, 0xE9, 0x00, 0x00).encodedString())
        assertEquals("ok", reader(0x09, "utf-8", 0x00, "ok", 0x00).encodedString(), "charset by name")
        val long = "Ünïcödé subject that is longer than thirty octets"
        val wire = WspWriter().apply { encodedString(long) }.toByteArray()
        assertEquals(LENGTH_QUOTE, wire[0].toInt())
        assertEquals(long, WspReader(wire).encodedString())
    }

    @Test
    fun `skipValue follows the generic length rules`() {
        val r = reader(
            0x83, // one octet
            0x02, 0xAA, 0xBB, // short length
            0x1F, 0x22, *Array(34) { 0x41 }, // length quote
            "text", 0x00, // text
            0x99,
        )
        repeat(4) { r.skipValue() }
        assertEquals(0x99, r.octet())
    }

    @Test
    fun `sub-readers stay inside their bounds`() {
        val r = reader(0x02, 0x61, 0x62, 0x63, 0x00)
        val value = r.lengthPrefixed()
        assertFailsWith<MmsPduException> { value.text() }
        assertEquals(0x63, r.octet())
        assertFailsWith<MmsPduException> { reader(0x05, 0x01).lengthPrefixed() }
    }

    @Test
    fun `well-known content types table`() {
        assertEquals(0x03, WellKnownContentTypes.code("text/plain"))
        assertEquals(0x1D, WellKnownContentTypes.code("image/gif"))
        assertEquals(0x1E, WellKnownContentTypes.code("IMAGE/JPEG"))
        assertEquals(0x20, WellKnownContentTypes.code("image/png"))
        assertEquals(0x23, WellKnownContentTypes.code(ContentTypes.MULTIPART_MIXED))
        assertEquals(0x26, WellKnownContentTypes.code(ContentTypes.MULTIPART_ALTERNATIVE))
        assertEquals(0x33, WellKnownContentTypes.code(ContentTypes.MULTIPART_RELATED))
        assertEquals(0x52, WellKnownContentTypes.code("application/mikey"))
        assertEquals(null, WellKnownContentTypes.code(ContentTypes.SMIL))
        assertEquals(ContentTypes.OCTET_STREAM, WellKnownContentTypes.name(0x53))
    }

    @Test
    fun `content type encodings`() {
        assertEquals("9E", write { contentType(ContentType("image/jpeg")) })
        assertEquals(
            bytes("application/smil", 0x00).hex(),
            write { contentType(ContentType(ContentTypes.SMIL)) },
        )
        assertEquals("03 83 81 EA", write { contentType(ContentType("text/plain", charset = 106)) })
        assertEquals(
            bytes(0x1B, 0xB3, 0x8A, "<smil>", 0x00, 0x89, "application/smil", 0x00).hex(),
            write { contentType(ContentType(ContentTypes.MULTIPART_RELATED, type = ContentTypes.SMIL, start = "smil")) },
        )
    }

    @Test
    fun `content type parameter forms seen in the wild`() {
        fun parse(vararg items: Any) = WspReader(bytes(*items)).contentType()
        assertEquals(ContentType("image/png"), parse(0xA0))
        assertEquals(ContentType("image/x-ms-bmp"), parse("image/x-ms-bmp", 0x00))
        // multipart/related with type as a well-known code (0x83 Type) and start as a Text-value (0x99)
        assertEquals(
            ContentType(ContentTypes.MULTIPART_RELATED, type = "text/plain", start = "t1"),
            parse(0x0A, 0xB3, 0x83, 0x83, 0x99, 0x22, "<t1>", 0x00),
        )
        // type via 0x89 as text, start via 0x8A without angle brackets
        assertEquals(
            ContentType(ContentTypes.MULTIPART_RELATED, type = "application/smil", start = "smil"),
            parse(0x19, 0xB3, 0x89, "application/smil", 0x00, 0x8A, "smil", 0x00),
        )
        // name and filename, old (0x85/0x86) and WSP 1.4 (0x97/0x98) tokens, Q, an unknown typed parameter
        assertEquals(
            ContentType("image/jpeg", name = "a.jpg", filename = "b.jpg"),
            parse(0x14, 0x9E, 0x80, 0x64, 0x85, "a.jpg", 0x00, 0x98, 0x22, "b.jpg", 0x00, 0x96, 0x82),
        )
        // untyped parameters and a long-form media code
        assertEquals(
            ContentType("text/plain", charset = 106, name = "n"),
            parse(0x17, 0x01, 0x03, "charset", 0x00, "utf-8", 0x00, "name", 0x00, "n", 0x00),
        )
        // a malformed trailing parameter keeps what parsed before it
        assertEquals(ContentType("text/plain", charset = 4), parse(0x04, 0x83, 0x81, 0x84, 0x1F))
    }
}
