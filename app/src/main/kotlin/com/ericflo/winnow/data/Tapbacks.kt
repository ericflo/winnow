package com.ericflo.winnow.data

/**
 * Reactions that arrive as plain texts. iPhones send tapbacks to SMS/MMS recipients as
 * `Loved “see you soon”`, and Google Messages falls back to `Reacted 😂 to “see you soon”`.
 * Parsed, they can be drawn on the message they react to instead of as messages of their own.
 */
data class Tapback(
    val emoji: String,
    /** The reacted-to text as quoted, possibly cut short with "…". */
    val quoted: String,
    /** "Removed a heart from …": takes a reaction away. */
    val removal: Boolean = false,
) {
    /** True if [body] is the message this reaction quotes. */
    fun matches(body: String): Boolean {
        val wanted = quoted.trim()
        if (wanted.isEmpty()) return false
        val text = body.trim()
        // Cut short with "…" or "...", whichever the sender's phone used.
        val cut = when {
            wanted.endsWith("…") -> wanted.dropLast(1)
            wanted.endsWith("...") -> wanted.dropLast(3)
            else -> null
        }?.trimEnd()
        return text == wanted || (cut != null && cut.isNotEmpty() && text.startsWith(cut))
    }

    /**
     * For a reaction to an attachment (`Loved a movie`), whether a message with [contentTypes]
     * holds what it names. Null when the reaction quotes text instead.
     */
    fun matchesAttachments(contentTypes: List<String>): Boolean? {
        if (quoted !in ATTACHMENT_NAMES) return null
        return contentTypes.any { quoted == "an attachment" || attachmentName(it) == quoted }
    }

    companion object {
        private val VERBS = linkedMapOf(
            "Liked" to "👍", "Loved" to "❤️", "Disliked" to "👎", "Laughed at" to "😂",
            "Emphasized" to "‼️", "Emphasised" to "‼️", "Questioned" to "❓",
        )
        private val REMOVALS = linkedMapOf(
            "a like" to "👍", "a heart" to "❤️", "a dislike" to "👎", "a laugh" to "😂",
            "an exclamation" to "‼️", "a question mark" to "❓",
        )
        private const val QUOTED = """[“"](.+)[”"]"""
        private val ATTACHMENT_NAMES = listOf("an image", "a movie", "an audio message", "an attachment")
        // iPhones react to a photo with `Loved an image`, unquoted, and likewise for videos and voice messages.
        private val ADD = Regex("""^(${VERBS.keys.joinToString("|") { Regex.escape(it) }}) (?:$QUOTED|(${ATTACHMENT_NAMES.joinToString("|")}))$""", RegexOption.DOT_MATCHES_ALL)

        /** The reactions Winnow offers, in the order iPhones show them. */
        val CHOICES = listOf("❤️", "👍", "👎", "😂", "‼️", "❓")

        /** Longest quote sent; longer texts are cut with "…", which [matches] understands. */
        private const val QUOTE_LIMIT = 100

        /**
         * The text that reacts to a message with [emoji]: `Loved “see you soon”`, as iPhones
         * send and display as a tapback. [body] blank means an attachment, named by [attachment]:
         * `Loved an image`.
         */
        fun compose(emoji: String, body: String, attachment: String = "an image"): String {
            val verb = VERBS.entries.firstOrNull { it.value == emoji }?.key ?: return "Reacted $emoji to “${quote(body.ifBlank { attachment })}”"
            return if (body.isBlank()) "$verb $attachment" else "$verb “${quote(body)}”"
        }

        /** How iPhones name an attachment they react to: "an image", "a movie", "an audio message". */
        fun attachmentName(contentType: String): String = when {
            contentType.startsWith("image/") -> "an image"
            contentType.startsWith("video/") -> "a movie"
            contentType.startsWith("audio/") -> "an audio message"
            else -> "an attachment"
        }

        private fun quote(body: String): String {
            val text = body.trim()
            return if (text.length <= QUOTE_LIMIT) text else text.take(QUOTE_LIMIT).trimEnd() + "…"
        }
        private val REMOVE = Regex("""^Removed (${REMOVALS.keys.joinToString("|") { Regex.escape(it) }}) from $QUOTED$""", RegexOption.DOT_MATCHES_ALL)
        /** Every reaction's first word. */
        private val STARTS = (VERBS.keys.map { it.substringBefore(' ') } + "Removed" + "Reacted").distinct()
        private val REACTED = Regex("""^Reacted (?:with )?(\S+) to $QUOTED$""", RegexOption.DOT_MATCHES_ALL)
        // "😂 to “see you soon”" and "Removed 😂 from “see you soon”": the forms with the emoji itself.
        private val EMOJI_TO = Regex("""^(\S{1,8}) to $QUOTED$""", RegexOption.DOT_MATCHES_ALL)
        private val REMOVED_EMOJI = Regex("""^Removed (?:the )?(\S{1,8}) (?:reaction )?from $QUOTED$""", RegexOption.DOT_MATCHES_ALL)

        /** Whether [s] is emoji only (no letters or digits): what the emoji-first forms start with. */
        private fun emojiOnly(s: String) = s.isNotEmpty() && s.none { it.isLetterOrDigit() } && s.codePoints().anyMatch { it >= 0x2190 }

        /** A short description for previews: "Reacted ❤️ to “see you soon”", or [body] itself. */
        fun summarize(body: String): String = parse(body)?.let {
            "${if (it.removal) "Removed" else "Reacted"} ${it.emoji} ${if (it.removal) "from" else "to"} “${it.quoted}”"
        } ?: body

        fun parse(body: String): Tapback? {
            val text = body.trim()
            // Nearly every text isn't a reaction, and a conversation can hold tens of thousands:
            // a first word that can't start one skips the patterns.
            if (STARTS.none { text.startsWith(it) }) {
                // Or an emoji then " to “": too rare a start to be worth a pattern otherwise.
                val first = text.substringBefore(' ')
                if (!emojiOnly(first)) return null
                EMOJI_TO.matchEntire(text)?.let { return Tapback(it.groupValues[1], it.groupValues[2]) }
                return null
            }
            ADD.matchEntire(text)?.let { return Tapback(VERBS.getValue(it.groupValues[1]), it.groupValues[2].ifEmpty { it.groupValues[3] }) }
            REMOVE.matchEntire(text)?.let { return Tapback(REMOVALS.getValue(it.groupValues[1]), it.groupValues[2], removal = true) }
            REACTED.matchEntire(text)?.let { return Tapback(it.groupValues[1], it.groupValues[2]) }
            REMOVED_EMOJI.matchEntire(text)?.takeIf { emojiOnly(it.groupValues[1]) }?.let { return Tapback(it.groupValues[1], it.groupValues[2], removal = true) }
            return null
        }
    }
}
