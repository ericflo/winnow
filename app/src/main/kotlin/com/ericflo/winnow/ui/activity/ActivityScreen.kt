package com.ericflo.winnow.ui.activity

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.R
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classify.Decider
import com.ericflo.winnow.classify.ModelReason
import com.ericflo.winnow.classify.Provenance
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.MessageTexts
import com.ericflo.winnow.data.VerdictRecord
import com.ericflo.winnow.ui.components.CategoryDot
import com.ericflo.winnow.ui.insight.FilterRow
import com.ericflo.winnow.ui.insight.ProvenanceSheet
import com.ericflo.winnow.ui.insight.count
import com.ericflo.winnow.ui.insight.pct
import com.ericflo.winnow.ui.insight.share
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

enum class Window(val label: String, val days: Long?) { WEEK("7 days", 7), MONTH("30 days", 30), ALL("All time", null) }

/**
 * Who decided a text, grouped as the Activity screen shows them: the classifier service, the
 * on-device model (for whatever reason it was the one), a rule on the phone, or the keyword
 * fallback. In this order everywhere, each with its own color (see [deciderColor]).
 */
enum class Who(val label: String) { SERVICE("Classifier service"), MODEL("On-device model"), RULE("Rule on this phone"), FALLBACK("Keyword fallback") }

/** What happened to a text: the outcome, in a fixed order and color everywhere on this screen. */
enum class Outcome(val label: String) { FILTERED("Filtered"), SILENCED("Silenced"), DELIVERED("Delivered") }

fun whoOf(r: VerdictRecord): Who = when (Provenance.decider(r.sourceKind, r.sourceDetail)) {
    Decider.PROVIDER -> Who.SERVICE
    Decider.MODEL -> Who.MODEL
    Decider.FALLBACK -> Who.FALLBACK
    Decider.RULE, Decider.YOU -> Who.RULE
}

fun outcomeOf(action: Action) = when (action) {
    Action.FILTER -> Outcome.FILTERED
    Action.SILENCE -> Outcome.SILENCED
    Action.ALLOW -> Outcome.DELIVERED
}

/** One decider's texts: how many, and what became of them. */
data class WhoRow(val who: Who, val total: Int, val outcomes: Map<Outcome, Int>, val changedSince: Int)

/** What happened to the texts that arrived in one day (or week) starting at [start]: by outcome, and by who decided. */
data class Bucket(val start: LocalDate, val days: Int, val outcomes: Map<Outcome, Int>, val who: Map<Who, Int>) {
    val total: Int get() = outcomes.values.sum()
}

/** One decision, as the log lists it. */
data class LogRow(
    val key: String,
    val threadId: Long,
    val sender: String,
    val name: String,
    val at: Long,
    val who: Who,
    /** Why the model was the one, when it was. */
    val why: ModelReason?,
    val category: Category?,
    val confidence: Double,
    val outcome: Outcome,
    val rule: String?,
    val mine: Category?,
    val changed: Boolean,
    /** The service that decided it, by name, when one did: the one asked then, not whichever is set up now. */
    val serviceName: String? = null,
)

data class ActivityUiState(
    val window: Window = Window.MONTH,
    val checked: Int = 0,
    val outcomes: Map<Outcome, Int> = emptyMap(),
    val who: List<WhoRow> = emptyList(),
    /** Every category, most common first, including zeros. */
    val byCategory: List<Pair<Category, Int>> = emptyList(),
    val costUsd: Double = 0.0,
    val topFiltered: List<Pair<String, Int>> = emptyList(),
    /** Oldest first: a day each, or a week each for long spans. */
    val buckets: List<Bucket> = emptyList(),
    /** Newest first. */
    val log: List<LogRow> = emptyList(),
    /** The classifier service as the user knows it. */
    val service: String = "Classifier service",
) {
    fun count(o: Outcome) = outcomes[o] ?: 0
    val keptQuiet: Int get() = count(Outcome.FILTERED) + count(Outcome.SILENCED)
}

class ActivityViewModel(private val container: AppContainer) : ViewModel() {
    private val window = MutableStateFlow(Window.MONTH)

    val state: StateFlow<ActivityUiState> = combine(container.messages.verdictRecords(), window, container.settings.settings) { records, window, settings ->
        // Whole days, matching the bars: "7 days" is today and the six before it.
        val since = window.days?.let { days ->
            LocalDate.now().minusDays(days - 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } ?: Long.MIN_VALUE
        summarize(records.filter { it.decidedAt >= since }, window, settings.provider.label.substringBefore(" ("))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    /** The words of the decisions the log shows, by message key, read once they're listed. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val texts: StateFlow<Map<String, String>> = state.map { s -> s.log.take(LOG_TEXTS).map { it.key } }
        .mapLatest { keys -> MessageTexts(container.appContext).of(keys).mapValues { it.value.body } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun select(value: Window) {
        window.value = value
    }

    suspend fun explain(key: String) = container.provenance.explain(key)

    private fun summarize(records: List<VerdictRecord>, window: Window, current: String): ActivityUiState {
        fun nameOf(providerId: String) = com.ericflo.winnow.data.ProviderKind.labelFor(providerId).substringBefore(" (")
        // The services that decided these texts, not just the one set up now: one by name, more as a kind.
        val services = records.filter { it.byProvider }.map { it.sourceDetail }.distinct()
        val service = when (services.size) {
            0 -> current
            1 -> nameOf(services.single())
            else -> "Classifier services"
        }
        val counts = records.groupingBy { it.category }.eachCount()
        val byWho = records.groupBy(::whoOf)
        fun changed(r: VerdictRecord) = (r.userCategory != null && r.userCategory != r.category) || (r.userAction != null && r.userAction != r.action)
        return ActivityUiState(
            window = window,
            checked = records.size,
            outcomes = records.groupingBy { outcomeOf(it.action) }.eachCount(),
            who = Who.entries.map { w ->
                val rows = byWho[w].orEmpty()
                WhoRow(w, rows.size, rows.groupingBy { outcomeOf(it.action) }.eachCount(), rows.count(::changed))
            },
            byCategory = Category.entries.map { it to (counts[it] ?: 0) }.sortedByDescending { it.second },
            costUsd = records.sumOf { it.costUsd },
            // One row per sender, however the carrier wrote the number each time.
            topFiltered = records.filter { it.action == Action.FILTER }.groupBy { ContactLookup.numberKey(it.sender) ?: it.sender }
                .values.map { it.first().sender to it.size }
                .sortedByDescending { it.second }.take(5)
                .map { (sender, n) -> container.messages.displayName(sender) to n },
            buckets = buckets(records, window),
            log = records.sortedByDescending { it.decidedAt }.take(LOG_ROWS).map { r ->
                LogRow(
                    key = r.key, threadId = r.threadId, sender = r.sender, name = container.messages.displayName(r.sender), at = r.decidedAt,
                    who = whoOf(r), why = Provenance.modelReason(r.sourceKind, r.fallbackReason, r.localModel),
                    category = r.category, confidence = r.confidence, outcome = outcomeOf(r.action),
                    rule = r.sourceDetail.takeIf { whoOf(r) == Who.RULE }, mine = r.userCategory, changed = changed(r),
                    serviceName = if (r.byProvider) nameOf(r.sourceDetail) else null,
                )
            },
            service = service,
        )
    }

    /** Daily buckets for a week or a month; weekly ones when "All time" spans longer than that. */
    private fun buckets(records: List<VerdictRecord>, window: Window): List<Bucket> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dates = records.map { Instant.ofEpochMilli(it.decidedAt).atZone(zone).toLocalDate() }
        val spanDays = window.days ?: (ChronoUnit.DAYS.between(dates.minOrNull() ?: today, today) + 1).coerceAtLeast(30)
        // Daily up to 52 days, weekly up to a year, then as wide as it takes: never more than 52
        // bars, and every day in one of them.
        val step = when {
            spanDays <= 52 -> 1
            spanDays <= 364 -> 7
            else -> ((spanDays + 51) / 52).toInt()
        }
        val count = ((spanDays + step - 1) / step).toInt().coerceAtMost(52)
        val first = today.minusDays((count * step - 1).toLong())
        return (0 until count).map { i ->
            val start = first.plusDays((i * step).toLong())
            val inBucket = records.filterIndexed { n, _ -> !dates[n].isBefore(start) && dates[n].isBefore(start.plusDays(step.toLong())) }
            Bucket(
                start = start,
                days = step,
                outcomes = inBucket.groupingBy { outcomeOf(it.action) }.eachCount(),
                who = inBucket.groupingBy(::whoOf).eachCount(),
            )
        }
    }

    private companion object {
        /** Decisions the log lists, newest first, and of those, how many have their words read. */
        const val LOG_ROWS = 300
        const val LOG_TEXTS = 300
    }
}

/**
 * The colors of who decided, validated as a set for both themes (dataviz palette slots 1–4, in
 * this order, adjacent in every stack). In light mode three sit under 3:1 against the surface, so
 * every place they appear also says in words what they are.
 */
@Composable
fun deciderColor(who: Who): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (who) {
        Who.SERVICE -> if (dark) Color(0xFF3987E5) else Color(0xFF2A78D6)
        Who.MODEL -> if (dark) Color(0xFFD95926) else Color(0xFFEB6834)
        Who.RULE -> if (dark) Color(0xFF199E70) else Color(0xFF1BAF7A)
        Who.FALLBACK -> if (dark) Color(0xFFC98500) else Color(0xFFEDA100)
    }
}

@Composable
private fun Outcome.color(): Color = when (this) {
    Outcome.FILTERED -> MaterialTheme.colorScheme.primary
    Outcome.SILENCED -> MaterialTheme.colorScheme.tertiary
    Outcome.DELIVERED -> MaterialTheme.colorScheme.outline
}

private fun Who.name(service: String) = if (this == Who.SERVICE) service else label

/**
 * What Winnow has been doing, and who did it: how many texts it checked, who decided each (the
 * classifier service, the on-device model, a rule on the phone, the keyword fallback), what
 * became of them, day by day, and every decision, each with why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(viewModel: ActivityViewModel, onBack: () -> Unit, onOpenMetrics: () -> Unit = {}, onOpenModel: () -> Unit = {}, onOpenRun: (Long) -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val texts by viewModel.texts.collectAsStateWithLifecycle()
    var explaining by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        var logFilter by rememberSaveable { mutableStateOf<String?>(null) }
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {
            item("window") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Window.entries.forEachIndexed { i, w ->
                        SegmentedButton(selected = w == state.window, onClick = { viewModel.select(w) }, shape = SegmentedButtonDefaults.itemShape(i, Window.entries.size)) { Text(w.label) }
                    }
                }
            }
            item("hero") { QuietHero(state) }
            item("who") { WhoCard(state) }
            item("timeline") {
                Card("Day by day", subtitle = "Tap a bar for that ${if (state.buckets.firstOrNull()?.days == 7) "week" else "day"}") {
                    if (state.checked == 0) Empty() else Timeline(state)
                }
            }
            item("table") { WhatEachDid(state) }
            item("categories") {
                Card("What arrived", subtitle = "By the category it was given as it arrived; your labels since are in the log below") {
                    if (state.checked == 0) Empty() else {
                        val max = state.byCategory.maxOf { it.second }.coerceAtLeast(1)
                        state.byCategory.forEach { (category, n) -> CategoryBar(category, n, max, state.checked) }
                    }
                }
            }
            if (state.topFiltered.isNotEmpty()) {
                item("senders") {
                    Card("Most filtered senders") {
                        state.topFiltered.forEach { (name, n) -> Line(name, count(n), null) }
                    }
                }
            }
            item("log-head") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text("Every decision", style = MaterialTheme.typography.titleMedium)
                    Text("Newest first. Tap one for who decided, what each thought, and why.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val options = listOf<String?>(null) + Who.entries.map { it.name } + listOf(CHANGED, FILTERED)
                    FilterRow(
                        options = options,
                        selected = logFilter,
                        label = { o -> when (o) { null -> "All"; CHANGED -> "You changed"; FILTERED -> "Filtered"; else -> Who.valueOf(o).name(state.service) } },
                        onSelect = { logFilter = it },
                        counts = { o -> state.log.count { matches(it, o) } },
                    )
                }
            }
            val shown = state.log.filter { matches(it, logFilter) }
            if (shown.isEmpty()) item("log-none") { Empty() }
            items(shown, key = { it.key }) { row -> LogItem(row, texts[row.key], state.service) { explaining = row.key } }
            item("links") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    LinkCard("Winnow's model", "What it learned from, who agrees with whom, how it's built: score it and rebuild it", onOpenModel)
                    LinkCard("How accurate is Winnow?", "Every chart, worked out from your labels", onOpenMetrics)
                }
            }
            item("footer") {
                Text(
                    "Counted on this phone from Winnow's own records of each text as it arrived. ${count(state.checked)} in this period" +
                        if (state.costUsd > 0) "; the service's answers cost about $${"%.4f".format(state.costUsd)}." else ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
    explaining?.let { key -> ProvenanceSheet(load = { viewModel.explain(key) }, onDismiss = { explaining = null }, onOpenRun = onOpenRun) }
}

private const val CHANGED = "changed"
private const val FILTERED = "filtered"

private fun matches(row: LogRow, filter: String?) = when (filter) {
    null -> true
    CHANGED -> row.changed
    FILTERED -> row.outcome == Outcome.FILTERED
    else -> row.who.name == filter
}

@Composable
private fun Empty() {
    Text("Nothing in this period yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** How many texts never buzzed the phone, around a ring of what happened to everything. */
@Composable
private fun QuietHero(state: ActivityUiState) {
    val c = MaterialTheme.colorScheme
    val values = Outcome.entries.map { it to state.count(it) }
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
                // Two stops: a surface color between them makes a dark band across the card in dark mode.
                .background(Brush.linearGradient(listOf(c.primaryContainer, c.tertiaryContainer.copy(alpha = 0.6f).compositeOver(c.surface))))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                Modifier.size(132.dp).semantics {
                    contentDescription = "${state.keptQuiet} texts kept quiet: " + values.joinToString(", ") { "${it.second} ${it.first.label.lowercase()}" }
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
                    Text(count(shown), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("kept quiet", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                Text(
                    if (state.checked == 0) "No texts checked yet" else "${count(state.keptQuiet)} of ${count(state.checked)} texts never buzzed your phone",
                    style = MaterialTheme.typography.titleSmall,
                )
                values.forEach { (outcome, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(outcome.color(), CircleShape))
                        Text(outcome.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Text(count(n), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Who decided this period's texts: one bar split four ways, then each in words. */
@Composable
private fun WhoCard(state: ActivityUiState) {
    val total = state.checked
    Card("Who decided", subtitle = "Each text is decided once, as it arrives, by one of these") {
        if (total == 0) {
            Empty()
            return@Card
        }
        val gap = 2.dp
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)), horizontalArrangement = Arrangement.spacedBy(gap)) {
            state.who.filter { it.total > 0 }.forEach { w ->
                Box(Modifier.weight(w.total.toFloat()).fillMaxSize().background(deciderColor(w.who)))
            }
        }
        state.who.forEach { w ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${w.who.name(state.service)}: ${w.total}, ${share(w.total, total)}" }) {
                Box(Modifier.padding(top = 5.dp).size(10.dp).background(deciderColor(w.who), CircleShape))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(w.who.name(state.service), style = MaterialTheme.typography.bodyLarge)
                    Text(whoDetail(w.who, state.service), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(count(w.total), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                Text(share(w.total, total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 44.dp).padding(top = 4.dp))
            }
        }
    }
}

private fun whoDetail(who: Who, service: String) = when (who) {
    Who.SERVICE -> "Asked as the text arrived; the on-device model learns from its sure answers"
    Who.MODEL -> "Sure enough not to ask $service, standing in when it didn't answer, or when nothing may leave the phone"
    Who.RULE -> "Your contacts, people you've texted, codes, your sender rules and filtered words"
    Who.FALLBACK -> "Keywords, when neither the service nor the model could answer; it can silence, never filter"
}

/** Who decided, against what happened: the whole of it in one table, and how often the user changed each one's calls since. */
@Composable
private fun WhatEachDid(state: ActivityUiState) {
    Card("What each one did", subtitle = "Texts by who decided and what happened to them") {
        if (state.checked == 0) {
            Empty()
            return@Card
        }
        val c = MaterialTheme.colorScheme
        Row {
            Spacer(Modifier.weight(2f))
            Outcome.entries.forEach { o -> Text(o.label, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) }
            Text("You changed", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1.1f))
        }
        state.who.filter { it.total > 0 }.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(deciderColor(w.who), CircleShape))
                    Text("  ${w.who.name(state.service)}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Outcome.entries.forEach { o -> Text(count(w.outcomes[o] ?: 0), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) }
                Text(count(w.changedSince), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End, color = if (w.changedSince > 0) c.error else c.onSurface, modifier = Modifier.weight(1.1f))
            }
        }
        Text(
            "“You changed”: texts you've since labeled differently, or whose sender you let through or filtered: a call of theirs you didn't agree with.",
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant,
        )
    }
}

/**
 * Stacked bars, one per day (or week), oldest first, split by who decided or by what happened.
 * Tapping a bar shows its numbers; until then the readout sums the whole period.
 */
@Composable
private fun Timeline(state: ActivityUiState) {
    val buckets = state.buckets
    val c = MaterialTheme.colorScheme
    var byWho by rememberSaveable { mutableStateOf(true) }
    val measurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = c.onSurfaceVariant)
    var selected by remember(buckets) { mutableStateOf<Int?>(null) }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(buckets) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    // Baseline first.
    val series: List<Pair<String, Color>> = if (byWho) Who.entries.map { it.name(state.service) to deciderColor(it) }
        else listOf(Outcome.DELIVERED, Outcome.SILENCED, Outcome.FILTERED).map { it.label to it.color() }
    fun values(b: Bucket): List<Int> = if (byWho) Who.entries.map { b.who[it] ?: 0 }
        else listOf(Outcome.DELIVERED, Outcome.SILENCED, Outcome.FILTERED).map { b.outcomes[it] ?: 0 }
    val max = buckets.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
    val niceMax = niceCeiling(max)
    val formatter = DateTimeFormatter.ofPattern("MMM d")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Who decided", "What happened").forEachIndexed { i, label ->
                SegmentedButton(selected = byWho == (i == 0), onClick = { byWho = i == 0 }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            series.asReversed().forEach { (label, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                    Text("  $label", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = "Texts per ${if (buckets.firstOrNull()?.days == 7) "week" else "day"}, up to $max, by ${if (byWho) "who decided" else "what happened"}" }
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
                val parts = values(b)
                parts.forEachIndexed { p, n ->
                    if (n == 0) return@forEachIndexed
                    val h = plotHeight * n / niceMax * grow.value
                    val isTop = parts.subList(p + 1, parts.size).all { it == 0 }
                    val alpha = if (selected == null || selected == i) 1f else 0.35f
                    // Segments stack from the baseline with a hairline of surface between them; only the top one is rounded.
                    val rect = Rect(x, top - h, x + w, top - if (p > 0) 1.dp.toPx() else 0f)
                    if (isTop) {
                        drawPath(Path().apply { addRoundRect(RoundRect(rect, topLeft = CornerRadius(3.dp.toPx()), topRight = CornerRadius(3.dp.toPx()))) }, series[p].second.copy(alpha = alpha))
                    } else {
                        drawRect(series[p].second.copy(alpha = alpha), rect.topLeft, rect.size)
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
            "$day: " + series.zip(values(b)).asReversed().joinToString(" · ") { (s, n) -> "${count(n)} ${s.first.lowercase()}" }
        }
        Text(readout, style = MaterialTheme.typography.bodyMedium, color = if (b == null) c.onSurfaceVariant else c.onSurface)
    }
}

/** One decision in the log: who, what it was, what happened, and the user's say since. */
@Composable
private fun LogItem(row: LogRow, text: String?, service: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val time = DateTimeFormatter.ofPattern("MMM d, h:mm a").format(Instant.ofEpochMilli(row.at).atZone(ZoneId.systemDefault()))
    Surface(color = c.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClickLabel = "Why Winnow did this", onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(time, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
            text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(deciderColor(row.who), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    buildString {
                        append(row.who.name(row.serviceName ?: service))
                        // Which service the model stood in for isn't kept: by name only when there was one.
                        @Suppress("NAME_SHADOWING") val service = if (service == "Classifier services") "the service" else service
                        when (row.why) {
                            ModelReason.SURE -> append(" (sure enough)")
                            ModelReason.YOUR_LABELS -> append(" (your labels of this sender)")
                            ModelReason.OVER_SERVICE -> append(" (your labels of this sender, over $service)")
                            ModelReason.PROVIDER_FAILED -> append(" ($service didn't answer)")
                            ModelReason.KEPT_ON_PHONE -> append(" (kept on phone)")
                            else -> Unit
                        }
                        if (row.who == Who.RULE) row.rule?.let { append(": ").append(it.lowercase()) }
                        else row.category?.let { append(": ${it.label} ${pct(row.confidence)}") }
                        append(" → ${row.outcome.label.lowercase()}")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            row.mine?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryDot(it, Modifier.size(18.dp))
                    Text("  You labeled it ${it.label}", style = MaterialTheme.typography.labelMedium, color = if (it != row.category) c.error else c.primary)
                }
            }
        }
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
private fun Card(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
    }
}

@Composable
private fun LinkCard(title: String, detail: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_insights), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One labeled bar: the category's dot names it with its label; the bar's one hue is the same for every row. */
@Composable
private fun CategoryBar(category: Category, n: Int, max: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${category.label}: $n" }) {
        CategoryDot(category, Modifier.size(18.dp))
        Text("  ${category.label}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(min = 118.dp))
        Box(Modifier.weight(1f).height(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp))) {
            if (n > 0) Box(Modifier.fillMaxWidth(n / max.toFloat()).height(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
        }
        Text(count(n), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 44.dp))
        Text(if (total == 0) "" else "${(n * 100f / total).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 40.dp))
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
