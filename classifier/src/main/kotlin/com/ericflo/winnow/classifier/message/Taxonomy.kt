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
    REMINDER(
        "reminder", "Reminder",
        "Someone the user already deals with (a landlord or building, school, daycare, coach, doctor's office, " +
            "HOA, employer, utility, club) telling them something to do or know, not set off by anything they just " +
            "did and not selling anything: maintenance and inspection notices, rent or dues coming up, closures and " +
            "schedule changes, outages, pickups, forms to return, 'please test your heater before winter'.",
        Action.ALLOW,
    ),
    TRANSACTIONAL(
        "transactional", "Transactional",
        "An automated message set off by something the user did: verification codes, real order or delivery " +
            "updates, confirmations and reminders of appointments they booked, bills, payments and account alerts " +
            "from a bank, carrier or service they use. No pressure to click an unfamiliar link or to pay.",
        Action.ALLOW,
    ),
    MARKETING(
        "marketing", "Marketing",
        "A business the user may know wanting them to buy, spend or come back: sales, coupons, offers, member " +
            "days, rewards and points, newsletters, surveys, loyalty programs. Usually offers an opt-out such as " +
            "'Reply STOP'. If it's telling them something to do or know and wants nothing sold, it's a reminder.",
        Action.SILENCE,
    ),
    POLITICAL(
        "political", "Political",
        "From a campaign, party, PAC, advocacy group or pollster: donation and matching asks, fake polls, " +
            "petitions and 'sign by' deadlines, voting reminders, sensational 'BREAKING' or 'devastating news' " +
            "hooks about politicians, and fundraising texts addressed to someone else by name.",
        Action.FILTER,
    ),
    SPAM(
        "spam", "Spam",
        "Unwanted texts from strangers: junk and lead generation, messages meant for someone else, scams ('wrong " +
            "number', 'hi, is this…', 'are you free to talk?', job, prize, romance, crypto, gift cards) and phishing " +
            "that impersonates a company, bank, toll agency, delivery service, government office or one of the " +
            "user's accounts to get a click, a login, a payment or personal details.",
        Action.FILTER,
    ),
    ;

    companion object {
        /**
         * Phishing and "likely scam" were categories of their own until they were folded into spam:
         * stored verdicts, labels, settings and backups that still say so read as spam.
         */
        private val FORMER = mapOf("phishing" to "spam", "scam" to "spam")

        fun fromKey(key: String): Category? = (FORMER[key] ?: key).let { k -> entries.firstOrNull { it.key == k } }

        /** Only a current key: for settings, where a former category's choice mustn't overwrite spam's. */
        fun fromCurrentKey(key: String): Category? = entries.firstOrNull { it.key == key }
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

/**
 * RCS ids, as Google Messages leaves them in the phone's store for a chat it had over RCS
 * ("<id>@rcs.google.com"), in place of the person's number.
 */
object RcsIds {
    /** Any RCS id: a person's, or a business's ("@rbm.goog", RCS Business Messaging). */
    fun isRcs(address: String): Boolean {
        val domain = address.trim().lowercase().substringAfterLast('@', "")
        return domain.isNotEmpty() && (domain.startsWith("rcs.") || domain.contains(".rcs.") || domain.endsWith("rcs.telephony.goog") || isBusinessDomain(domain))
    }

    /** A person's RCS id: someone the user chatted with in Google Messages, never a business. */
    fun isPerson(address: String): Boolean = isRcs(address) && !isBusinessDomain(address.trim().lowercase().substringAfterLast('@', ""))

    private fun isBusinessDomain(domain: String) = domain == "rbm.goog" || domain.endsWith(".rbm.goog")
}

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
