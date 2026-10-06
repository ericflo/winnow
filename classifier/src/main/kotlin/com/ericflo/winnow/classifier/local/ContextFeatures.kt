package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.MessageContext
import java.util.Calendar
import java.util.TimeZone

/**
 * A text's context as features a model can learn from: the time of day and week it came, whether
 * it opened its conversation or answered the user, how much came before it, and how long since
 * the last text. Words can't say these, and they can be what makes texts that read alike
 * different things to someone ("see you at 7" from a friend at night, from a salon on a weekday
 * morning). Named apart from the words ([PREFIX]), so a model that didn't learn from them never
 * reads them (see ContextPredictor).
 */
object ContextFeatures {
    const val PREFIX = "__ctx_"

    fun isContext(feature: String) = feature.startsWith(PREFIX)

    fun of(context: MessageContext?, zone: TimeZone = TimeZone.getDefault()): List<String> {
        context ?: return emptyList()
        val at = Calendar.getInstance(zone).apply { timeInMillis = context.sentAt }
        val hour = at.get(Calendar.HOUR_OF_DAY)
        val day = at.get(Calendar.DAY_OF_WEEK)
        return buildList {
            add(
                PREFIX + "hour_" + when (hour) {
                    in 0..5 -> "night"
                    in 6..11 -> "morning"
                    in 12..16 -> "afternoon"
                    in 17..21 -> "evening"
                    else -> "late"
                } + "__",
            )
            add(PREFIX + (if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) "weekend" else "weekday") + "__")
            val them = context.earlierFromThem
            val you = context.earlierFromYou
            if (them == 0 && you == 0) add(FIRST)
            add(PREFIX + "them_" + when {
                them == 0 -> "none"
                them <= 3 -> "few"
                them < MessageContext.CAP -> "some"
                else -> "many"
            } + "__")
            add(if (you == 0) NEVER_WROTE else WROTE)
            if (context.answersYou == true) add(ANSWERS)
            context.sinceLastMillis?.let { gap ->
                add(PREFIX + "gap_" + when {
                    gap < 10 * MINUTE -> "minutes"
                    gap < 12 * HOUR -> "hours"
                    gap < 7 * DAY -> "days"
                    else -> "weeks"
                } + "__")
            }
        }
    }

    /** One in words, for explaining a verdict; null for one not worth saying. */
    fun describe(feature: String): String? = DESCRIPTIONS[feature]

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val FIRST = PREFIX + "first__"
    private const val ANSWERS = PREFIX + "answers_you__"
    private const val WROTE = PREFIX + "you_wrote__"
    private const val NEVER_WROTE = PREFIX + "you_never__"

    private val DESCRIPTIONS = mapOf(
        PREFIX + "hour_night__" to "sent in the middle of the night",
        PREFIX + "hour_morning__" to "sent in the morning",
        PREFIX + "hour_afternoon__" to "sent in the afternoon",
        PREFIX + "hour_evening__" to "sent in the evening",
        PREFIX + "hour_late__" to "sent late at night",
        PREFIX + "weekend__" to "sent on a weekend",
        PREFIX + "weekday__" to "sent on a weekday",
        FIRST to "the first text in its conversation",
        PREFIX + "them_many__" to "from a long conversation",
        ANSWERS to "an answer to your text",
        WROTE to "in a conversation you've written in",
        NEVER_WROTE to "in a conversation you've never written in",
        PREFIX + "gap_minutes__" to "minutes after the last text",
        PREFIX + "gap_weeks__" to "after weeks of quiet",
    )
}
