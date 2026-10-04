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
        return text == wanted || (wanted.endsWith("…") && text.startsWith(wanted.dropLast(1).trimEnd()))
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
        // iPhones react to a photo with `Loved an image`, unquoted.
        private val ADD = Regex("""^(${VERBS.keys.joinToString("|") { Regex.escape(it) }}) (?:$QUOTED|(an image))$""", RegexOption.DOT_MATCHES_ALL)

        /** The reactions Winnow offers, in the order iPhones show them. */
        val CHOICES = listOf("❤️", "👍", "👎", "😂", "‼️", "❓")

        /** Longest quote sent; longer texts are cut with "…", which [matches] understands. */
        private const val QUOTE_LIMIT = 100

        /**
         * The text that reacts to a message with [emoji]: `Loved “see you soon”`, as iPhones
         * send and display as a tapback. [body] blank means a photo: `Loved an image`.
         */
        fun compose(emoji: String, body: String): String {
            val verb = VERBS.entries.firstOrNull { it.value == emoji }?.key ?: return "Reacted $emoji to “${quote(body)}”"
            return if (body.isBlank()) "$verb an image" else "$verb “${quote(body)}”"
        }

        private fun quote(body: String): String {
            val text = body.trim()
            return if (text.length <= QUOTE_LIMIT) text else text.take(QUOTE_LIMIT).trimEnd() + "…"
        }
        private val REMOVE = Regex("""^Removed (${REMOVALS.keys.joinToString("|") { Regex.escape(it) }}) from $QUOTED$""", RegexOption.DOT_MATCHES_ALL)
        private val REACTED = Regex("""^Reacted (\S+) to $QUOTED$""", RegexOption.DOT_MATCHES_ALL)

        /** A short description for previews: "Reacted ❤️ to “see you soon”", or [body] itself. */
        fun summarize(body: String): String = parse(body)?.let {
            "${if (it.removal) "Removed" else "Reacted"} ${it.emoji} ${if (it.removal) "from" else "to"} “${it.quoted}”"
        } ?: body

        fun parse(body: String): Tapback? {
            val text = body.trim()
            ADD.matchEntire(text)?.let { return Tapback(VERBS.getValue(it.groupValues[1]), it.groupValues[2].ifEmpty { it.groupValues[3] }) }
            REMOVE.matchEntire(text)?.let { return Tapback(REMOVALS.getValue(it.groupValues[1]), it.groupValues[2], removal = true) }
            REACTED.matchEntire(text)?.let { return Tapback(it.groupValues[1], it.groupValues[2]) }
            return null
        }
    }
}
