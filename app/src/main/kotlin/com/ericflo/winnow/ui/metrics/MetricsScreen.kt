package com.ericflo.winnow.ui.metrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.local.BinaryMetrics
import com.ericflo.winnow.classifier.local.CategoryMetrics
import com.ericflo.winnow.classifier.local.ClassifierMetrics
import com.ericflo.winnow.classifier.local.CoveragePoint
import com.ericflo.winnow.classifier.local.CurvePoint
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What's known about Winnow on the user's own texts: only things they actually judged. A
 * conversation they never looked at says nothing either way, so it isn't counted as agreement.
 */
data class Agreement(
    /** Conversations a model or provider classified on this phone. */
    val decisions: Int,
    val corrected: Int,
    val notSpam: Int,
    val filteredByYou: Int,
    /** Messages the user labeled that a model had already judged, and how many of those it had right. */
    val labeledJudged: Int = 0,
    val labeledAgreed: Int = 0,
    /** Every message the user has labeled. */
    val labeled: Int = 0,
    /** Train Winnow guesses the user answered, and how many were right, before it learned from them. */
    val trainReviewed: Int = 0,
    val trainAgreed: Int = 0,
) {
    /** Every call of Winnow's the user has checked, either way. */
    val checked: Int get() = labeledJudged + trainReviewed
    val agreed: Int get() = labeledAgreed + trainAgreed
}

data class MetricsUiState(val metrics: ClassifierMetrics? = null, val agreement: Agreement = Agreement(0, 0, 0, 0))

class MetricsViewModel(container: AppContainer) : ViewModel() {
    val state: StateFlow<MetricsUiState> = combine(
        flow { emit(ClassifierMetrics.bundled) }.flowOn(Dispatchers.Default),
        container.verdictDao.observeAll(),
        flow { emit(container.training.history()) },
    ) { metrics, verdicts, rounds ->
        // Rules (contacts, codes, sender rules) aren't classifications, so they don't count either way.
        // Counted per conversation: a correction applies to every verdict in its thread at once.
        val decided = verdicts.filter { it.sourceKind != "rule" }.groupBy { it.threadId }
        val corrected = decided.mapNotNull { (_, rows) -> rows.firstOrNull { it.userAction != null && it.userAction != it.action } }
        // A label on a text a model judged as it arrived is a real check of that judgment; one per
        // conversation (its newest), since a conversation label covers several of its texts.
        val labeled = verdicts.filter { it.userCategory != null }
        val judged = labeled.filter { it.atArrival && it.sourceKind != "rule" && it.category != null }
            .groupBy { it.threadId }.values.map { rows -> rows.maxBy { it.decidedAt } }
        MetricsUiState(
            metrics = metrics,
            agreement = Agreement(
                decisions = decided.size,
                corrected = corrected.size,
                notSpam = corrected.count { it.userAction == Action.ALLOW.name },
                filteredByYou = corrected.count { it.userAction == Action.FILTER.name },
                labeledJudged = judged.size,
                labeledAgreed = judged.count { it.category == it.userCategory },
                labeled = labeled.size,
                trainReviewed = rounds.sumOf { it.reviewed },
                trainAgreed = rounds.sumOf { it.agreed },
            ),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetricsUiState())
}

/**
 * How accurate Winnow is. First, and only from the user's own texts, how its calls compare with
 * the labels and corrections they gave. Then, kept apart and folded away, how the built-in
 * model scored on Winnow's own test texts, labeled as exactly that: none of those numbers are
 * about the user's messages, and nothing on this screen may suggest they are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricsScreen(viewModel: MetricsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How accurate is Winnow?") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val m = state.metrics ?: return@Scaffold
        // One threshold, shared by both curves and the explorer, so they move together.
        var threshold by rememberSaveable { mutableStateOf(0.5) }
        var showTest by rememberSaveable { mutableStateOf(false) }
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {
            item("you") { AgreementCard(state.agreement) }
            item("test-header") {
                Section("How the built-in model was tested") {
                    Text(
                        "Before Winnow ever saw your texts, its built-in model was tested on ${count(m.examples)} example texts written for that. " +
                            "None of them are yours, and none of the numbers below are about your messages.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { showTest = !showTest }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (showTest) "Hide the test results" else "Show the test results")
                    }
                }
            }
            if (!showTest) {
                item("footer-short") { Spacer(Modifier.height(32.dp)) }
                return@LazyColumn
            }
            item("hero") { Hero(m) }
            item("roc") { RocCard(m.unwanted, threshold) { threshold = it } }
            item("explorer") { ThresholdExplorer(m.unwanted, threshold) { threshold = it } }
            item("pr") { PrecisionRecallCard(m.unwanted, threshold) { threshold = it } }
            item("calibration") {
                Section("Does “90% sure” mean right 90% of the time?") {
                    CalibrationChart(m.calibration)
                    Legend(listOf(MaterialTheme.colorScheme.primary to "Right this often", MaterialTheme.colorScheme.tertiary to "How sure it was"))
                    MetricRow(listOf("Calibration error" to f3(m.ece), "Log loss" to f3(m.logLoss), "Brier score" to f3(m.brier)))
                    Note("Bars should meet their line: then confidence can be taken at face value. The numbers above bars count texts in each range; faded bars have few.")
                }
            }
            item("categories") { PerCategory(m.perCategory) }
            item("confusion") { ConfusionMatrix(m) }
            item("coverage") { CoverageCard(m.coverage) }
            m.evaluation?.let { e ->
                item("blind") {
                    Section("Blind test") {
                        Text(
                            "${count(e.examples)} more test texts, written separately and never used for training, scored by the model that ships. " +
                                "They were written for testing too, so expect less on real messages.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MetricRow(listOf("Accuracy" to pct(e.accuracy), "Macro F1" to f2(e.macroF1), "Cohen's κ" to f2(e.kappa)))
                        MetricRow(listOf("MCC" to f2(e.mcc), "ROC AUC" to f3(e.auc), "" to ""))
                    }
                }
            }
            item("footer") {
                Text(
                    "${m.method} Every test text was written to show its category clearly, so real traffic will score lower. " +
                        "These numbers are for Winnow's own model (${m.model}) on those test texts; how it does on yours is at the top.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun Hero(m: ClassifierMetrics) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(c.primaryContainer, c.surfaceContainer, c.tertiaryContainer.copy(alpha = 0.6f))))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("On the test texts: unwanted vs. wanted", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ScoreGauge(m.unwanted.auc, "ROC AUC", size = 140.dp)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    BigNumber(pct(m.accuracy), "of the ${count(m.examples)} test texts put in the right one of 7 categories")
                    BigNumber(pct(m.unwanted.operatingPoint.falsePositiveRate), "of the wanted test texts would be filtered")
                    BigNumber(pct(m.unwanted.unwantedQuieted), "of the unwanted test texts would arrive without a sound")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("Macro F1", f2(m.macroF1), Modifier.weight(1f))
                StatChip("Cohen's κ", f2(m.kappa), Modifier.weight(1f))
                StatChip("MCC", f2(m.mcc), Modifier.weight(1f))
                StatChip("Avg. precision", f2(m.unwanted.averagePrecision), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BigNumber(value: String, caption: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RocCard(b: BinaryMetrics, threshold: Double, onThreshold: (Double) -> Unit) {
    // A good classifier lives in the top-left corner, so that's where the chart starts.
    var zoom by rememberSaveable { mutableStateOf(true) }
    val selected = b.roc.nearestTo(threshold)
    Section("ROC curve") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AUC ${f3(b.auc)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow {
                listOf("Full", "Zoom").forEachIndexed { i, label ->
                    SegmentedButton(selected = zoom == (i == 1), onClick = { zoom = i == 1 }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
        }
        CurveChart(
            points = b.roc,
            xRange = if (zoom) 0.0..0.2 else 0.0..1.0,
            yRange = if (zoom) 0.6..1.0 else 0.0..1.0,
            baseline = Baseline.Diagonal,
            marker = Marker(b.operatingPoint.falsePositiveRate, b.operatingPoint.recall, "Winnow's rule"),
            selected = selected,
            onSelect = { onThreshold(it.t) },
            xLabel = "Wanted texts flagged (false positive rate) →",
            yLabel = "Unwanted caught (true positive rate)",
            description = "ROC curve for unwanted versus wanted texts, area under the curve ${f3(b.auc)}",
        )
        Readout("Flag at ≥ ${pct(selected.t)}: catches ${pct(selected.y)} of unwanted texts, flags ${pct(selected.x)} of wanted ones.")
        Note("Drag across the chart to try other thresholds. The dashed line is a coin flip; the closer the curve hugs the top-left, the better. ${count(b.positives)} unwanted and ${count(b.negatives)} wanted texts.")
    }
}

@Composable
private fun PrecisionRecallCard(b: BinaryMetrics, threshold: Double, onThreshold: (Double) -> Unit) {
    val selected = b.pr.nearestTo(threshold)
    val prevalence = b.positives.toDouble() / (b.positives + b.negatives)
    Section("Precision and recall") {
        Text("Average precision ${f3(b.averagePrecision)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        CurveChart(
            points = b.pr,
            xRange = 0.0..1.0,
            yRange = 0.0..1.0,
            baseline = Baseline.Horizontal(prevalence),
            marker = Marker(b.operatingPoint.recall, b.operatingPoint.precision, "Winnow's rule"),
            selected = selected,
            onSelect = { onThreshold(it.t) },
            xLabel = "Recall: unwanted caught →",
            yLabel = "Precision: flagged texts that deserved it",
            description = "Precision–recall curve, average precision ${f3(b.averagePrecision)}",
        )
        Readout("Flag at ≥ ${pct(selected.t)}: ${pct(selected.y)} of flagged texts are really unwanted, catching ${pct(selected.x)} of them.")
        Note("The dashed line is how often texts are unwanted at all (${pct(prevalence)}): what guessing would score.")
    }
}

/** A slider over the threshold that redraws the 2×2 outcome grid and every score at once. */
@Composable
private fun ThresholdExplorer(b: BinaryMetrics, threshold: Double, onThreshold: (Double) -> Unit) {
    val point = b.roc.nearestTo(threshold)
    val tp = (point.y * b.positives).roundToInt()
    val fp = (point.x * b.negatives).roundToInt()
    val fn = b.positives - tp
    val tn = b.negatives - fp
    val n = (b.positives + b.negatives).toDouble()
    val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
    val recall = tp.toDouble() / b.positives.coerceAtLeast(1)
    val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
    val mccDen = sqrt((tp + fp).toDouble() * (tp + fn) * (tn + fp) * (tn + fn))
    val mcc = if (mccDen == 0.0) 0.0 else (tp.toDouble() * tn - fp.toDouble() * fn) / mccDen
    val po = (tp + tn) / n
    val pe = ((tp + fp) / n) * ((tp + fn) / n) + ((fn + tn) / n) * ((fp + tn) / n)
    val kappa = if (pe == 1.0) 0.0 else (po - pe) / (1 - pe)
    Section("Try a threshold") {
        Text("Flag a text when it's at least ${pct(point.t)} likely to be unwanted", style = MaterialTheme.typography.bodyLarge)
        Slider(value = threshold.toFloat(), onValueChange = { onThreshold(it.toDouble()) }, valueRange = 0.01f..0.99f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Outcome("Caught", tp, "unwanted, flagged", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.weight(1f))
            Outcome("Wrongly flagged", fp, "wanted, flagged", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Outcome("Missed", fn, "unwanted, let through", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Modifier.weight(1f))
            Outcome("Let through", tn, "wanted, delivered", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        MetricRow(listOf("Precision" to pct(precision), "Recall" to pct(recall), "F1" to f2(f1)))
        MetricRow(listOf("MCC" to f2(mcc), "Cohen's κ" to f2(kappa), "False positives" to pct(point.x)))
        val rule = b.operatingPoint
        Note(
            "Winnow's own rule is stricter than any single threshold: it filters only when the top category is an unwanted one and it's at least 85% sure, " +
                "and never filters a scam or phishing text with no hook (no link off the company's real site, money, number to call, or payment or code talk): " +
                "a bare “hi, is this David?” reads exactly like a real person on a new number, and “your password was changed” has nothing to phish with. " +
                "On the test texts, that filters ${pct(rule.recall)} of the unwanted ones with ${pct(rule.precision)} precision and ${pct(rule.falsePositiveRate)} of the wanted ones (F1 ${f2(rule.f1)}, MCC ${f2(rule.mcc)}, κ ${f2(rule.kappa)}). " +
                "The rest are silenced rather than filtered: ${pct(b.unwantedQuieted)} of the unwanted test texts would arrive without a sound.",
        )
    }
}

@Composable
private fun Outcome(title: String, value: Int, caption: String, container: Color, content: Color, modifier: Modifier) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(count(value), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(caption, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PerCategory(categories: List<CategoryMetrics>) {
    val c = MaterialTheme.colorScheme
    Section("Every category") {
        Legend(listOf(c.primary to "Precision", c.tertiary to "Recall"))
        categories.forEach { m ->
            val label = Category.fromKey(m.key)?.label ?: m.key
            Column(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = "$label: precision ${pct(m.precision)}, recall ${pct(m.recall)}, F1 ${f2(m.f1)}, ROC AUC ${f3(m.auc)}, ${m.support} texts"
                },
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("F1 ${f2(m.f1)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("  AUC ${f3(m.auc)} · ${m.support}", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Bar(m.precision, c.primary, pct(m.precision))
                Spacer(Modifier.height(4.dp))
                Bar(m.recall, c.tertiary, pct(m.recall))
            }
        }
    }
}

@Composable
private fun Bar(value: Double, color: Color, label: String, labelWidth: Dp = 48.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(value.toFloat().coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(labelWidth))
    }
}

/** Rows are what a text really was, columns what the model said; shading is each row's share. */
@Composable
private fun ConfusionMatrix(m: ClassifierMetrics) {
    val c = MaterialTheme.colorScheme
    val short = mapOf("personal" to "Pers", "transactional" to "Txn", "marketing" to "Mktg", "political" to "Pol", "phishing" to "Phish", "scam" to "Scam", "spam" to "Spam")
    Section("Confusion matrix") {
        Text("Rows: what each text really was. Columns: what the model said.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        Row {
            Spacer(Modifier.width(52.dp))
            m.classes.forEach { key ->
                Text(short[key] ?: key, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
        m.confusion.forEachIndexed { r, row ->
            val total = row.sum().coerceAtLeast(1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(short[m.classes[r]] ?: m.classes[r], style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, modifier = Modifier.width(52.dp))
                row.forEachIndexed { col, n ->
                    val share = n / total.toFloat()
                    val fill = if (r == col) c.primary else c.error
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(1.5.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (n == 0) c.surfaceContainerHighest.copy(alpha = 0.5f) else fill.copy(alpha = 0.18f + 0.82f * share)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (n > 0) {
                            Text(
                                "$n",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (r == col) FontWeight.Bold else FontWeight.Normal,
                                color = if (share > 0.5f) (if (r == col) c.onPrimary else c.onError) else c.onSurface,
                            )
                        }
                    }
                }
            }
        }
        Legend(listOf(c.primary to "Right", c.error to "Mixed up"))
    }
}

@Composable
private fun CoverageCard(points: List<CoveragePoint>) {
    val c = MaterialTheme.colorScheme
    Section("When it's sure") {
        Text("How many of the test texts reach each level of confidence, and how often those are right.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        points.forEach { p ->
            val role = when (p.threshold) {
                0.85 -> "Where Winnow filters on its own"
                0.95 -> "Where it decides without a provider"
                else -> null
            }
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("≥ ${pct(p.threshold)} sure", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${pct(p.accuracy)} right", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                if (role != null) Text(role, style = MaterialTheme.typography.labelSmall, color = c.tertiary)
                Spacer(Modifier.height(4.dp))
                Bar(p.coverage, c.primary, "${pct(p.coverage)} of texts", labelWidth = 96.dp)
            }
        }
    }
}

@Composable
private fun AgreementCard(a: Agreement) {
    Section("On your texts") {
        if (a.checked >= MIN_CHECKED_FOR_PERCENT) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ScoreGauge(a.agreed.toDouble() / a.checked, "agreed", size = 120.dp, format = { "${(it * 100).roundToInt()}%" })
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    Text("Right on ${count(a.agreed)} of ${count(a.checked)}", style = MaterialTheme.typography.titleSmall)
                    Text("of its calls that you checked", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (a.checked == 0) {
            Text(
                "Nothing measured on your texts yet. Label some (long-press a conversation, or Train Winnow from the menu) and this shows how often Winnow agrees with you.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "Right on ${count(a.agreed)} of ${count(a.checked)} of its calls that you checked. Too few to make a percentage of yet: check $MIN_CHECKED_FOR_PERCENT and one shows here.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (a.checked > 0) {
            val parts = buildList {
                if (a.trainReviewed > 0) add("Train Winnow: right on ${count(a.trainAgreed)} of ${count(a.trainReviewed)} guesses, each made before it saw your answer")
                if (a.labeledJudged > 0) add("When texts arrived: right on ${count(a.labeledAgreed)} of ${count(a.labeledJudged)} that you labeled later")
            }
            parts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Note("Only what you checked yourself counts. The more you label, the more this tells you.")
        }
        val counts = buildList {
            if (a.labeled > 0) add("${count(a.labeled)} texts labeled by you")
            if (a.decisions > 0) add("${count(a.decisions)} conversations classified on this phone")
            if (a.notSpam > 0) add("${count(a.notSpam)} you marked “Not spam”")
            if (a.filteredByYou > 0) add("${count(a.filteredByYou)} you filtered yourself")
        }
        counts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Fewer checks than this, and a percentage would claim more than they can show. */
private const val MIN_CHECKED_FOR_PERCENT = 10

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun MetricRow(items: List<Pair<String, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                if (label.isNotEmpty()) {
                    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Text("  $label", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Readout(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The curve point whose threshold is closest to [threshold]. */
private fun List<CurvePoint>.nearestTo(threshold: Double): CurvePoint = minBy { abs(it.t - threshold) }

private fun pct(x: Double) = String.format(Locale.US, if (x >= 0.995 || x < 0.1) "%.1f%%" else "%.0f%%", x * 100)
private fun f2(x: Double) = String.format(Locale.US, "%.2f", x)
private fun f3(x: Double) = String.format(Locale.US, "%.3f", x)
private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)
