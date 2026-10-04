package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.SenderKind

/**
 * Turns a message into the sparse features the on-device model reads: words and word pairs,
 * plus named signals for what words can't capture (links and where they point, money, phone
 * numbers, shouting, the kind of sender). Numbers, links and addresses are replaced by
 * placeholders first, so "$4.35" and "$12.51" look the same to the model.
 *
 * Changing anything here changes the meaning of the trained weights: bump [VERSION] and
 * retrain (`./gradlew :classifier:trainLocalModel`).
 */
object Featurizer {
    const val VERSION = 1

    data class Input(val sender: String, val body: String, val senderInContacts: Boolean = false, val userHasMessagedSender: Boolean = false)

    fun features(input: Input): List<String> {
        val out = ArrayList<String>(64)
        out += "__sender_${SenderKind.of(input.sender).wire}__"
        if (input.senderInContacts) out += CONTACT
        if (input.userHasMessagedSender) out += KNOWN

        val body = input.body
        out += "__len_${LENGTHS.indexOfFirst { body.length < it }.let { if (it < 0) LENGTHS.size else it }}__"
        val letters = body.count(Char::isLetter)
        if (letters >= 10 && body.count(Char::isUpperCase) * 2 >= letters) out += SHOUTING
        if (CAPS_WORD.containsMatchIn(body)) out += CAPS_WORD_FEATURE
        if ("!!" in body) out += BANGS else if ('!' in body) out += BANG
        if (body.codePoints().anyMatch { it >= 0x1F300 || it in 0x2600..0x27BF }) out += EMOJI

        var text = body
        text = EMAIL.replace(text) { out += "__email__"; " zzemail " }
        text = URL.replace(text) { match -> out += urlFeatures(match.value); " zzurl " }
        text = MONEY.replace(text) { out += MONEY_FEATURE; " zzmoney " }
        text = PHONE.replace(text) { out += PHONE_FEATURE; " zzphone " }
        text = DIGITS.replace(text) { m -> " zznum${if (m.value.length >= 5) "long" else "short"} " }

        val words = text.lowercase().replace("'", "").replace("’", "").split(NON_WORD).filter { it.isNotEmpty() }
        words.forEach { out += "w:$it" }
        words.zipWithNext().forEach { (a, b) -> out += "b:$a $b" }
        return out
    }

    private fun urlFeatures(raw: String): List<String> {
        val url = raw.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val host = url.substringBefore('/')
        val labels = host.split('.')
        val tld = labels.last()
        return buildList {
            add(URL_FEATURE)
            add("__tld_${tld}__")
            if (host in SHORTENERS) add(SHORTENER)
            if (tld in RISKY_TLDS) add(RISKY_TLD)
            if (DECEPTIVE.containsMatchIn(host)) add(DECEPTIVE_HOST)
            if (host.count { it == '-' } >= 2) add(HYPHENATED_HOST)
            if ('/' in url && url.substringAfter('/').isNotEmpty()) add(URL_PATH)
            // A brand name inside a stranger's domain ("usps-redelivery.info") is a strong tell.
            labels.dropLast(1).flatMap { it.split('-') }.filter { it.length >= 2 }.forEach { add("h:$it") }
        }
    }

    /** Named features, for explaining a verdict in words. Null for ones not worth mentioning. */
    fun describe(feature: String): String? = when {
        feature.startsWith("w:") -> feature.removePrefix("w:").takeIf(::meaningful)?.let { "“$it”" }
        feature.startsWith("b:") -> feature.removePrefix("b:").takeIf { it.split(' ').all(::meaningful) }?.let { "“$it”" }
        feature.startsWith("h:") -> feature.removePrefix("h:").takeIf(::meaningful)?.let { "a link mentioning “$it”" }
        feature.startsWith("__tld_") -> "a .${feature.removePrefix("__tld_").removeSuffix("__")} link"
        else -> DESCRIPTIONS[feature]
    }

    /** The words a feature is about, so an explanation doesn't name the same word twice. */
    fun wordsOf(feature: String): Set<String> = when {
        feature.startsWith("w:") || feature.startsWith("h:") -> setOf(feature.substring(2))
        feature.startsWith("b:") -> feature.substring(2).split(' ').toSet()
        else -> emptySet()
    }

    private fun meaningful(word: String) = word.length >= 3 && !word.startsWith("zz") && word !in STOPWORDS

    private const val CONTACT = "__contact__"
    private const val KNOWN = "__known__"
    private const val SHOUTING = "__shouting__"
    private const val CAPS_WORD_FEATURE = "__caps_word__"
    private const val BANG = "__bang__"
    private const val BANGS = "__bangs__"
    private const val EMOJI = "__emoji__"
    private const val URL_FEATURE = "__url__"
    private const val SHORTENER = "__shortener__"
    private const val RISKY_TLD = "__risky_tld__"
    private const val DECEPTIVE_HOST = "__deceptive_host__"
    private const val HYPHENATED_HOST = "__hyphenated_host__"
    private const val URL_PATH = "__url_path__"
    private const val MONEY_FEATURE = "__money__"
    private const val PHONE_FEATURE = "__phone__"

    private val DESCRIPTIONS = mapOf(
        URL_FEATURE to "a link",
        SHORTENER to "a shortened link",
        RISKY_TLD to "a link to an unusual domain",
        DECEPTIVE_HOST to "a web address dressed up as another",
        HYPHENATED_HOST to "a web address made of several words",
        MONEY_FEATURE to "a dollar amount",
        PHONE_FEATURE to "a number to call",
        SHOUTING to "mostly capital letters",
        CAPS_WORD_FEATURE to "words in capitals",
        "__email__" to "an email address",
        "__sender_short_code__" to "sent from a short code",
        "__sender_alphanumeric__" to "sent from a named sender",
        "__sender_email__" to "sent from an email address",
    )

    private val LENGTHS = intArrayOf(20, 50, 100, 160, 300)
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val URL = Regex("""(?i)\b(?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2,}\b(?:/[^\s]*)?""")
    private val MONEY = Regex("""(?i)[$£€]\s?\d[\d,]*(?:\.\d+)?|\b\d[\d,]*(?:\.\d{2})?\s?(?:usd|dollars)\b""")
    private val PHONE = Regex("""(?:\+?1[\s.-]?)?(?:\(\d{3}\)\s?|\b\d{3}[\s.-])\d{3}[\s.-]\d{4}\b|\b\d{3}-\d{4}\b""")
    private val DIGITS = Regex("""\d+""")
    private val CAPS_WORD = Regex("""\b[A-Z]{4,}\b""")
    /** "fastrak-pay.com-tolls.xyz", "dmv-ca.gov-pay.top": a real-looking domain glued to another. */
    private val DECEPTIVE = Regex("""\.(?:com|gov|org|net)[-.][a-z]""")

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "rb.gy", "cutt.ly", "is.gd", "ow.ly", "tiny.cc", "shorturl.at", "goo.gl", "t.ly", "buff.ly",
    )
    private val RISKY_TLDS = setOf(
        "top", "xyz", "vip", "icu", "click", "info", "cc", "win", "club", "help", "online", "site", "live", "shop", "buzz", "rest", "cyou", "sbs",
    )
    private val STOPWORDS = setOf(
        "the", "and", "you", "your", "for", "are", "this", "that", "with", "was", "have", "has", "our", "but", "not", "can", "its",
        "from", "will", "com", "net", "org", "www", "http", "https", "who", "what", "when", "how", "all", "any", "out", "get", "now",
        "just", "here", "there", "they", "them", "she", "his", "her", "him", "been", "were", "would", "could", "should", "about",
    )
}
