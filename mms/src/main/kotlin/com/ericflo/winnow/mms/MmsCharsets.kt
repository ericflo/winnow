package com.ericflo.winnow.mms

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** IANA MIBenum charset numbers, as carried by Encoded-string-values and charset parameters. */
object MmsCharsets {
    const val US_ASCII = 3
    const val ISO_8859_1 = 4
    const val UTF_8 = 106
    const val UTF_16 = 1015
    internal const val ISO_2022_JP = 39

    // Ordered so reverse lookups prefer the modern number (UTF-16BE is 1013, not UCS-2's 1000).
    private val names = linkedMapOf(
        UTF_8 to "UTF-8",
        US_ASCII to "US-ASCII",
        ISO_8859_1 to "ISO-8859-1",
        5 to "ISO-8859-2",
        6 to "ISO-8859-3",
        7 to "ISO-8859-4",
        8 to "ISO-8859-5",
        9 to "ISO-8859-6",
        10 to "ISO-8859-7",
        11 to "ISO-8859-8",
        12 to "ISO-8859-9",
        17 to "Shift_JIS",
        18 to "EUC-JP",
        38 to "EUC-KR",
        39 to "ISO-2022-JP",
        109 to "ISO-8859-13",
        111 to "ISO-8859-15",
        113 to "GBK",
        114 to "GB18030",
        1013 to "UTF-16BE",
        1014 to "UTF-16LE",
        UTF_16 to "UTF-16",
        1000 to "UTF-16BE",
        2025 to "GB2312",
        2026 to "Big5",
        2084 to "KOI8-R",
        2250 to "windows-1250",
        2251 to "windows-1251",
        2252 to "windows-1252",
    )

    /** The JVM charset for [mib], falling back to UTF-8 when it is unknown or unavailable. */
    fun charsetOf(mib: Int?): Charset =
        names[mib]?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

    /** The MIBenum for a charset name or alias such as "utf-8" or "latin1", or null when unknown. */
    fun mibOf(name: String): Int? {
        val canonical = runCatching { Charset.forName(name.trim()).name() }.getOrNull() ?: return null
        return names.entries.firstOrNull { it.value.equals(canonical, ignoreCase = true) }?.key
    }

    /** Decodes [bytes] in [mib]'s charset; malformed input becomes replacement characters. */
    fun decode(bytes: ByteArray, mib: Int?): String = String(bytes, charsetOf(mib))

    internal fun isWide(mib: Int): Boolean = mib == 1000 || mib in 1013..UTF_16

    /**
     * A subject (or other encoded string) as Android's MMS store keeps it, the way its own MMS
     * code writes one: the encoded bytes as an ISO-8859-1 string, the charset beside it
     * (sub_cs, here [UTF_8]). Other apps then read it back correctly.
     */
    fun forStore(text: String): String = String(text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    /**
     * [raw] from the MMS store, read back: its characters are the bytes of [mib]'s encoding. One
     * that isn't (an app that stored plain text, as Winnow once did) is returned as it is.
     */
    fun fromStore(raw: String, mib: Int?): String {
        // Anything past U+00FF can't be bytes.
        if (raw.any { it.code > 0xFF }) return raw
        // UTF-16's bytes are often below 0x80 (00 48 is "H"), and ISO-2022-JP is 7-bit escapes:
        // for those, ASCII-looking text is still bytes to decode. For the rest it reads the same.
        val wide = mib != null && isWide(mib)
        if (!wide && mib != ISO_2022_JP && raw.all { it.code < 0x80 }) return raw
        if (wide && raw.length % 2 != 0) return raw
        // No charset (null, or 0 for "any") is what Android's own MMS code reads as UTF-8.
        val charset = if (mib == null || mib == 0) Charsets.UTF_8 else names[mib]?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: return raw
        if (charset == Charsets.ISO_8859_1 || charset == Charsets.US_ASCII) return raw
        return runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw.toByteArray(Charsets.ISO_8859_1)))
                .toString()
        }.getOrDefault(raw)
    }
}
