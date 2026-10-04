package com.ericflo.winnow.data

import java.text.Normalizer

/**
 * Settings → Simple characters: a text goes in 160-character parts only while every character
 * is in the GSM alphabet. One curly quote, long dash or accent outside it (a keyboard's smart
 * punctuation, a pasted name) sends the whole message in 70-character parts, so it can take
 * twice the texts. This swaps those for plain ones, but only where that makes the message take
 * fewer texts: a short message, or one with an emoji (which needs the long form anyway), goes
 * as typed. Pure Kotlin, so it's unit-tested without Android.
 */
object SimpleCharacters {
    /** How a carrier would send [text]: in how many parts, and whether in the 7-bit alphabet. */
    data class Measure(val segments: Int, val sevenBit: Boolean)

    /**
     * [text] with plain stand-ins for the characters that force the long form, if that takes
     * fewer texts by [measure] (on a phone, Android's own count, which knows the carrier's
     * alphabets); otherwise [text] as it is.
     */
    fun forSms(text: String, measure: (String) -> Measure = ::measure): String {
        val before = measure(text)
        if (before.sevenBit) return text
        val simple = simplify(text)
        if (simple == text) return text
        val after = measure(simple)
        return if (after.sevenBit && after.segments < before.segments) simple else text
    }

    /** Every character outside the GSM alphabet that has a plain stand-in, replaced; the rest kept. */
    fun simplify(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            if (c in GSM_BASIC || c in GSM_EXTENSION) {
                out.append(c)
                continue
            }
            out.append(standIn(c) ?: c.toString())
        }
        return out.toString()
    }

    private fun standIn(c: Char): String? {
        STAND_INS[c]?.let { return it }
        // An accented letter the alphabet lacks (á, ê, ç, ō…): the letter without its accent.
        val bare = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        return bare.takeIf { it.isNotEmpty() && it != c.toString() && it.all { b -> b in GSM_BASIC } }
    }

    /**
     * The GSM 03.38 count, for tests and as a fallback: 160 characters in one text or 153 a part
     * (extension characters take two), else 70 UTF-16 units in one or 67 a part.
     */
    fun measure(text: String): Measure {
        val sevenBit = text.all { it in GSM_BASIC || it in GSM_EXTENSION }
        if (sevenBit) {
            val septets = text.sumOf { if (it in GSM_EXTENSION) 2L else 1L }.toInt()
            return Measure(if (septets <= 160) 1 else (septets + 152) / 153, true)
        }
        return Measure(if (text.length <= 70) 1 else (text.length + 66) / 67, false)
    }

    /** The GSM 7-bit default alphabet, less the escape. */
    private val GSM_BASIC: Set<Char> = (
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
        ).toSet()

    /** Reached by an escape, so each takes two places. */
    private val GSM_EXTENSION: Set<Char> = "\u000C^{}\\[~]|€".toSet()

    private val STAND_INS: Map<Char, String> = buildMap {
        "‘’‚‛′ʼ´`".forEach { put(it, "'") }
        "“”„‟″«»".forEach { put(it, "\"") }
        "‐‑‒–—―−".forEach { put(it, "-") }
        put('…', "...")
        put('•', "-")
        put('\t', " ")
        // Spaces of other widths, which word processors and some keyboards put in.
        "              　".forEach { put(it, " ") }
        put('œ', "oe"); put('Œ', "OE"); put('ł', "l"); put('Ł', "L"); put('đ', "d"); put('Đ', "D"); put('ı', "i")
    }
}
