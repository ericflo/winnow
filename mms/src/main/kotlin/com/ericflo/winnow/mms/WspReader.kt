package com.ericflo.winnow.mms

internal const val LENGTH_QUOTE = 31
internal const val TEXT_QUOTE = 0x7F
internal const val QUOTATION_MARK = 0x22
internal const val MAX_UINTVAR = 0xFFFFFFFFL

/**
 * A bounds-checked cursor over WSP-encoded octets (WAP-230-WSP 8.4.2). Every malformation
 * surfaces as an [MmsPduException], never as an index or arithmetic error.
 */
internal class WspReader(private val buf: ByteArray, start: Int = 0, private val end: Int = buf.size) {
    private var pos = start

    val remaining: Int get() = end - pos

    fun hasMore(): Boolean = pos < end

    fun peek(): Int {
        if (pos >= end) throw MmsPduException("unexpected end of PDU")
        return buf[pos].toInt() and 0xFF
    }

    fun octet(): Int = peek().also { pos++ }

    fun bytes(count: Int): ByteArray {
        checkLength(count)
        return buf.copyOfRange(pos, pos + count).also { pos += count }
    }

    fun rest(): ByteArray = bytes(remaining)

    /** Splits the next [length] octets off as their own reader and moves past them. */
    fun sub(length: Int): WspReader {
        checkLength(length)
        return WspReader(buf, pos, pos + length).also { pos += length }
    }

    fun uintvar(): Long {
        var value = 0L
        repeat(5) {
            val b = octet()
            value = (value shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) {
                if (value > MAX_UINTVAR) throw MmsPduException("uintvar exceeds 32 bits")
                return value
            }
        }
        throw MmsPduException("uintvar longer than 5 octets")
    }

    /** A uintvar used as a length or count. */
    fun uintvarLength(): Int {
        val value = uintvar()
        if (value > Int.MAX_VALUE) throw MmsPduException("length $value is too large")
        return value.toInt()
    }

    fun shortInteger(): Int {
        val b = octet()
        if (b < 0x80) throw MmsPduException("expected a short-integer, found 0x%02x".format(b))
        return b and 0x7F
    }

    fun longInteger(): Long {
        val length = octet()
        if (length !in 1..30) throw MmsPduException("bad long-integer length $length")
        var value = 0L
        repeat(length) {
            if (value ushr 55 != 0L) throw MmsPduException("long-integer exceeds 63 bits")
            value = (value shl 8) or octet().toLong()
        }
        return value
    }

    fun integerValue(): Long = if (peek() >= 0x80) shortInteger().toLong() else longInteger()

    fun valueLength(): Int {
        val b = octet()
        return when {
            b < LENGTH_QUOTE -> b
            b == LENGTH_QUOTE -> uintvarLength()
            else -> throw MmsPduException("expected a value-length, found 0x%02x".format(b))
        }
    }

    /** A reader over exactly the next Value-length-prefixed value. */
    fun lengthPrefixed(): WspReader = sub(valueLength())

    /**
     * Octets up to the next NUL, minus a leading Quote (0x7F) or quotation mark (and that
     * mark's closing twin). Covers Text-string, Token-text, Quoted-string and Text-value alike.
     */
    fun textBytes(): ByteArray {
        var stop = pos
        while (stop < end && buf[stop] != ZERO) stop++
        if (stop == end) throw MmsPduException("unterminated text")
        var from = pos
        var to = stop
        val first = if (from < to) buf[from].toInt() and 0xFF else -1
        if (first == TEXT_QUOTE || first == QUOTATION_MARK) from++
        if (first == QUOTATION_MARK && to > from && buf[to - 1].toInt() == QUOTATION_MARK) to--
        pos = stop + 1
        return buf.copyOfRange(from, to)
    }

    fun text(): String = textBytes().decodeToString()

    /** Encoded-string-value: a Text-string, or Value-length Char-set Text-string. */
    fun encodedString(): String {
        if (peek() > LENGTH_QUOTE) return text()
        val value = lengthPrefixed()
        if (!value.hasMore()) return ""
        val charset = value.charset()
        return MmsCharsets.decode(value.rest().stripTerminator(charset), charset)
    }

    /** Well-known-charset (an Integer-value; 0 is "any") or a charset name, which is 0 when unknown. */
    fun charset(): Int {
        val b = peek()
        if (b >= 0x80 || b < LENGTH_QUOTE) return integerValue().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return MmsCharsets.mibOf(text()) ?: 0
    }

    /** The next value of any type, delimited as [skipValue] does, as its own reader. */
    fun value(): WspReader {
        val start = pos
        skipValue()
        return WspReader(buf, start, pos)
    }

    /** Skips a value of unknown type by the generic WSP rules: length-prefixed, text, or one octet. */
    fun skipValue() {
        val b = peek()
        when {
            b <= LENGTH_QUOTE -> lengthPrefixed()
            b < 0x80 -> textBytes()
            else -> pos++
        }
    }

    private fun checkLength(length: Int) {
        if (length !in 0..remaining) throw MmsPduException("length $length overruns the $remaining octets left")
    }

    private fun ByteArray.stripTerminator(charset: Int): ByteArray {
        val wide = MmsCharsets.isWide(charset)
        val from = if (!wide && isNotEmpty() && this[0].toInt() == TEXT_QUOTE) 1 else 0
        var to = size
        if (to > from && this[to - 1] == ZERO) to--
        if (wide && (to - from) % 2 == 1 && this[to - 1] == ZERO) to--
        return copyOfRange(from, to)
    }

    private companion object {
        const val ZERO: Byte = 0
    }
}
