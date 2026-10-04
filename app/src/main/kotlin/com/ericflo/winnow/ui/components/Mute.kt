package com.ericflo.winnow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** How long a conversation stays muted. */
private val MUTE_CHOICES = listOf(
    "1 hour" to 60 * 60_000L,
    "8 hours" to 8 * 60 * 60_000L,
    "1 day" to 24 * 60 * 60_000L,
    "Until I turn it back on" to null,
)

/** Picks how long to mute; [onMute] gets the end time in epoch millis, or null for until turned off. */
@Composable
fun MuteDialog(onMute: (until: Long?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mute notifications") },
        text = {
            Column {
                MUTE_CHOICES.forEach { (label, length) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onMute(length?.let { System.currentTimeMillis() + it }) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Muted", or "Muted until 6:30 PM" / "until tomorrow, 9:00 AM" / "until Oct 9, 9:00 AM" for a timed mute. */
fun mutedLabel(until: Long?, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (until == null) return "Muted"
    val at = Instant.ofEpochMilli(until).atZone(zone)
    val time = at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val day = at.toLocalDate()
    return when (day) {
        today -> "Muted until $time"
        today.plusDays(1) -> "Muted until tomorrow, $time"
        else -> "Muted until ${day.format(DateTimeFormatter.ofPattern("MMM d"))}, $time"
    }
}
