package com.ericflo.winnow.ui.model

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.classifier.local.SenderMemory
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classify.LabDiagnosis
import com.ericflo.winnow.data.db.EvalEntity
import com.ericflo.winnow.ui.components.CategoryDot
import com.ericflo.winnow.ui.insight.InsightCard
import com.ericflo.winnow.ui.insight.Note
import com.ericflo.winnow.ui.insight.count
import com.ericflo.winnow.ui.insight.pct
import kotlinx.coroutines.launch

/**
 * The Lab's "What would help it follow your labels": from the best-scoring model's latest scoring
 * (a Lab model's, or Winnow's own), where it falls short of the user's labels and what would help
 * it, with alike texts the user labeled differently to look at together (see LabDiagnosis). The
 * user's labels are the answer key: everything here is about the model.
 */
@Composable
fun FollowYourLabelsCard(viewModel: ModelViewModel, onOpenThread: (Long, List<String>) -> Unit) {
    val entries by viewModel.lab.entries.collectAsStateWithLifecycle()
    val history by viewModel.evals.history.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val all = history ?: return
    // The best of the latest scorings on the user's labels: each Lab model's, and Winnow's own.
    val lab = entries.mapNotNull { e -> e.evalId?.let { id -> all.firstOrNull { it.id == id } }?.let { it to e } }
    val own = all.firstOrNull { it.model == "now" && it.dataset == EvalEntity.DATASET_MINE && it.examples > 0 }
    val (eval, entry) = (lab + listOfNotNull(own?.let { it to null })).maxByOrNull { it.first.accuracy } ?: return
    InsightCard("What would help it follow your labels", subtitle = "Based on ${eval.label}: the best of the latest scorings on your labels") {
        DiagnosisFor(viewModel, eval, entry?.fitAccuracy, entry?.recipe?.serviceWeight ?: overview?.weight, onOpenThread, senderMemory = entry?.recipe?.senderMemory ?: overview?.senderMemory)
    }
}

/** [eval]'s diagnosis, loaded, and loaded again after the user answers a review. */
@Composable
fun DiagnosisFor(
    viewModel: ModelViewModel,
    eval: EvalEntity,
    fitAccuracy: Double?,
    serviceWeight: Double?,
    onOpenThread: (Long, List<String>) -> Unit,
    showSuggestions: Boolean = true,
    senderMemory: Double? = null,
) {
    var version by remember { mutableIntStateOf(0) }
    var diagnosis by remember(eval.id) { mutableStateOf<Evaluations.Diagnosis?>(null) }
    LaunchedEffect(eval.id, fitAccuracy, senderMemory, version) { diagnosis = viewModel.evals.diagnosis(eval, fitAccuracy, serviceWeight, senderMemory) }
    val d = diagnosis
    if (d == null) CircularProgressIndicator() else DiagnosisSections(viewModel, d, onOpenThread, onChanged = { version++ }, showSuggestions = showSuggestions)
}

/** What one scoring says: how it follows the user's labels, what would help, where it goes wrong, and alike texts to review. */
@Composable
fun DiagnosisSections(
    viewModel: ModelViewModel,
    d: Evaluations.Diagnosis,
    onOpenThread: (Long, List<String>) -> Unit,
    onChanged: () -> Unit,
    showSuggestions: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "It follows your label on ${pct(d.accuracy)} of ${count(d.eval.examples)} texts it hadn't seen" +
                (d.fitAccuracy?.let { "; on the ones it learns from, ${pct(it)}" } ?: "") + ".",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (showSuggestions) d.suggestions.forEach { s ->
            Column {
                Text(s.title, style = MaterialTheme.typography.titleSmall)
                Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (d.confusions.isNotEmpty()) {
            Text("Where it goes wrong", style = MaterialTheme.typography.labelLarge)
            d.confusions.take(CONFUSIONS_SHOWN).forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryDot(c.label)
                    Spacer(Modifier.width(6.dp))
                    Text("Your ${c.label.label.lowercase()}, it called ${c.predicted.label.lowercase()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${count(c.count)} of ${count(c.ofLabel)}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("By category", style = MaterialTheme.typography.labelLarge)
        d.categories.filter { it.labels > 0 }.forEach { c ->
            val right = Math.round(c.recall * c.labels).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryDot(c.category)
                Spacer(Modifier.width(6.dp))
                Text(c.category.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                // Under ten, counts: a percentage of a handful says more than it knows.
                Text(
                    "${count(c.labels)} labels · follows " + if (c.labels < 10) "${count(right)} of ${count(c.labels)}" else pct(c.recall),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (d.mistakes.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Text("Its surest mistakes: ${count(d.mistakes.size)}", style = MaterialTheme.typography.labelLarge)
            Note("Trained without these, it was at least ${pct(LabDiagnosis.SURE)} sure of another category, and wrong: where what it learned misleads it most.")
            if (!open) TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) { Text("Show them") }
            else {
                d.mistakes.take(SHOWN).forEach { ShownRow(it, onOpenThread) }
                if (d.mistakes.size > SHOWN) Note("And ${count(d.mistakes.size - SHOWN)} more.")
            }
        }
        if (d.toReview.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Text("Similar texts, different labels from you: ${count(d.toReview.size)}", style = MaterialTheme.typography.labelLarge)
            Note("These read nearly alike (numbers and links aside) but got different labels from you. Pick one label for both, or keep both if they're different to you; either way, it's your call.")
            if (!open) TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) { Text("Look at them") }
            else {
                d.toReview.take(SHOWN).forEach { r -> ReviewPair(viewModel, r, onOpenThread, onChanged) }
                if (d.toReview.size > SHOWN) Note("And ${count(d.toReview.size - SHOWN)} more: they show here as you answer these.")
            }
        }
        if (d.keptApart > 0) Note("You kept ${count(d.keptApart)} ${if (d.keptApart == 1) "pair" else "pairs"} of alike texts apart: the words don't separate them, so the model needs signals it doesn't have yet to follow you there.")
    }
}

/** Two alike texts with different labels: keep both, or use one's label for both. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewPair(viewModel: ModelViewModel, r: Evaluations.Review, onOpenThread: (Long, List<String>) -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember(r.id) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ShownRow(r.a, onOpenThread)
            ShownRow(r.b, onOpenThread)
            // One sender's two texts: the answer also says whether the sender sends one kind or both.
            if (r.oneSender) Note(
                "Both are from the same sender. Keeping both says they send both kinds, so the words decide between them; one label for both keeps your labels of them all one way, " +
                    "and ${SenderMemory.DECISIVE_AT_LEAST} or more all one way decide their next texts (unless you text with them).",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy, onClick = { busy = true; scope.launch { viewModel.evals.keepBoth(r); onChanged() } }) { Text("Keep both") }
                fun both(category: Category, other: Evaluations.Shown) {
                    busy = true
                    scope.launch { viewModel.evals.relabel(other, category); onChanged() }
                }
                TextButton(enabled = !busy, onClick = { both(r.a.label, r.b) }) { Text("Both ${r.a.label.label.lowercase()}") }
                TextButton(enabled = !busy, onClick = { both(r.b.label, r.a) }) { Text("Both ${r.b.label.label.lowercase()}") }
            }
        }
    }
}

@Composable
private fun ShownRow(s: Evaluations.Shown, onOpenThread: (Long, List<String>) -> Unit) {
    val canOpen = s.threadId != null && s.address != null
    Column(Modifier.fillMaxWidth().clickable(enabled = canOpen) { onOpenThread(s.threadId!!, listOf(s.address!!)) }.padding(vertical = 4.dp)) {
        Text(s.text ?: "No longer on your phone", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryDot(s.label)
            Spacer(Modifier.width(4.dp))
            Text(
                "You: ${s.label.label}" + (s.predicted?.let { p -> " · it said ${p.label}" + (s.confidence?.let { " (${pct(it)} sure)" } ?: "") } ?: ""),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val CONFUSIONS_SHOWN = 6
private const val SHOWN = 25
