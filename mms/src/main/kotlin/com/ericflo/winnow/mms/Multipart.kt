package com.ericflo.winnow.mms

/**
 * WSP multipart bodies (WAP-230-WSP 8.5): uintvar nEntries, then per entry uintvar headersLength,
 * uintvar dataLength, the content type and part headers, and the data.
 */
internal object Multipart {
    // Well-known part header fields (WAP-230-WSP Table 39), with the short-integer high bit set.
    private const val CONTENT_LOCATION = 0x8E
    private const val CONTENT_DISPOSITION = 0xAE
    private const val CONTENT_ID = 0xC0
    private const val CONTENT_DISPOSITION_V14 = 0xC5

    fun read(reader: WspReader): List<MmsPart> {
        val count = reader.uintvar()
        val parts = ArrayList<MmsPart>()
        while (parts.size < count) {
            val headersLength = reader.uintvarLength()
            val dataLength = reader.uintvarLength()
            val headers = reader.sub(headersLength)
            parts += part(headers, reader.bytes(dataLength))
        }
        return parts
    }

    fun write(writer: WspWriter, parts: List<MmsPart>) {
        writer.uintvar(parts.size.toLong())
        for (part in parts) {
            val headers = WspWriter().apply {
                contentType(ContentType(part.contentType, part.charset, part.name, part.filename))
                part.contentLocation?.let { octet(CONTENT_LOCATION); text(it) }
                part.contentId?.let { octet(CONTENT_ID); quotedString("<${it.withoutAngles()}>") }
            }.toByteArray()
            writer.uintvar(headers.size.toLong())
            writer.uintvar(part.data.size.toLong())
            writer.bytes(headers)
            writer.bytes(part.data)
        }
    }

    private fun part(headers: WspReader, data: ByteArray): MmsPart {
        val ct = headers.contentType()
        var contentId: String? = null
        var contentLocation: String? = null
        var dispositionFilename: String? = null
        // The entry's length is known, so a malformed part header loses only the headers after it.
        try {
            while (headers.hasMore()) {
                val field = headers.peek()
                when {
                    field >= 0x80 -> when (headers.octet()) {
                        CONTENT_LOCATION -> contentLocation = headers.text()
                        CONTENT_ID -> contentId = headers.text().withoutAngles()
                        CONTENT_DISPOSITION, CONTENT_DISPOSITION_V14 -> dispositionFilename = headers.dispositionFilename()
                        else -> headers.skipValue()
                    }
                    field >= 0x20 -> when (headers.text().lowercase()) {
                        "content-id" -> contentId = headers.text().withoutAngles()
                        "content-location" -> contentLocation = headers.text()
                        else -> headers.skipValue()
                    }
                    else -> break
                }
            }
        } catch (e: MmsPduException) {
        }
        return MmsPart(
            contentType = ct.mediaType,
            data = data,
            name = ct.name,
            filename = ct.filename ?: dispositionFilename,
            contentId = contentId,
            contentLocation = contentLocation,
            charset = ct.charset,
        )
    }

    /** Content-disposition-value: Value-length Disposition *(Parameter). */
    private fun WspReader.dispositionFilename(): String? {
        if (peek() > LENGTH_QUOTE) return null.also { skipValue() }
        val value = lengthPrefixed()
        value.skipValue()
        val params = value.parameters(ContentType(""))
        return params.filename ?: params.name
    }
}
