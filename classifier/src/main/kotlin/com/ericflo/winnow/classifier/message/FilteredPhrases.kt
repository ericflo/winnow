package com.ericflo.winnow.classifier.message

/**
 * Words and phrases the user wants kept out of the inbox. Whole words only, ignoring case and
 * spacing: "vote" catches "VOTE today" but not "devoted", and "chip in" catches "chip  in".
 */
class FilteredPhrases(phrases: Collection<String>) {
    private val matchers: List<Pair<String, Regex>> = phrases
        .map(::normalize)
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .map { phrase ->
            val words = phrase.split(' ').joinToString("""\s+""") { Regex.escape(it) }
            phrase to Regex("""(?<![\p{L}\p{N}])$words(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
        }

    /** The first phrase [body] contains, as the user wrote it; null for none. */
    fun find(body: String): String? = matchers.firstOrNull { (_, regex) -> regex.containsMatchIn(body) }?.first

    val isEmpty: Boolean get() = matchers.isEmpty()

    companion object {
        /** Trimmed, with runs of spaces as one, and at most [MAX_LENGTH] characters. */
        fun normalize(phrase: String): String = phrase.trim().replace(Regex("""\s+"""), " ").take(MAX_LENGTH)

        const val MAX_LENGTH = 60

        /** How a verdict's reason starts when a filtered phrase decided it. */
        const val REASON_PREFIX = "Has “"

        fun reason(phrase: String) = "$REASON_PREFIX$phrase”, which you filter"
    }
}
