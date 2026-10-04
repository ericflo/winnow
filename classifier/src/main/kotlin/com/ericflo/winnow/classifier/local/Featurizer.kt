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
    const val VERSION = 3

    data class Input(val sender: String, val body: String, val senderInContacts: Boolean = false, val userHasMessagedSender: Boolean = false)

    fun features(input: Input): List<String> {
        val out = ArrayList<String>(64)
        out += "__sender_${SenderKind.of(input.sender).wire}__"
        senderNumber(input.sender)?.let(out::add)
        if (input.senderInContacts) out += CONTACT
        if (input.userHasMessagedSender) out += KNOWN

        val body = input.body
        out += "__len_${LENGTHS.indexOfFirst { body.length < it }.let { if (it < 0) LENGTHS.size else it }}__"
        val letters = body.count(Char::isLetter)
        if (letters >= 10 && body.count(Char::isUpperCase) * 2 >= letters) out += SHOUTING
        if (CAPS_WORD.containsMatchIn(body)) out += CAPS_WORD_FEATURE
        if ("!!" in body) out += BANGS else if ('!' in body) out += BANG
        if (body.codePoints().anyMatch { it >= 0x1F300 || it in 0x2600..0x27BF }) out += EMOJI
        out += structure(body)

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

    /** Toll-free numbers send business texts, never personal ones; foreign numbers often send scams. */
    private fun senderNumber(sender: String): String? {
        val digits = sender.filter(Char::isDigit)
        return when {
            sender.trim().startsWith("+") && !sender.trim().startsWith("+1") && digits.length >= 8 -> SENDER_INTERNATIONAL
            digits.length == 11 && digits.startsWith("1") && digits.substring(1, 4) in TOLL_FREE -> SENDER_TOLL_FREE
            digits.length == 10 && digits.substring(0, 3) in TOLL_FREE -> SENDER_TOLL_FREE
            else -> null
        }
    }

    /** Shapes that words alone miss: how a message opens, asks, pressures and lets you opt out. */
    private fun structure(body: String): List<String> = buildList {
        val trimmed = body.trim()
        if (OPT_OUT.containsMatchIn(body)) add(OPT_OUT_FEATURE)
        if (SELF_INTRO.containsMatchIn(body)) add(SELF_INTRO_FEATURE)
        if (INTRO_WITH_ORG.containsMatchIn(body)) add(INTRO_WITH_ORG_FEATURE)
        if (NAME_GREETING.containsMatchIn(trimmed)) add(NAME_GREETING_FEATURE)
        if (MULTIPLIER.containsMatchIn(body)) add(MULTIPLIER_FEATURE)
        if (DEADLINE.containsMatchIn(body)) add(DEADLINE_FEATURE)
        if (REPLY_KEYWORD.containsMatchIn(body)) add(REPLY_KEYWORD_FEATURE)
        if (DEAR.containsMatchIn(body)) add(DEAR_FEATURE)
        if (BRAND_PREFIX.containsMatchIn(trimmed)) add(BRAND_PREFIX_FEATURE)
        if (trimmed.endsWith("?")) add(QUESTION_FEATURE)
        if (trimmed.any(Char::isLetter) && trimmed.none(Char::isUpperCase)) add(ALL_LOWERCASE_FEATURE)
        if (trimmed.split(WHITESPACE).size <= 8) add(FEW_WORDS_FEATURE)
        if (LOOKALIKE.containsMatchIn(body)) add(LOOKALIKE_FEATURE)
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
            if (labels.takeLast(2).joinToString(".") in OFFICIAL_DOMAINS) add(OFFICIAL_DOMAIN)
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
    private const val SENDER_TOLL_FREE = "__sender_toll_free__"
    private const val SENDER_INTERNATIONAL = "__sender_international__"
    private val TOLL_FREE = setOf("800", "833", "844", "855", "866", "877", "888")
    private const val OFFICIAL_DOMAIN = "__official_domain__"
    private const val OPT_OUT_FEATURE = "__opt_out__"
    private const val SELF_INTRO_FEATURE = "__self_intro__"
    private const val INTRO_WITH_ORG_FEATURE = "__intro_with_org__"
    private const val NAME_GREETING_FEATURE = "__name_greeting__"
    private const val MULTIPLIER_FEATURE = "__multiplier__"
    private const val DEADLINE_FEATURE = "__deadline__"
    private const val REPLY_KEYWORD_FEATURE = "__reply_keyword__"
    private const val DEAR_FEATURE = "__dear__"
    private const val BRAND_PREFIX_FEATURE = "__brand_prefix__"
    private const val QUESTION_FEATURE = "__question__"
    private const val ALL_LOWERCASE_FEATURE = "__all_lowercase__"
    private const val FEW_WORDS_FEATURE = "__few_words__"
    private const val LOOKALIKE_FEATURE = "__lookalike__"
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
        OFFICIAL_DOMAIN to "a link to the company's real website",
        SENDER_TOLL_FREE to "sent from a toll-free business number",
        SENDER_INTERNATIONAL to "sent from an overseas number",
        OPT_OUT_FEATURE to "a “reply STOP” opt-out",
        SELF_INTRO_FEATURE to "a stranger introducing themselves",
        INTRO_WITH_ORG_FEATURE to "someone writing on behalf of a group",
        NAME_GREETING_FEATURE to "a greeting by first name",
        MULTIPLIER_FEATURE to "a donation “match”",
        DEADLINE_FEATURE to "a deadline or countdown",
        REPLY_KEYWORD_FEATURE to "asks for a one-word reply",
        DEAR_FEATURE to "“Dear customer”",
        BRAND_PREFIX_FEATURE to "starts with a company name",
        LOOKALIKE_FEATURE to "letters from another alphabet disguised as English",
    )

    private val LENGTHS = intArrayOf(20, 50, 100, 160, 300)
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val URL = Regex("""(?i)\b(?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2,}\b(?:/[^\s]*)?""")
    private val MONEY = Regex("""(?i)[$£€]\s?\d[\d,]*(?:\.\d+)?|\b\d[\d,]*(?:\.\d{2})?\s?(?:usd|dollars)\b""")
    private val PHONE = Regex("""(?:\+?1[\s.-]?)?(?:\(\d{3}\)\s?|\b\d{3}[\s.-])\d{3}[\s.-]\d{4}\b|\b\d{3}-\d{4}\b""")
    private val DIGITS = Regex("""\d+""")
    private val WHITESPACE = Regex("""\s+""")
    private val OPT_OUT = Regex("""(?i)\b(?:reply|text|txt|send)\s+stop\b|\bstop\s*(?:2|to|=|-)\s*(?:end|quit|stop|opt|unsub|cancel)|\bstop2\w+""")
    private val SELF_INTRO = Regex("""(?i)\b(?:this is|it's|it’s|its|i'm|i’m|my name is)\s+[A-Z][a-z]+\b""")
    private val INTRO_WITH_ORG = Regex("""\b(?:[Tt]his is|[Ii]t'?s|[Ii]t’s|I'?m|I’m)\s+[A-Z][a-z]+(?:\s+[A-Z][a-z]+)?,?\s+(?:with|from|at|w/|volunteering|a volunteer)\b""")
    private val NAME_GREETING = Regex("""^(?i:hi|hey|hello|good (?:morning|afternoon|evening)|dear)[,!]?\s+[A-Z][a-z]+\b""")
    private val MULTIPLIER = Regex("""(?i)\b\d+\s?x\b|\b\d{3,}%|\b(?:double|triple|quadruple)[- ]?match|\bmatched\b""")
    private val DEADLINE = Regex("""(?i)\b(?:midnight|tonight|today only|expires?|expiring|final (?:notice|hours|reminder|days)|last chance|within \d+ (?:hours|hrs)|\d+ hours|ends (?:tonight|soon|today|sunday)|deadline|before it'?s too late|immediately)\b""")
    private val REPLY_KEYWORD = Regex("""(?i)\breply\s+(?:y|yes|no|n|\d|c|r|claim|info|stop|help|[A-Z]{3,})\b""")
    private val DEAR = Regex("""(?i)\bdear (?:customer|user|member|sir|madam|client|valued)""")
    private val BRAND_PREFIX = Regex("""^\[?[A-Z][\w&'’.-]*(?:\s[A-Z0-9][\w&'’.-]*){0,3}\]?:\s""")
    /** A Latin letter and a Cyrillic or Greek one in the same word: "Аpple", "PayPаl". */
    private val LOOKALIKE = Regex("""(?:[a-zA-Z][\u0370-\u03FF\u0400-\u04FF]|[\u0370-\u03FF\u0400-\u04FF][a-zA-Z])""")

    private val OFFICIAL_DOMAINS = setOf(
        "amazon.com", "amzn.to", "usps.com", "fedex.com", "ups.com", "dhl.com", "chase.com", "wellsfargo.com", "bankofamerica.com",
        "citi.com", "capitalone.com", "americanexpress.com", "paypal.com", "venmo.com", "apple.com", "google.com", "microsoft.com",
        "netflix.com", "verizon.com", "vzw.com", "att.com", "t-mobile.com", "xfinity.com", "comcast.com", "target.com", "walmart.com",
        "costco.com", "bestbuy.com", "homedepot.com", "lowes.com", "kp.org", "irs.gov", "ssa.gov", "vote.org", "vote.gov",
        "southwest.com", "delta.com", "united.com", "aa.com", "uber.com", "lyft.com", "doordash.com", "drd.sh", "instacart.com",
    )
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
