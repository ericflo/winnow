package com.ericflo.winnow.ui.components

import android.icu.lang.UCharacter
import android.icu.lang.UProperty

/**
 * Whether [text] is exactly one emoji: one grapheme cluster, so ZWJ families, flags, keycaps and
 * skin tones count, while a letter, a digit, punctuation or an invisible character alone doesn't.
 */
fun isSingleEmoji(text: String): Boolean = EmojiCheck.isSingle(
    text,
    isEmoji = { UCharacter.hasBinaryProperty(it, UProperty.EMOJI) },
    isPresentation = { UCharacter.hasBinaryProperty(it, UProperty.EMOJI_PRESENTATION) },
)

/** The rules behind [isSingleEmoji], with the Unicode properties passed in (ICU on Android, the JDK in tests). */
object EmojiCheck {
    private const val ZWJ = 0x200D
    private const val VS15 = 0xFE0E
    private const val VS16 = 0xFE0F
    private const val KEYCAP = 0x20E3
    private val TAGS = 0xE0020..0xE007F
    private val SKIN_TONES = 0x1F3FB..0x1F3FF
    private val REGIONAL = 0x1F1E6..0x1F1FF
    /** The emoji blocks, for emoji newer than the phone's Unicode data. */
    private val EMOJI_PLANE = 0x1F000..0x1FAFF

    fun isSingle(text: String, isEmoji: (Int) -> Boolean, isPresentation: (Int) -> Boolean): Boolean {
        if (text.isEmpty() || text.length > 64) return false
        val graphemes = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
        graphemes.first()
        if (graphemes.next() != text.length) return false
        val points = text.codePoints().toArray()
        var emoji = false
        points.forEachIndexed { i, cp ->
            val next = points.getOrNull(i + 1)
            when {
                cp == ZWJ || cp == VS15 || cp == VS16 || cp == KEYCAP || cp in TAGS || cp in SKIN_TONES -> Unit
                cp in REGIONAL -> emoji = true
                isPresentation(cp) -> emoji = true
                // Text by default (©, #, 1, ‼): an emoji only with VS16 or a keycap after it.
                isEmoji(cp) && (next == VS16 || next == KEYCAP) -> emoji = true
                cp in EMOJI_PLANE && !Character.isDefined(cp) -> emoji = true
                else -> return false
            }
        }
        return emoji
    }
}
