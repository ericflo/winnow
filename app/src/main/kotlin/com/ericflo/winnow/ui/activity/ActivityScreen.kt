package com.ericflo.winnow.ui.activity

import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import com.ericflo.winnow.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.data.VerdictRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class Window(val label: String, val days: Long?) { WEEK("7 days", 7), MONTH("30 days", 30), ALL("All time", null) }

data class ActivityUiState(
    val window: Window = Window.MONTH,
    val checked: Int = 0,
    val filtered: Int = 0,
    val silenced: Int = 0,
    val delivered: Int = 0,
    /** Every category, most common first, including zeros. */
    val byCategory: List<Pair<Category, Int>> = emptyList(),
    val onPhone: Int = 0,
    val byProvider: Int = 0,
    val costUsd: Double = 0.0,
    val topFiltered: List<Pair<String, Int>> = emptyList(),
    /** Oldest first: a day each, or a week each for long spans. */
    val buckets: List<Bucket> = emptyList(),
) {
    val keptQuiet: Int get() = filtered + silenced
}

/** What happened to the texts that arrived in one day (or week) starting at [start]. */
data class Bucket(val start: LocalDate, val days: Int, val delivered: Int, val silenced: Int, val filtered: Int) {
    val total: Int get() = delivered + silenced + filtered
}

class ActivityViewModel(private val container: AppContainer) : ViewModel() {
    private val window = MutableStateFlow(Window.MONTH)

    val state: StateFlow<ActivityUiState> = combine(container.messages.verdictRecords(), window) { records, window ->
        val since = window.days?.let { System.currentTimeMillis() - it * 24 * 3_600_000 } ?: Long.MIN_VALUE
        summarize(records.filter { it.decidedAt >= since }, window)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    fun select(value: Window) {
        window.value = value
    }

    private fun summarize(records: List<VerdictRecord>, window: Window): ActivityUiState {
        val counts = records.groupingBy { it.category }.eachCount()
        return ActivityUiState(
            window = window,
            checked = records.size,
            filtered = records.count { it.action == Action.FILTER },
            silenced = records.count { it.action == Action.SILENCE },
            delivered = records.count { it.action == Action.ALLOW },
            byCategory = Category.entries.map { it to (counts[it] ?: 0) }.sortedByDescending { it.second },
            onPhone = records.count { !it.byProvider },
            byProvider = records.count { it.byProvider },
            costUsd = records.sumOf { it.costUsd },
            topFiltered = records.filter { it.action == Action.FILTER }.groupingBy { it.sender }.eachCount()
                .entries.sortedByDescending { it.value }.take(5)
                .map { (sender, n) -> container.messages.displayName(sender) to n },
            buckets = buckets(records, window),
        )
    }

    /** Daily buckets for a week or a month; weekly ones when "All time" spans longer than that. */
    private fun buckets(records: List<VerdictRecord>, window: Window): List<Bucket> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dates = records.map { Instant.ofEpochMilli(it.decidedAt).atZone(zone).toLocalDate() }
        val spanDays = window.days ?: (ChronoUnit.DAYS.between(dates.minOrNull() ?: today, today) + 1).coerceAtLeast(30)
        val step = if (spanDays > 60) 7 else 1
        val count = ((spanDays + step - 1) / step).toInt().coerceAtMost(52)
        val first = today.minusDays((count * step - 1).toLong())
        return (0 until count).map { i ->
            val start = first.plusDays((i * step).toLong())
            val inBucket = records.filterIndexed { n, _ -> !dates[n].isBefore(start) && dates[n].isBefore(start.plusDays(step.toLong())) }
            Bucket(
                start = start,
                days = step,
                delivered = inBucket.count { it.action == Action.ALLOW },
                silenced = inBucket.count { it.action == Action.SILENCE },
                filtered = inBucket.count { it.action == Action.FILTER },
            )
        }
    }
}

/** What Winnow has been doing: how many texts it checked, and what it did with them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(viewModel: ActivityViewModel, onBack: () -> Unit, onOpenMetrics: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {
            item("window") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Window.entries.forEachIndexed { i, w ->
                        SegmentedButton(
                            selected = w == state.window,
                            onClick = { viewModel.select(w) },
                            shape = SegmentedButtonDefaults.itemShape(i, Window.entries.size),
                        ) { Text(w.label) }
                    }
                }
            }
            item("hero") { QuietHero(state) }
            item("timeline") {
                Card("Day by day") {
                    if (state.checked == 0) {
                        Text("Nothing checked in this period yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Timeline(state.buckets)
                    }
                }
            }
            item("categories") {
                Card("What arrived") {
                    if (state.checked == 0) {
                        Text("Nothing checked in this period yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val max = state.byCategory.maxOf { it.second }.coerceAtLeast(1)
                        state.byCategory.forEach { (category, count) -> CategoryBar(category.label, count, max, state.checked) }
                    }
                }
            }
            item("deciders") {
                Card("Who decided") {
                    Line("On this phone", "${state.onPhone}", "Contacts, people you've texted, codes, sender rules, Winnow's model")
                    Line("Classifier service", "${state.byProvider}", if (state.costUsd > 0) "About $${"%.4f".format(state.costUsd)} in total" else null)
                }
            }
            if (state.topFiltered.isNotEmpty()) {
                item("senders") {
                    Card("Most filtered senders") {
                        state.topFiltered.forEach { (name, n) -> Line(name, "$n", null) }
                    }
                }
            }
            item("accuracy") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenMetrics),
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_insights), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text("How accurate is Winnow?", style = MaterialTheme.typography.titleMedium)
                            Text("ROC curve, precision and recall, calibration, κ and MCC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item("footer") {
                Text(
                    "Counted on this phone from Winnow's own records. ${state.checked} messages checked in this period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

/** The three outcomes, in a fixed order and color everywhere on this screen. */
private enum class Outcome(val label: String) { FILTERED("Filtered"), SILENCED("Silenced"), DELIVERED("Delivered") }

@Composable
private fun Outcome.color(): Color = when (this) {
    Outcome.FILTERED -> MaterialTheme.colorScheme.primary
    Outcome.SILENCED -> MaterialTheme.colorScheme.tertiary
    Outcome.DELIVERED -> MaterialTheme.colorScheme.outline
}

/** How many texts never buzzed the phone, around a ring of what happened to everything. */
@Composable
private fun QuietHero(state: ActivityUiState) {
    val c = MaterialTheme.colorScheme
    val values = listOf(Outcome.FILTERED to state.filtered, Outcome.SILENCED to state.silenced, Outcome.DELIVERED to state.delivered)
    val colors = values.map { it.first.color() }
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(state.checked, state.window) {
        sweep.snapTo(0f)
        sweep.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }
    val shown by animateIntAsState(state.keptQuiet, tween(900), label = "keptQuiet")
    Surface(shape = RoundedCornerShape(28.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .background(Brush.linearGradient(listOf(c.primaryContainer, c.surfaceContainer, c.tertiaryContainer.copy(alpha = 0.6f))))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                Modifier.size(132.dp).semantics {
                    contentDescription = "${state.keptQuiet} texts kept quiet: ${state.filtered} filtered, ${state.silenced} silenced, ${state.delivered} delivered"
                },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(132.dp)) {
                    val stroke = 16.dp.toPx()
                    val arc = Size(size.width - stroke, size.height - stroke)
                    val topLeft = Offset(stroke / 2, stroke / 2)
                    val total = values.sumOf { it.second }
                    if (total == 0) {
                        drawArc(c.surfaceContainerHighest, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
                    } else {
                        // A small gap between segments keeps them distinct where they meet.
                        val gap = if (values.count { it.second > 0 } > 1) 4f else 0f
                        var start = -90f
                        values.forEachIndexed { i, (_, n) ->
                            if (n == 0) return@forEachIndexed
                            val extent = 360f * n / total * sweep.value
                            drawArc(colors[i], start + gap / 2, (extent - gap).coerceAtLeast(0.5f), false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                            start += extent
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$shown", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("kept quiet", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                Text(
                    if (state.checked == 0) "No texts checked yet" else "${state.keptQuiet} of ${state.checked} texts never buzzed your phone",
                    style = MaterialTheme.typography.titleSmall,
                )
                values.forEach { (outcome, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(outcome.color(), CircleShape))
                        Text(outcome.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Text("$n", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * Stacked bars, one per day (or week), oldest first. Tapping a bar shows its numbers; until
 * then the readout sums the whole period.
 */
@Composable
private fun Timeline(buckets: List<Bucket>) {
    val c = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = c.onSurfaceVariant)
    var selected by remember(buckets) { mutableStateOf<Int?>(null) }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(buckets) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val order = listOf(Outcome.DELIVERED, Outcome.SILENCED, Outcome.FILTERED)
    val colors = order.map { it.color() }
    val max = buckets.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
    val niceMax = niceCeiling(max)
    val formatter = DateTimeFormatter.ofPattern("MMM d")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(Outcome.FILTERED, Outcome.SILENCED, Outcome.DELIVERED).forEach { o ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(o.color(), CircleShape))
                    Text("  ${o.label}", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = "Texts per ${if (buckets.firstOrNull()?.days == 7) "week" else "day"}, up to $max" }
                .pointerInput(buckets) {
                    detectTapGestures { pos ->
                        val left = 28.dp.toPx()
                        val slot = (size.width - left) / buckets.size
                        selected = ((pos.x - left) / slot).toInt().coerceIn(0, buckets.lastIndex)
                    }
                },
        ) {
            val left = 28.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            val plotHeight = bottom - 6.dp.toPx()
            for (tick in listOf(0, niceMax / 2, niceMax)) {
                val y = bottom - plotHeight * tick / niceMax
                drawLine(c.outlineVariant, Offset(left, y), Offset(size.width, y), 1.dp.toPx())
                val label = measurer.measure("$tick", tickStyle)
                drawText(label, topLeft = Offset(left - label.size.width - 6.dp.toPx(), y - label.size.height / 2))
            }
            val slot = (size.width - left) / buckets.size
            val gap = (slot * 0.25f).coerceIn(1.dp.toPx(), 6.dp.toPx())
            buckets.forEachIndexed { i, b ->
                val x = left + i * slot + gap / 2
                val w = slot - gap
                var top = bottom
                val parts = listOf(b.delivered, b.silenced, b.filtered)
                parts.forEachIndexed { p, n ->
                    if (n == 0) return@forEachIndexed
                    val h = plotHeight * n / niceMax * grow.value
                    val isTop = parts.subList(p + 1, parts.size).all { it == 0 }
                    val alpha = if (selected == null || selected == i) 1f else 0.35f
                    // Segments stack from the baseline, with a hairline of surface between them;
                    // only the top one gets rounded corners.
                    val rect = Rect(x, top - h, x + w, top - if (p > 0) 1.dp.toPx() else 0f)
                    if (isTop) {
                        drawPath(Path().apply {
                            addRoundRect(RoundRect(rect, topLeft = CornerRadius(3.dp.toPx()), topRight = CornerRadius(3.dp.toPx())))
                        }, colors[p].copy(alpha = alpha))
                    } else {
                        drawRect(colors[p].copy(alpha = alpha), rect.topLeft, rect.size)
                    }
                    top -= h
                }
            }
            listOf(0, buckets.lastIndex / 2, buckets.lastIndex).distinct().forEach { i ->
                val text = if (i == buckets.lastIndex && buckets[i].days == 1) "Today" else buckets[i].start.format(formatter)
                val label = measurer.measure(text, tickStyle)
                val cx = left + (i + 0.5f) * slot
                drawText(label, topLeft = Offset((cx - label.size.width / 2).coerceIn(left, size.width - label.size.width), bottom + 4.dp.toPx()))
            }
        }
        val b = selected?.let(buckets::getOrNull)
        val readout = if (b == null) {
            "Tap a bar for that ${if (buckets.firstOrNull()?.days == 7) "week" else "day"}."
        } else {
            val day = if (b.days == 7) "Week of ${b.start.format(formatter)}" else b.start.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
            "$day: ${b.filtered} filtered · ${b.silenced} silenced · ${b.delivered} delivered"
        }
        Text(readout, style = MaterialTheme.typography.bodyMedium, color = if (b == null) c.onSurfaceVariant else c.onSurface)
    }
}

/** 1, 2, 5, 10, 20, 50… at or above [n], for round axis ticks. */
private fun niceCeiling(n: Int): Int {
    var scale = 1
    while (true) {
        for (m in intArrayOf(2, 4, 10)) if (m * scale >= n) return m * scale
        scale *= 10
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** One labeled bar. A single hue for every row: the label, not the color, names the category. */
@Composable
private fun CategoryBar(label: String, count: Int, max: Int, total: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label: $count" },
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
        Box(Modifier.weight(1f).height(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp))) {
            if (count > 0) {
                Box(Modifier.fillMaxWidth(count / max.toFloat()).height(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
            }
        }
        Text("$count", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        Text(
            if (total == 0) "" else "${(count * 100f / total).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
private fun Line(label: String, value: String, detail: String?) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
