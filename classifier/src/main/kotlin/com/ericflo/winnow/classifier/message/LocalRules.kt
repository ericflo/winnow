package com.ericflo.winnow.classifier.message

/** Finds one-time codes ("Your code is 482913", "G-482913") so they can stay local and be copied. */
object VerificationCodes {
    private val CODE_WORDS = Regex(
        """\b(code|passcode|verification|verify|one[- ]time|otp|pin|security code|2fa|login)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CODE = Regex("""(?<![\d#])(?:[A-Z]-)?(\d{4,8})(?![\d#])""")

    /** The code's digits, or null when [body] doesn't read like a verification message. */
    fun find(body: String): String? {
        if (body.length > 300 || !CODE_WORDS.containsMatchIn(body)) return null
        return CODE.find(body)?.groupValues?.get(1)
    }
}

/** Decisions made on the phone, before any provider sees the message. */
internal object LocalRules {

    fun decide(message: InboundMessage, privacy: PrivacyPolicy): Verdict? = when {
        message.senderRule == SenderRule.ALWAYS_ALLOW ->
            Verdict.rule(null, Action.ALLOW, "You allowed this sender")
        message.senderRule == SenderRule.ALWAYS_FILTER ->
            Verdict.rule(null, Action.FILTER, "You filtered this sender")
        message.senderInContacts && !privacy.classifyContacts ->
            Verdict.rule(Category.PERSONAL, Action.ALLOW, "Sender is in your contacts")
        message.userHasMessagedSender && !privacy.classifyKnownConversations ->
            Verdict.rule(Category.PERSONAL, Action.ALLOW, "You've texted this sender before")
        // Someone the user chatted with in Google Messages, over RCS: their number isn't in the
        // store, so they can't be found in contacts, but they're never a stranger's spam to send off.
        RcsIds.isPerson(message.sender) && !privacy.classifyKnownConversations ->
            Verdict.rule(Category.PERSONAL, Action.ALLOW, RCS_CHAT)
        looksLikeVerificationCode(message.body) && !privacy.classifyVerificationCodes ->
            Verdict.rule(Category.TRANSACTIONAL, Action.ALLOW, "Verification code, kept on this phone")
        else -> null
    }

    fun looksLikeVerificationCode(body: String): Boolean = VerificationCodes.find(body) != null

    const val RCS_CHAT = "Someone from an RCS chat in Google Messages, kept on this phone"
}

/**
 * A crude keyword scorer used only when no provider can answer (offline, misconfigured, or
 * excluded by the privacy policy). Its verdicts are capped by [ActionPolicy.heuristicCeiling].
 */
internal object HeuristicScorer {
    private fun words(vararg w: String) = Regex(w.joinToString("|", "(", ")"), RegexOption.IGNORE_CASE)

    private val phishing = words(
        "unpaid toll", "toll (balance|violation)", "e-?zpass", "redeliver", "package (is )?(on hold|could not)",
        "suspended", "verify your (account|identity)", "final notice", "refund", "irs", "social security payment",
    )
    private val scam = words(
        "gift ?card", "crypto", "you('ve| have) won", "claim your", "wire transfer", "job offer",
        "is this \\w+\\?", "are you free to talk",
    )
    private val shortLink = words("bit\\.ly", "tinyurl", "t\\.co/", "\\.top\\b", "\\.xyz\\b", "\\.click\\b", "\\.vip\\b", "\\.icu\\b")
    private val promo = words("% off", "\\bsale\\b", "reply stop", "stop to (opt[- ]out|end|unsubscribe)", "coupon", "promo", "deal")
    private val political = words(
        "\\bvote\\b", "donat", "campaign", "election", "paid for by", "\\bpac\\b", "ballot", "chip in",
        "\\bpoll\\b", "petition", "matched", "breaking:",
    )
    private val transactional = words("your order", "delivered", "appointment", "has shipped", "receipt")
    private val reminder = words("reminder", "maintenance", "inspection", "rent", "closed", "schedule change", "please remember")

    fun score(message: InboundMessage): Map<Category, Double> {
        val body = message.body
        val kind = SenderKind.of(message.sender)
        val raw = mutableMapOf(
            Category.PERSONAL to if (kind == SenderKind.PHONE_NUMBER) 1.0 else 0.2,
            Category.REMINDER to 0.1,
            Category.TRANSACTIONAL to 0.3,
            Category.MARKETING to 0.2,
            Category.POLITICAL to 0.1,
            Category.SPAM to 0.3,
        )
        fun bump(c: Category, hits: Int, weight: Double) {
            raw[c] = raw.getValue(c) + hits * weight
        }
        bump(Category.SPAM, phishing.findAll(body).count(), 1.5)
        bump(Category.SPAM, shortLink.findAll(body).count(), 1.0)
        bump(Category.SPAM, scam.findAll(body).count(), 1.5)
        bump(Category.REMINDER, reminder.findAll(body).count(), 0.8)
        bump(Category.MARKETING, promo.findAll(body).count(), 1.0)
        bump(Category.POLITICAL, political.findAll(body).count(), 1.0)
        bump(Category.TRANSACTIONAL, transactional.findAll(body).count(), 0.8)
        val total = raw.values.sum()
        return raw.mapValues { it.value / total }
    }
}
