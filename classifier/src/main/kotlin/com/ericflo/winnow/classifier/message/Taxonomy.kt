package com.ericflo.winnow.classifier.message

/**
 * What a message is. The rubrics are sent to the provider as the options of one Choice
 * question, so wording changes here change classification behavior.
 */
enum class Category(val key: String, val label: String, val rubric: String, val defaultAction: Action) {
    PERSONAL(
        "personal", "Personal",
        "Written by a real person to the user personally: friends, family, coworkers, neighbors, someone they " +
            "are coordinating with. Not a stranger opening a conversation out of nowhere.",
        Action.ALLOW,
    ),
    TRANSACTIONAL(
        "transactional", "Transactional",
        "An automated message the user plausibly expects because of something they did: verification codes, real " +
            "order or delivery updates, appointment reminders, alerts from a bank or service they use. No pressure " +
            "to click an unfamiliar link or to pay.",
        Action.ALLOW,
    ),
    PROMOTIONAL(
        "promotional", "Promotion",
        "Marketing from a business the user may have signed up with: sales, coupons, offers, newsletters, " +
            "loyalty programs. Usually offers an opt-out such as 'Reply STOP'.",
        Action.SILENCE,
    ),
    POLITICAL(
        "political", "Political",
        "From a campaign, party, PAC, advocacy group or pollster: donation asks, voting reminders, surveys, " +
            "issue pushes.",
        Action.FILTER,
    ),
    SPAM(
        "spam", "Spam",
        "Unsolicited bulk junk that is not clearly fraud: lead generation, random offers, irrelevant mass texts, " +
            "messages meant for someone else.",
        Action.FILTER,
    ),
    SCAM(
        "scam", "Likely scam",
        "Fraud or phishing: fake unpaid tolls, fake package or account problems, prize or job offers, investment " +
            "or crypto pitches, 'wrong number' openers from strangers, anything urging a link, a payment, gift " +
            "cards or personal details.",
        Action.FILTER,
    ),
    ;

    companion object {
        fun fromKey(key: String): Category? = entries.firstOrNull { it.key == key }
    }
}

/** What Winnow does with a message. Ordered from least to most severe. */
enum class Action {
    /** Inbox plus a normal notification. */
    ALLOW,

    /** Inbox, but no notification. */
    SILENCE,

    /** Filtered folder, no notification. */
    FILTER,
    ;

    fun softened(): Action = entries[(ordinal - 1).coerceAtLeast(0)]
}

/** The user's explicit decision about a sender, which overrides classification. */
enum class SenderRule { ALWAYS_ALLOW, ALWAYS_FILTER }

enum class SenderKind(val wire: String) {
    PHONE_NUMBER("phone_number"),
    SHORT_CODE("short_code"),
    ALPHANUMERIC("alphanumeric"),
    EMAIL("email"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun of(address: String): SenderKind {
            val a = address.trim()
            val digits = a.count(Char::isDigit)
            return when {
                '@' in a -> EMAIL
                a.any(Char::isLetter) -> ALPHANUMERIC
                a.all(Char::isDigit) && digits in 3..8 -> SHORT_CODE
                digits >= 7 -> PHONE_NUMBER
                else -> UNKNOWN
            }
        }
    }
}
