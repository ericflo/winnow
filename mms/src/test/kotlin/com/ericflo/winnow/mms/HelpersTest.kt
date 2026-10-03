package com.ericflo.winnow.mms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelpersTest {
    @Test
    fun `addresses normalize to clean numbers`() {
        assertEquals("+15551234567", MmsAddress.normalize("+1 (555) 123-4567/TYPE=PLMN"))
        assertEquals("+15551234567", MmsAddress.normalize("+15551234567/type=plmn"))
        assertEquals("72975", MmsAddress.normalize("72975"))
        assertEquals("ann@example.com", MmsAddress.normalize(" ann@example.com "))
        assertEquals("AMAZON", MmsAddress.normalize("AMAZON"))
        assertEquals("10.0.0.1/TYPE=IPV4", MmsAddress.normalize("10.0.0.1/TYPE=IPV4"))
    }

    @Test
    fun `addresses encode with a PLMN suffix only for phone numbers`() {
        assertEquals("+15551234567/TYPE=PLMN", MmsAddress.encode("+1 555.123.4567"))
        assertEquals("+15551234567/TYPE=PLMN", MmsAddress.encode("+15551234567/TYPE=PLMN"))
        assertEquals("72975/TYPE=PLMN", MmsAddress.encode("72975"))
        assertEquals("ann@example.com", MmsAddress.encode("ann@example.com"))
        assertEquals("AMAZON", MmsAddress.encode("AMAZON"))
        assertEquals("10.0.0.1/TYPE=IPV4", MmsAddress.encode("10.0.0.1/TYPE=IPV4"))
    }

    @Test
    fun `part text decodes by charset`() {
        assertEquals("héllo", MmsPart("text/plain", "héllo".encodeToByteArray()).text)
        assertEquals("Ä", MmsPart("text/plain", byteArrayOf(0xC4.toByte()), charset = MmsCharsets.ISO_8859_1).text)
        assertEquals("hi", MmsPart("TEXT/PLAIN", "hi".encodeToByteArray(), charset = 9999).text, "unknown charsets fall back to UTF-8")
        assertNull(MmsPart("image/png", byteArrayOf(1)).text)
    }

    @Test
    fun `parts compare by content`() {
        val a = MmsPart("image/png", byteArrayOf(1, 2), contentId = "x")
        val b = MmsPart("image/png", byteArrayOf(1, 2), contentId = "x")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b.copy(data = byteArrayOf(1, 3)))
        assertNotEquals(a, b.copy(contentId = "y"))
        assertTrue("2 bytes" in a.toString())
    }

    @Test
    fun `charset names`() {
        assertEquals(MmsCharsets.UTF_8, MmsCharsets.mibOf("utf-8"))
        assertEquals(MmsCharsets.ISO_8859_1, MmsCharsets.mibOf("latin1"))
        assertEquals(1013, MmsCharsets.mibOf("UTF-16BE"))
        assertNull(MmsCharsets.mibOf("no-such-charset"))
        assertNull(MmsCharsets.mibOf("\u0001"))
        assertEquals(Charsets.UTF_8, MmsCharsets.charsetOf(null))
    }

    @Test
    fun `smil puts an image and its caption on one slide`() {
        val image = MmsPart("image/jpeg", byteArrayOf(1), contentLocation = "photo.jpg")
        val text = MmsPart.plainText("hi", contentLocation = "text0.txt")
        val smil = Smil.forParts(listOf(image, text))
        assertEquals(ContentTypes.SMIL, smil.contentType)
        assertEquals("smil", smil.contentId)
        assertEquals("smil.xml", smil.contentLocation)
        assertEquals(
            """<smil><head><layout><root-layout width="100%" height="100%"/>""" +
                """<region id="Image" left="0" top="0%" width="100%" height="70%" fit="meet"/>""" +
                """<region id="Text" left="0" top="70%" width="100%" height="30%" fit="scroll"/>""" +
                """</layout></head><body><par dur="5000ms"><img src="photo.jpg" region="Image"/>""" +
                """<text src="text0.txt" region="Text"/></par></body></smil>""",
            smil.data.decodeToString(),
        )
    }

    @Test
    fun `smil starts a new slide when a slot is taken`() {
        val xml = Smil.forParts(
            listOf(
                Smil.forParts(emptyList()),
                MmsPart("image/gif", byteArrayOf(), name = "a.gif"),
                MmsPart("image/png", byteArrayOf(), filename = "b&c.png"),
                MmsPart.plainText("caption", contentId = "t", contentLocation = null),
                MmsPart("audio/amr", byteArrayOf(), contentLocation = "s.amr"),
                MmsPart("video/mp4", byteArrayOf(), contentLocation = "v.mp4"),
                MmsPart("text/x-vCard", byteArrayOf(), contentLocation = "c.vcf"),
            ),
        ).data.decodeToString()
        assertEquals(
            """<body><par dur="5000ms"><img src="a.gif" region="Image"/></par>""" +
                """<par><img src="b&amp;c.png" region="Image"/><text src="cid:t" region="Text"/><audio src="s.amr"/></par>""" +
                """<par><video src="v.mp4" region="Image"/></par>""" +
                """<par dur="5000ms"><ref src="c.vcf"/></par></body>""",
            xml.substring(xml.indexOf("<body>"), xml.indexOf("</smil>")),
        )
    }

    @Test
    fun `smil needs a way to reference each part`() {
        assertFailsWith<IllegalArgumentException> { Smil.forParts(listOf(MmsPart("image/png", byteArrayOf()))) }
    }
}
