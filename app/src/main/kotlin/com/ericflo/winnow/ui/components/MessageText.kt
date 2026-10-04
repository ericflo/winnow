package com.ericflo.winnow.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

private val WEB = Regex("""(?i)\b(?:https?://\S+|www\.\S+|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|io|co|me|us|app|dev|info|biz|top|vip|xyz|ly|gl|gov|edu|shop|click|link)(?:/\S*)?)""")
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
    return if (value.startsWith("http", ignoreCase = true)) value else "https://$value"
}

/** Every web link in [text], as URLs to show; emails don't count. */
fun allWebLinks(text: String): List<String> = WEB.findAll(text)
    .filterNot { m -> EMAIL.findAll(text).any { it.range.first <= m.range.first && m.range.first < it.range.last } }
    .map { m -> m.value.trimEnd { it in TRAILING_PUNCTUATION }.let { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" } }
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
    add(WEB) { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
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
