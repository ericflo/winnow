package com.ericflo.winnow.classifier.local

/**
 * The shapes in a text that its words lose: the Featurizer keeps letters and digits and turns
 * every number into a placeholder, so "20% off", "3:30 PM", "10/15", "promo code SAVE20" and
 * "order #A1B2C3" reach a model as bare numbers. These say which of them a text has: percents
 * (and percents off), times, dates, weekday names, promo codes, order and tracking numbers, a run
 * of emoji, several links. Where marketing and transactional, or a reminder and a receipt, read
 * alike, these can tell them apart. Named apart from the words ([PREFIX]), so a model that didn't
 * learn from them never reads them (see ShapesPredictor).
 */
object TextShapes {
    const val PREFIX = "__shape_"

    fun isShape(feature: String) = feature.startsWith(PREFIX)

    fun of(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        return buildList {
            if (PERCENT.containsMatchIn(body)) add(PREFIX + "percent__")
            if (PERCENT_OFF.containsMatchIn(body)) add(PREFIX + "percent_off__")
            if (TIME.containsMatchIn(body)) add(PREFIX + "time__")
            if (DATE.containsMatchIn(body) || MONTH_DAY.containsMatchIn(body)) add(PREFIX + "date__")
            if (WEEKDAY.containsMatchIn(body)) add(PREFIX + "weekday__")
            if (PROMO_CODE.containsMatchIn(body)) add(PREFIX + "promo_code__")
            if (ORDER_NUMBER.containsMatchIn(body)) add(PREFIX + "order_number__")
            if (body.codePoints().filter { it >= 0x1F300 || it in 0x2600..0x27BF }.count() >= 3) add(PREFIX + "emoji_run__")
            if (LINK.findAll(body).count() >= 2) add(PREFIX + "links__")
        }
    }

    /** One in words, for explaining a verdict; null for one not worth saying. */
    fun describe(feature: String): String? = DESCRIPTIONS[feature]

    private val PERCENT = Regex("""\d\s?%""")
    private val PERCENT_OFF = Regex("""(?i)\d\s?%\s*off\b""")
    private val TIME = Regex("""(?i)\b\d{1,2}:\d{2}\s?(?:[ap]\.?m\.?)?\b|\b\d{1,2}\s?[ap]\.?m\b\.?""")
    private val DATE = Regex("""\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b""")
    private val MONTH_DAY = Regex("""(?i)\b(?:jan|feb|mar|apr|may|jun|jul|aug|sept?|oct|nov|dec)[a-z]*\.?\s+\d{1,2}(?:st|nd|rd|th)?\b""")
    private val WEEKDAY = Regex("""(?i)\b(?:mon|tues?|wed|thu(?:rs?)?|fri|sat|sun)(?:day)?\b""")
    private val PROMO_CODE = Regex("""(?i)\b(?:promo|coupon|discount|use)\s*(?:code)?\s*:?\s*[A-Z]*\d*[A-Z][A-Z0-9]{3,}\b|\bcode\s*:?\s*[A-Z]{2,}[A-Z0-9]{2,}\b""")
    private val ORDER_NUMBER = Regex("""(?i)\b(?:order|tracking|confirmation|reference|ref|invoice)\s*(?:#|no\.?|number)?\s*:?\s*#?[A-Z0-9-]{5,}\b|#\s?[A-Z0-9-]{6,}\b""")
    private val LINK = Regex("""(?i)\bhttps?://\S+|\bwww\.\S+""")

    private val DESCRIPTIONS = mapOf(
        PREFIX + "percent_off__" to "a percent off",
        PREFIX + "percent__" to "a percentage",
        PREFIX + "time__" to "a time of day",
        PREFIX + "date__" to "a date",
        PREFIX + "weekday__" to "a day of the week",
        PREFIX + "promo_code__" to "a promo code",
        PREFIX + "order_number__" to "an order or tracking number",
        PREFIX + "emoji_run__" to "a run of emoji",
        PREFIX + "links__" to "several links",
    )
}
