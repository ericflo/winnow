package com.ericflo.winnow.ui.insight

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

/*
 * The pieces the screens about Winnow's model and its runs are built from: cards, numbers with
 * what they count, bars with their values, notes. One look across all of them, so a number
 * means the same thing wherever it's shown.
 */

/** A card with a title, an optional line saying what it shows, and its content. */
@Composable
fun InsightCard(title: String, modifier: Modifier = Modifier, subtitle: String? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick) else it },
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
    }
}

/** A number and what it counts, beneath it. */
@Composable
fun Stat(value: String, caption: String, modifier: Modifier = Modifier, emphasis: Boolean = false) {
    Column(modifier) {
        Text(value, style = if (emphasis) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row of [Stat]s sharing the width. */
@Composable
fun StatRow(items: List<Pair<String, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        items.forEach { (value, caption) -> Stat(value, caption, Modifier.weight(1f)) }
    }
}

/**
 * A labeled bar: [count] of [max] filled, the count and its share of [total] beside it. One hue
 * for every row: the label, not the color, says what it is. [leading] goes before the label (a
 * category's dot, say).
 */
@Composable
fun BarRow(label: String, count: Int, max: Int, total: Int, leading: (@Composable () -> Unit)? = null, color: Color = MaterialTheme.colorScheme.primary, labelWidth: Dp = 112.dp) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label: ${count(count)}${if (total > 0) ", ${share(count, total)}" else ""}" },
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(labelWidth))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (count > 0 && max > 0) Box(Modifier.fillMaxWidth(count / max.toFloat()).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
        Text(count(count), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
        Text(if (total == 0) "" else share(count, total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
    }
}

/** A share of a whole as a 0–1 bar with its percentage, for rates (agreement, accuracy). */
@Composable
fun RateBar(label: String, part: Int, whole: Int, color: Color = MaterialTheme.colorScheme.primary, detail: String? = null) {
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label: ${count(part)} of ${count(whole)}${if (whole > 0) ", ${share(part, whole)}" else ""}" }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (whole == 0) "—" else share(part, whole), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (whole > 0 && part > 0) Box(Modifier.fillMaxWidth(part / whole.toFloat()).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
        Text(
            (if (whole == 0) "Nothing to compare yet" else "${count(part)} of ${count(whole)}") + (detail?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Small print: how a number was worked out, or what to make of it. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** One fact on a line: its name, and its value at the end. */
@Composable
fun Fact(label: String, value: String, detail: String? = null) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
    }
}

/** A legend entry: a dot of [color] and what it stands for. */
@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text("  $label", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Filters in one row that scrolls sideways; [counts] says how many each would show. */
@Composable
fun <T> FilterRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, counts: ((T) -> Int)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        options.forEach { option ->
            val n = counts?.invoke(option)
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option) + (n?.let { " · ${count(it)}" } ?: "")) },
            )
        }
    }
}

fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** [part] of [whole] as a percentage: whole numbers, but a decimal near the ends where it matters. */
fun share(part: Int, whole: Int): String = if (whole == 0) "—" else pct(part.toDouble() / whole)

fun pct(x: Double): String = when {
    x.isNaN() -> "—"
    x >= 0.995 && x < 1.0 || x in 0.0..0.1 && x > 0 -> String.format(Locale.US, "%.1f%%", x * 100)
    else -> String.format(Locale.US, "%.0f%%", x * 100)
}

fun f2(x: Double): String = if (x.isNaN()) "—" else String.format(Locale.US, "%.2f", x)

fun f3(x: Double): String = if (x.isNaN()) "—" else String.format(Locale.US, "%.3f", x)

/** Money a service charged: nothing, under a cent, or dollars and cents. */
fun money(usd: Double): String = when {
    usd <= 0.0 -> "nothing"
    usd < 0.01 -> "under a cent"
    else -> String.format(Locale.US, "$%.2f", usd)
}

/** How long something took: "45 s", "6 min", "1 h 20 min". */
fun duration(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${(s + 30) / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

/** When something happened, relative to now: "just now", "5 minutes ago", "yesterday". */
fun ago(at: Long, now: Long = System.currentTimeMillis()): String =
    if (now - at < 60_000) "just now"
    else android.text.format.DateUtils.getRelativeTimeSpanString(at, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
