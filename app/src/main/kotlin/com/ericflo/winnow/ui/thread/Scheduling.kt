package com.ericflo.winnow.ui.thread

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ericflo.winnow.data.db.ScheduledMessageEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** "Today, 6:00 PM", "Tomorrow, 8:00 AM", "Tue, Oct 7, 9:30 AM". */
fun scheduleLabel(at: Long, today: LocalDate = LocalDate.now()): String {
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val clock = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val day = when (ChronoUnit.DAYS.between(today, time.toLocalDate())) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> time.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
    return "$day, $clock"
}

/** Quick picks, as in Messages: later today and tonight while they're still ahead, and tomorrow morning. */
private fun quickTimes(now: LocalDateTime = LocalDateTime.now()): List<Long> {
    fun at(date: LocalDate, hour: Int) = LocalDateTime.of(date, LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val today = now.toLocalDate()
    return buildList {
        if (now.hour < 17) add(at(today, 18))
        if (now.hour < 20) add(at(today, 21))
        add(at(today.plusDays(1), 8))
    }
}

/** Send on tap; long-press for scheduling, like Messages. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SendButton(enabled: Boolean, onSend: () -> Unit, onSchedule: (at: Long, label: String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.12f))
                .combinedClickable(enabled = enabled, onClick = onSend, onLongClick = { menu = true }, onLongClickLabel = "Schedule send"),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (enabled) colors.onPrimary else colors.onSurface.copy(alpha = 0.38f),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                "Schedule send",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            quickTimes().forEach { at ->
                val label = scheduleLabel(at)
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    menu = false
                    onSchedule(at, label)
                })
            }
            DropdownMenuItem(
                text = { Text("Pick date and time") },
                leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                onClick = {
                    menu = false
                    picking = true
                },
            )
        }
    }
    if (picking) {
        PickDateTimeDialog(onDismiss = { picking = false }, onPicked = { at ->
            picking = false
            onSchedule(at, scheduleLabel(at))
        })
    }
}

/** A date picker, then a time picker. Only future times are accepted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDateTimeDialog(onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    var date by remember { mutableStateOf<LocalDate?>(null) }
    val todayUtc = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = todayUtc,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
        },
    )
    val pickedDate = date
    if (pickedDate == null) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    date = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                }, enabled = dateState.selectedDateMillis != null) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(state = dateState) }
    } else {
        val now = LocalTime.now()
        val timeState = rememberTimePickerState(initialHour = (now.hour + 1) % 24, initialMinute = 0)
        val at = LocalDateTime.of(pickedDate, LocalTime.of(timeState.hour, timeState.minute))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Send at") },
            text = { TimePicker(state = timeState) },
            confirmButton = { TextButton(onClick = { onPicked(at) }, enabled = at > System.currentTimeMillis()) { Text("Schedule") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

/** A scheduled text: outlined like a draft, with its send time and a tap menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScheduledBubble(
    message: ScheduledMessageEntity,
    onSendNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box {
            Text(
                message.body,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .wrapContentWidth(Alignment.End)
                    .clip(RoundedCornerShape(22.dp))
                    .border(BorderStroke(1.dp, colors.outline), RoundedCornerShape(22.dp))
                    .combinedClickable(onClick = { menu = true }, onLongClick = { menu = true })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Send now") }, onClick = { menu = false; onSendNow() })
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
        Text(
            "Scheduled · ${scheduleLabel(message.sendAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
