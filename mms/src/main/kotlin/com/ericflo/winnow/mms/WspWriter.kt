package com.ericflo.winnow.mms

import java.io.ByteArrayOutputStream

/** Appends WSP-encoded values (WAP-230-WSP 8.4.2). Rejects values the encoding can't carry. */
internal class WspWriter {
    private val out = ByteArrayOutputStream()

    fun octet(value: Int) = out.write(value)

    fun bytes(value: ByteArray) = out.write(value, 0, value.size)

    fun uintvar(value: Long) {
        require(value in 0..MAX_UINTVAR) { "uintvar out of range: $value" }
        var shift = 28
        while (shift > 0 && value ushr shift == 0L) shift -= 7
        while (shift > 0) {
            octet(((value ushr shift) and 0x7F).toInt() or 0x80)
            shift -= 7
        }
        octet((value and 0x7F).toInt())
    }

    fun shortInteger(value: Int) {
        require(value in 0..0x7F) { "short-integer out of range: $value" }
        octet(0x80 or value)
    }

    fun longInteger(value: Long) {
        require(value >= 0) { "long-integer must not be negative: $value" }
        var length = 1
        while (length < 8 && value ushr (8 * length) != 0L) length++
        octet(length)
        for (i in length - 1 downTo 0) octet(((value ushr (8 * i)) and 0xFF).toInt())
    }

    fun integerValue(value: Long) = if (value in 0..0x7F) shortInteger(value.toInt()) else longInteger(value)

    fun valueLength(length: Int) {
        if (length < LENGTH_QUOTE) {
            octet(length)
        } else {
            octet(LENGTH_QUOTE)
            uintvar(length.toLong())
        }
    }

    /** Writes [block]'s output prefixed by its Value-length. */
    fun lengthPrefixed(block: WspWriter.() -> Unit) {
        val value = WspWriter().apply(block).toByteArray()
        valueLength(value.size)
        bytes(value)
    }

    /**
     * Text-string as UTF-8. A Quote (0x7F) goes first when the text starts with an octet >= 0x80,
     * as the spec requires, or with a quote character a reader would otherwise strip. NULs are dropped.
     */
    fun text(value: String) = textBytes(value.encodeToByteArray())

    fun quotedString(value: String) {
        octet(QUOTATION_MARK)
        bytes(value.encodeToByteArray().withoutNuls())
        octet(0)
    }

    /** Encoded-string-value: a plain Text-string when printable ASCII, else UTF-8 with its charset. */
    fun encodedString(value: String) {
        val utf8 = value.encodeToByteArray()
        val plain = utf8.all { it >= 0 } && (utf8.isEmpty() || utf8[0] >= 0x20)
        if (plain) {
            textBytes(utf8)
        } else {
            lengthPrefixed {
                shortInteger(MmsCharsets.UTF_8)
                textBytes(utf8)
            }
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun textBytes(value: ByteArray) {
        val clean = value.withoutNuls()
        val first = clean.firstOrNull()?.toInt()?.and(0xFF)
        if (first != null && (first >= 0x80 || first == TEXT_QUOTE || first == QUOTATION_MARK)) octet(TEXT_QUOTE)
        bytes(clean)
        octet(0)
    }

    private fun ByteArray.withoutNuls(): ByteArray = if (contains(0)) filter { it != 0.toByte() }.toByteArray() else this
}
