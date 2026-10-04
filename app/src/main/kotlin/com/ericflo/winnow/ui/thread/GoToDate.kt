package com.ericflo.winnow.ui.thread

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ericflo.winnow.data.ChatMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * "Go to date": a calendar of the conversation, where only days with messages can be picked.
 * [onPick] gets the first message of the day picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoToDateDialog(messages: List<ChatMessage>, onPick: (ChatMessage) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    // Each day's first message, by the day as it was here.
    val firstOfDay = remember(messages) {
        messages.sortedBy { it.timestamp }
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .mapValues { (_, day) -> day.first() }
    }
    val newest = firstOfDay.keys.maxOrNull() ?: LocalDate.now(zone)
    val years = (firstOfDay.keys.minOrNull()?.year ?: newest.year)..newest.year
    // The picker counts days in UTC: a day's millis are its UTC midnight.
    fun utcDay(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    val state = rememberDatePickerState(
        initialDisplayedMonthMillis = newest.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = years,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcDay(utcTimeMillis) in firstOfDay
            override fun isSelectableYear(year: Int) = year in years
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { firstOfDay[utcDay(it)] }?.let(onPick) },
                enabled = state.selectedDateMillis != null,
            ) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, title = { Text("Go to date", modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) })
    }
}
