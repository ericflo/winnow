package com.ericflo.winnow

import com.ericflo.winnow.ui.components.EmojiCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCheckTest {
    private fun single(text: String) = EmojiCheck.isSingle(text, Character::isEmoji, Character::isEmojiPresentation)

    @Test
    fun `one emoji of any shape`() {
        listOf("🎉", "❤️", "👍🏽", "👨‍👩‍👧‍👦", "👩‍❤️‍💋‍👨", "🇺🇸", "1️⃣", "#️⃣", "‼️", "ℹ️", "↔️", "〰️", "🏴󠁧󠁢󠁥󠁮󠁧󠁿").forEach {
            assertTrue(it, single(it))
        }
    }

    @Test
    fun `not one emoji`() {
        listOf("", "a", "5", "#", "©", "‼", "🎉🎉", "🎉a", "⠀", "‍", "️", "🎉‮", "ok 👍").forEach {
            assertFalse(it, single(it))
        }
    }
}
