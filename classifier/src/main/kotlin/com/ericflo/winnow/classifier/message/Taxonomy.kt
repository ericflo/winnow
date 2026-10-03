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
            "order or delivery updates, appointment reminders, bills and alerts from a bank, carrier or service " +
            "they use. No pressure to click an unfamiliar link or to pay.",
        Action.ALLOW,
    ),
    MARKETING(
        "marketing", "Marketing",
        "Advertising from a business the user may have signed up with: sales, coupons, offers, member days, " +
            "newsletters, surveys, loyalty programs. Usually offers an opt-out such as 'Reply STOP'.",
        Action.SILENCE,
    ),
    POLITICAL(
        "political", "Political",
        "From a campaign, party, PAC, advocacy group or pollster: donation and matching asks, fake polls, " +
            "petitions and 'sign by' deadlines, voting reminders, sensational 'BREAKING' or 'devastating news' " +
            "hooks about politicians, and fundraising texts addressed to someone else by name.",
        Action.FILTER,
    ),
    PHISHING(
        "phishing", "Phishing",
        "Impersonates a company, bank, toll agency, delivery service, government office or one of the user's " +
            "accounts to get a click, a login, a payment or personal details: fake unpaid tolls, package holds, " +
            "account locks, refunds, benefit or Social Security payments.",
        Action.FILTER,
    ),
    SCAM(
        "scam", "Likely scam",
        "Other fraud from strangers: 'wrong number' and 'hi, is this…' or 'are you free to talk?' openers, " +
            "romance, job, prize, investment or crypto pitches, requests for gift cards or money.",
        Action.FILTER,
    ),
    SPAM(
        "spam", "Spam",
        "Unsolicited bulk junk that is not clearly fraud or politics: lead generation, random offers, " +
            "irrelevant mass texts, messages meant for someone else.",
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
