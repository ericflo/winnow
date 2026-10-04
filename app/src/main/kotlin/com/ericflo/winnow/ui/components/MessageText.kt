package com.ericflo.winnow.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

private const val TLDS = "com|net|org|io|co|me|us|app|dev|info|biz|top|vip|xyz|ly|gl|gov|edu|shop|click|link"

// A bare domain's ending must end the name ("Ok.Coming" and "5.Usually" aren't links) and be
// all lowercase or all caps: "Thanks.Me too" is a sentence break, "STORE.COM" is shouting.
private val WEB = Regex(
    """(?i)\b(?:https?://\S+|www\.\S+|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?-i:(?:$TLDS)|(?:${TLDS.uppercase()}))(?![a-z0-9-])(?:/\S*)?)""",
)
private val SCHEME = Regex("^(https?)://", RegexOption.IGNORE_CASE)

/** [value] as a URL to open: "HTTPS://…" lowercased to a scheme Android matches, a bare "httpbin.org" given one. */
private fun toUrl(value: String): String =
    SCHEME.find(value)?.let { value.replaceRange(it.range, "${it.groupValues[1].lowercase()}://") } ?: "https://$value"
private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
private val PHONE = Regex("""(?<![\w])(?:\+?1[ .-]?)?\(?\d{3}\)?[ .-]?\d{3}[ .-]?\d{4}(?![\w])""")
private val TRAILING_PUNCTUATION = ".,!?;:)'\""

/**
 * Message text with tappable web links, emails and phone numbers. With [links] off (fraud),
 * the text is returned unlinked: the point is that a scam link can't be tapped by accident.
 */
/** The first web link in [text], as a URL to open; emails and phone numbers don't count. */
fun firstWebLink(text: String): String? {
    val m = WEB.find(text) ?: return null
    val value = m.value.trimEnd { it in TRAILING_PUNCTUATION }
    if (EMAIL.findAll(text).any { it.range.first <= m.range.first && m.range.first < it.range.last }) return null
    return toUrl(value)
}

/** Every web link in [text], as URLs to show; emails don't count. */
fun allWebLinks(text: String): List<String> = WEB.findAll(text)
    .filterNot { m -> EMAIL.findAll(text).any { it.range.first <= m.range.first && m.range.first < it.range.last } }
    .map { m -> toUrl(m.value.trimEnd { it in TRAILING_PUNCTUATION }) }
    .distinct()
    .toList()

fun linkify(text: String, links: Boolean, linkColor: Color): AnnotatedString {
    if (!links) return AnnotatedString(text)
    data class Span(val start: Int, val end: Int, val url: String)
    val spans = mutableListOf<Span>()
    fun add(regex: Regex, toUrl: (String) -> String) {
        regex.findAll(text).forEach { m ->
            val value = m.value.trimEnd { it in TRAILING_PUNCTUATION }
            val end = m.range.first + value.length
            if (spans.none { m.range.first < it.end && it.start < end }) spans += Span(m.range.first, end, toUrl(value))
        }
    }
    add(EMAIL) { "mailto:$it" }
    add(WEB, ::toUrl)
    add(PHONE) { "tel:" + it.filter { c -> c.isDigit() || c == '+' } }

    val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var cursor = 0
        spans.sortedBy { it.start }.forEach { span ->
            append(text, cursor, span.start)
            withLink(LinkAnnotation.Url(span.url, style)) { append(text, span.start, span.end) }
            cursor = span.end
        }
        append(text, cursor, text.length)
    }
}

/** One to three emoji and nothing else: shown large, without a bubble, as in Messages. */
fun isEmojiOnly(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isLetterOrDigit() || it.isWhitespace() }) return false
    val symbols = trimmed.codePoints().filter { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }.count()
    return symbols in 1..3 && trimmed.codePoints().allMatch {
        when (Character.getType(it)) {
            Character.OTHER_SYMBOL.toInt(), Character.NON_SPACING_MARK.toInt(), Character.FORMAT.toInt(),
            Character.MODIFIER_SYMBOL.toInt(), Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }
}
