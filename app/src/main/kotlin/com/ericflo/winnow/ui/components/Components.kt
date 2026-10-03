package com.ericflo.winnow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.data.StoredVerdict
import com.ericflo.winnow.ui.theme.avatarColors
import com.ericflo.winnow.ui.theme.categoryColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** A colored circle with the sender's initial, or a person glyph for bare numbers. */
@Composable
fun Avatar(name: String, seed: String, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val (container, content) = avatarColors(seed)
    val initial = name.firstOrNull { it.isLetter() }?.uppercaseChar()
    Box(
        modifier = modifier.size(size).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (initial != null && !name.startsWith("+") && !name.first().isDigit()) {
            Text(initial.toString(), color = content, fontSize = (size.value * 0.42f).sp, style = MaterialTheme.typography.titleMedium)
        } else {
            Icon(Icons.Filled.Person, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** "Likely scam · 98%" or "Promotion · silenced". */
@Composable
fun VerdictBadge(verdict: StoredVerdict, modifier: Modifier = Modifier) {
    val (container, content) = categoryColors(verdict.category)
    val label = verdict.category?.label ?: "Sender rule"
    val detail = when {
        verdict.userAction != null -> "your call"
        verdict.effectiveAction == Action.SILENCE -> "silenced"
        verdict.confidence < 1.0 -> "${(verdict.confidence * 100).toInt()}%"
        else -> null
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(
            text = if (detail != null) "$label · $detail" else label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val weekdayFormat = DateTimeFormatter.ofPattern("EEE")
private val monthDayFormat = DateTimeFormatter.ofPattern("MMM d")
private val fullDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)

/** Conversation-list timestamp: time today, weekday this week, date otherwise. */
fun shortTimestamp(epochMillis: Long, today: LocalDate = LocalDate.now()): String {
    val at = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)
    return when {
        days <= 0 -> at.format(timeFormat)
        days < 7 -> at.format(weekdayFormat)
        at.year == today.year -> at.format(monthDayFormat)
        else -> at.format(fullDateFormat)
    }
}

/** Day header inside a conversation. */
fun dayLabel(epochMillis: Long, today: LocalDate = LocalDate.now()): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (ChronoUnit.DAYS.between(date, today)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEEE, MMM d" else "MMM d, yyyy"))
    }
}

fun timeOfDay(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFormat)
