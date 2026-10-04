package com.ericflo.winnow.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

/** A contact shared as a vCard, the way phones send contacts by MMS. */
data class VCardContact(val name: String, val phones: List<String>, val emails: List<String>)

/**
 * Just enough of vCard 2.1, 3.0 and 4.0 to show a shared contact: names, numbers and emails.
 * Handles folded lines, grouped properties (`item1.TEL`), escapes and 2.1's quoted-printable.
 */
object VCard {
    private val TYPES = setOf("text/x-vcard", "text/vcard", "text/directory")

    /** The type Winnow sends contacts as; older phones only know this one. */
    const val CONTENT_TYPE = "text/x-vcard"

    /** Longest vCard read; anything bigger is almost all photo. */
    const val MAX_BYTES = 256 * 1024

    /** Up to [limit] bytes of [input] as UTF-8 (InputStream.readNBytes is Android 13+). */
    fun read(input: InputStream, limit: Int = MAX_BYTES): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun isVCard(contentType: String): Boolean = contentType.substringBefore(';').trim().lowercase() in TYPES

    fun parse(text: String): List<VCardContact> {
        val contacts = mutableListOf<VCardContact>()
        var inCard = false
        var fullName: String? = null
        var structuredName: String? = null
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        for (line in unfold(text)) {
            val (property, params, value) = split(line) ?: continue
            when (property) {
                "BEGIN" -> if (value.equals("VCARD", ignoreCase = true)) {
                    inCard = true
                    fullName = null
                    structuredName = null
                    phones.clear()
                    emails.clear()
                }
                "END" -> if (inCard && value.equals("VCARD", ignoreCase = true)) {
                    inCard = false
                    val name = fullName?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() } ?: structuredName?.let(::nameFromParts).orEmpty()
                    if (name.isNotEmpty() || phones.isNotEmpty() || emails.isNotEmpty()) contacts += VCardContact(name, phones.toList(), emails.toList())
                }
                "FN" -> fullName = decode(value, params)
                "N" -> structuredName = decode(value, params)
                "TEL" -> unescape(decode(value, params)).trim().removePrefix("tel:").takeIf { it.isNotEmpty() && it !in phones }?.let(phones::add)
                "EMAIL" -> unescape(decode(value, params)).trim().takeIf { it.isNotEmpty() && it !in emails }?.let(emails::add)
            }
        }
        return contacts
    }

    /**
     * [text] without PHOTO or LOGO properties, which are base64 images that can be most of a
     * vCard's size and would push an MMS over the carrier's limit.
     */
    fun withoutPhotos(text: String): String =
        unfold(text).filterNot { line -> split(line)?.first in setOf("PHOTO", "LOGO") }.joinToString("\r\n", postfix = "\r\n")

    /** Logical lines: folded continuations (leading space or tab) and quoted-printable soft breaks rejoined. */
    private fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val last = lines.lastOrNull()
            when {
                last != null && (raw.startsWith(" ") || raw.startsWith("\t")) -> lines[lines.size - 1] = last + raw.substring(1)
                last != null && isQuotedPrintable(last) && last.endsWith("=") -> lines[lines.size - 1] = last.dropLast(1) + raw
                raw.isNotBlank() -> lines += raw
            }
        }
        return lines
    }

    /** `item1.TEL;TYPE=CELL:+1 555` → ("TEL", ["TYPE=CELL"], "+1 555"). */
    private fun split(line: String): Triple<String, List<String>, String>? {
        val colon = line.indexOf(':').takeIf { it > 0 } ?: return null
        val head = line.substring(0, colon).split(';')
        return Triple(head[0].substringAfterLast('.').trim().uppercase(), head.drop(1), line.substring(colon + 1))
    }

    private fun isQuotedPrintable(line: String): Boolean = split(line)?.second.orEmpty().any { isQuotedPrintableParam(it) }

    private fun isQuotedPrintableParam(param: String) =
        param.equals("ENCODING=QUOTED-PRINTABLE", ignoreCase = true) || param.equals("QUOTED-PRINTABLE", ignoreCase = true)

    private fun decode(value: String, params: List<String>): String {
        if (params.none(::isQuotedPrintableParam)) return value
        val charset = params.firstOrNull { it.startsWith("CHARSET=", ignoreCase = true) }?.substringAfter('=')
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hex = if (c == '=' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                bytes.write(hex)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(charset))
                i++
            }
        }
        return String(bytes.toByteArray(), charset)
    }

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                out.append(if (value[i + 1] == 'n' || value[i + 1] == 'N') '\n' else value[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** `N:Family;Given;Middle;Prefix;Suffix` as it would be said: "Dr. Ada M. Lovelace". */
    private fun nameFromParts(value: String): String {
        val parts = value.split(Regex("""(?<!\\);""")).map { unescape(it).trim() }
        val (family, given, middle, prefix, suffix) = (parts + List(5) { "" }).take(5)
        return listOf(prefix, given, middle, family, suffix).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
