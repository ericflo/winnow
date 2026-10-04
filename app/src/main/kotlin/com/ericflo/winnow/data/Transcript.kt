package com.ericflo.winnow.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A conversation as plain text, for Export: a heading per day, then "time  name: text". */
object Transcript {
    fun render(
        title: String,
        messages: List<ChatMessage>,
        nameOf: (ChatMessage) -> String,
        exportedOn: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = buildString {
        val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        appendLine("Conversation with $title")
        appendLine("Exported from Winnow on ${exportedOn.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))}")
        var current: LocalDate? = null
        for (m in messages.sortedBy { it.timestamp }) {
            val at = Instant.ofEpochMilli(m.timestamp).atZone(zone)
            if (at.toLocalDate() != current) {
                current = at.toLocalDate()
                appendLine()
                appendLine(current.format(day))
            }
            val stamp = at.format(time).replace(' ', ' ')
            val lines = buildList {
                if (m.body.isNotBlank()) addAll(m.body.lines())
                m.attachments.forEach { a -> add("[${attachmentSummary(listOf(a.contentType))}${a.name?.let { ": $it" } ?: ""}]") }
                if (m.isPlaceholder) add("[Picture message, not downloaded]")
            }.ifEmpty { listOf("") }
            val head = "$stamp  ${nameOf(m)}: "
            appendLine(head + lines.first())
            // Continuation lines line up under the text, not the time.
            lines.drop(1).forEach { appendLine(" ".repeat(head.length) + it) }
        }
    }
}
