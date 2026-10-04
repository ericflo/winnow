package com.ericflo.winnow.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.ericflo.winnow.data.SmartLink

private const val TLDS = "com|net|org|io|co|me|us|app|dev|info|biz|top|vip|xyz|ly|gl|gov|edu|shop|click|link"

// A bare domain's ending must end the name ("Ok.Coming" and "5.Usually" aren't links) and be
// all lowercase or all caps: "Thanks.Me too" is a sentence break, "STORE.COM" is shouting.
private val WEB = Regex(
    """(?i)\b(?:https?://\S+|www\.\S+|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?-i:(?:$TLDS)|(?:${TLDS.uppercase()}))(?![a-z0-9-])(?:/\S*)?)""",
)
private val SCHEME = Regex("^(https?)://", RegexOption.IGNORE_CASE)
private val UPS = Regex("""\b1Z[0-9A-Z]{16}\b""", RegexOption.IGNORE_CASE)
// Whole tokens only: not the digits of "INV123456789012", nor a phone number's after its "+".
private val USPS = Regex("""(?<![\w+])9[2-5]\d{18,24}(?!\w)""")
private val FEDEX = Regex("""(?<![\w+])(?:\d{12}|\d{15})(?!\w)""")
private val FEDEX_NAMED = Regex("""(?i)\bfed\s?ex\b""")
// A US street address: a number, up to four capitalized words (or "45th"), a street type, and
// maybe a unit and ", City, ST 12345". Capitals keep "2 dogs on the way" out.
private const val STREET_TYPES = "St|Street|Ave|Avenue|Rd|Road|Blvd|Boulevard|Dr|Drive|Ln|Lane|Way|Ct|Court|Pl|Place|Pkwy|Parkway|" +
    "Hwy|Highway|Ter|Terrace|Cir|Circle|Sq|Square|Trl|Trail|Loop|Plaza"
private val ADDRESS = Regex(
    """(?<![\w#])\d{1,6}(?:\s+[NSEW]\.?)?(?:\s+(?:[A-Z][A-Za-z'.-]*|\d{1,3}(?:st|nd|rd|th))){1,4}\s+(?:$STREET_TYPES)\b\.?""" +
        """(?:,?\s+(?:Apt|Suite|Ste|Unit|#)\.?\s*[A-Za-z0-9-]{1,6})?""" +
        """(?:,\s*[A-Z][A-Za-z.]*(?:\s+[A-Z][A-Za-z.]*){0,3},\s*[A-Z]{2}(?:\s+\d{5}(?:-\d{4})?)?)?""",
)

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

fun linkify(
    text: String,
    links: Boolean,
    linkColor: Color,
    /** Places, dates and flights from Android's text classifier, where nothing else already links. */
    smart: List<SmartLink> = emptyList(),
    onSmart: (SmartLink) -> Unit = {},
): AnnotatedString {
    if (!links) return AnnotatedString(text)
    data class Span(val start: Int, val end: Int, val url: String, val smart: SmartLink? = null)
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
    // Package tracking numbers, to the carrier's tracking page. Before phone numbers, whose
    // digits a long tracking number would otherwise lend itself to.
    add(UPS) { "https://www.ups.com/track?tracknum=${it.uppercase()}" }
    add(USPS) { "https://tools.usps.com/go/TrackConfirmAction?tLabels=$it" }
    // FedEx's are plain 12 or 15 digits, like any order number: only when the text says FedEx.
    if (FEDEX_NAMED.containsMatchIn(text)) add(FEDEX) { "https://www.fedex.com/fedextrack/?trknbr=$it" }
    // To a search in the maps app: the place, nothing else, leaves the message.
    add(ADDRESS) { "geo:0,0?q=" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    add(PHONE) { "tel:" + it.filter { c -> c.isDigit() || c == '+' } }
    smart.filter { it.start >= 0 && it.end <= text.length && it.start < it.end }.forEach { found ->
        // The classifier's "at 7pm." takes the full stop with it; the link shouldn't.
        var end = found.end
        while (end > found.start + 1 && text[end - 1] in TRAILING_PUNCTUATION) end--
        val link = found.copy(end = end)
        if (spans.none { link.start < it.end && it.start < link.end }) spans += Span(link.start, link.end, "", link)
    }

    val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var cursor = 0
        spans.sortedBy { it.start }.forEach { span ->
            append(text, cursor, span.start)
            val link = span.smart?.let { smartLink -> LinkAnnotation.Clickable("smart", style) { onSmart(smartLink) } } ?: LinkAnnotation.Url(span.url, style)
            withLink(link) { append(text, span.start, span.end) }
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
