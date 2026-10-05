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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classify.Learner
import com.ericflo.winnow.data.db.EvalEntity
import com.ericflo.winnow.ui.components.CategoryDot
import com.ericflo.winnow.ui.insight.InsightCard
import com.ericflo.winnow.ui.insight.Note
import com.ericflo.winnow.ui.insight.RateBar
import com.ericflo.winnow.ui.insight.count
import com.ericflo.winnow.ui.insight.f2
import com.ericflo.winnow.ui.insight.pct
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * The Evaluate tab: score models on the user's own labels (the model now, as it ships, fitted on
 * their labels alone, with another weight for the service's labels, any kept fit, the service's
 * recorded answers), see where each went wrong, replay how the model learned, and follow its
 * score over time. Every result is kept.
 */
internal fun LazyListScope.evaluate(viewModel: ModelViewModel, onOpenThread: (Long, List<String>) -> Unit) {
    item("pick") { PickCard(viewModel) }
    item("latest") { LatestCard(viewModel, onOpenThread) }
    item("curve") { CurveCard(viewModel) }
    item("over-time") { OverTimeCard(viewModel) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickCard(viewModel: ModelViewModel) {
    val evals = viewModel.evals
    val kept by evals.kept.collectAsStateWithLifecycle()
    val progress by evals.progress.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val service = overview?.provider ?: "the service"
    var chosen by rememberSaveable { mutableStateOf(setOf(Pick.Now.id, Pick.Shipped.id, Pick.YoursOnly.id, Pick.Service.id)) }
    val current = overview?.weight ?: Learner.PROVIDER_WEIGHT
    var weight by rememberSaveable { mutableFloatStateOf(current.toFloat()) }
    val scope = rememberCoroutineScope()
    InsightCard("Score them on your labels", subtitle = "Pick what to compare. Each is scored only in a way that's fair to it, and every result is kept.") {
        fun toggle(id: String) { chosen = if (id in chosen) chosen - id else chosen + id }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = Pick.Now.id in chosen, onClick = { toggle(Pick.Now.id) }, label = { Text("The model now") })
            FilterChip(selected = Pick.Shipped.id in chosen, onClick = { toggle(Pick.Shipped.id) }, label = { Text("As it ships") })
            FilterChip(selected = Pick.YoursOnly.id in chosen, onClick = { toggle(Pick.YoursOnly.id) }, label = { Text("Your labels only") })
            FilterChip(selected = Pick.Service.id in chosen, onClick = { toggle(Pick.Service.id) }, label = { Text("$service's answers") })
            FilterChip(selected = "weight" in chosen, onClick = { toggle("weight") }, label = { Text("$service's labels at ${pct(weight.toDouble())}") })
            kept.forEach { f ->
                val id = "fit:${f.fit}"
                FilterChip(
                    selected = id in chosen,
                    onClick = { toggle(id) },
                    label = { Text(f.name ?: "Fit ${f.fit} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(f.fittedAt))}") },
                )
            }
        }
        if ("weight" in chosen) {
            Text("Rebuild with $service's labels counting ${pct(weight.toDouble())} of one of yours (the model uses ${pct(current)} now)", style = MaterialTheme.typography.bodyMedium)
            Slider(value = weight, onValueChange = { weight = (it * 20).toInt() / 20f }, valueRange = 0f..1f, steps = 19)
        }
        Note(
            "The model now, your labels only and a rebuilt weight learn from your labels, so they're cross-validated: each conversation's labels scored by a fit without them. " +
                "As it ships never saw them. A kept fit is scored on the labels you made after it. $service's are its recorded answers on texts you've labeled, at no cost.",
        )
        if (current != Learner.PROVIDER_WEIGHT) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("The model uses $service's labels at ${pct(current)}, which you chose.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.useWeight(Learner.PROVIDER_WEIGHT) }) { Text("Back to ${pct(Learner.PROVIDER_WEIGHT)}") }
            }
        }
        val error by evals.error.collectAsStateWithLifecycle()
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        val running = progress
        if (running != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text(running, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = evals::cancel) { Text("Stop") }
            }
        } else {
            Button(
                onClick = {
                    val picks = buildList {
                        if (Pick.Now.id in chosen) add(Pick.Now)
                        if (Pick.Shipped.id in chosen) add(Pick.Shipped)
                        if (Pick.YoursOnly.id in chosen) add(Pick.YoursOnly)
                        if ("weight" in chosen) add(Pick.Weight(weight.toDouble()))
                        if (Pick.Service.id in chosen) add(Pick.Service)
                        kept.filter { "fit:${it.fit}" in chosen }.forEach { add(Pick.Kept(it)) }
                    }
                    scope.launch { evals.run(picks, service) }
                },
                enabled = chosen.isNotEmpty(),
            ) { Text("Score them") }
        }
    }
}

@Composable
private fun LatestCard(viewModel: ModelViewModel, onOpenThread: (Long, List<String>) -> Unit) {
    val history by viewModel.evals.history.collectAsStateWithLifecycle()
    val all = history ?: return
    if (all.isEmpty()) return
    val at = all.first().at
    // Kept in the order they were scored.
    val latest = all.filter { it.at == at }.sortedBy { it.id }
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    InsightCard("Results", subtitle = "Scored ${format.format(Date(at))}, on your labels") {
        latest.forEach { e -> ResultRow(viewModel, e, onOpenThread) }
        Note("Accuracy: put in the category you gave. Macro F1: the average over categories, so a rare one counts as much as a common one. κ: agreement beyond chance. Wanted filtered: your non-spam texts Winnow's rule would have filtered.")
    }
}

@Composable
private fun ResultRow(viewModel: ModelViewModel, e: EvalEntity, onOpenThread: (Long, List<String>) -> Unit) {
    var open by remember(e.id) { mutableStateOf(false) }
    val fair = Evaluations.fair(e.method)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(e.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${Evaluations.methodLabel(e.method)} · ${count(e.examples)} texts" + if (!fair) " · not a fair test" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (fair) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Text(if (e.examples == 0) "—" else pct(e.accuracy), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            if (e.examples > 0) {
                Text(
                    "macro F1 ${f2(e.macroF1)} · κ ${f2(e.kappa)}" + (e.falsePositiveRate?.let { " · wanted filtered ${pct(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // A rebuild that scored well can be made the model, there and then.
            weightOf(e.model)?.let { w ->
                val o by viewModel.overview.collectAsStateWithLifecycle()
                if (o != null && o!!.weight != w && e.examples > 0) {
                    TextButton(onClick = { viewModel.useWeight(w) }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (w == 0.0) "Use it: fit the model on your labels only" else "Use it: fit the model with ${pct(w)}")
                    }
                } else if (o != null && o!!.weight == w) {
                    Text("This is how the model is fitted now.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (open) Detail(viewModel, e, onOpenThread)
            else if (e.examples > 0) Text("Tap for each category and where it went wrong", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Detail(viewModel: ModelViewModel, e: EvalEntity, onOpenThread: (Long, List<String>) -> Unit) {
    val metrics = remember(e.id) { viewModel.evals.metricsOf(e) }
    e.note?.let { Note(it) }
    metrics?.perCategory?.filter { it.support > 0 }?.forEach { c ->
        val category = Category.fromKey(c.key) ?: return@forEach
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryDot(category)
            Spacer(Modifier.width(8.dp))
            Text(category.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("F1 ${f2(c.f1)} · ${count(c.support)} texts", style = MaterialTheme.typography.labelMedium)
        }
    }
    var misses by remember(e.id) { mutableStateOf<List<Miss>?>(null) }
    LaunchedEffect(e.id) { misses = viewModel.evals.misses(e) }
    val list = misses
    if (list == null) {
        CircularProgressIndicator()
        return
    }
    Text(if (list.isEmpty()) "It got every one right." else "Where it went wrong: ${count(list.size)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    list.take(MISSES_SHOWN).forEach { m ->
        Column(
            Modifier.fillMaxWidth().clickable(enabled = m.threadId != null && m.address != null) { onOpenThread(m.threadId!!, listOf(m.address!!)) }.padding(vertical = 4.dp),
        ) {
            Text(m.text ?: "No longer on your phone", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("You: ${m.label.label} · it said ${m.predicted.label} ${pct(m.confidence)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (list.size > MISSES_SHOWN) Note("And ${count(list.size - MISSES_SHOWN)} more.")
}

@Composable
private fun CurveCard(viewModel: ModelViewModel) {
    val curve by viewModel.evals.curve.collectAsStateWithLifecycle()
    val busy by viewModel.evals.curving.collectAsStateWithLifecycle()
    InsightCard("How it learned", subtitle = "Your labels replayed in the order you gave them: after each stretch, the model fitted on everything taught until then, scored on the labels that came next") {
        val points = curve
        when {
            busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text("Replaying…", style = MaterialTheme.typography.bodyMedium)
            }
            points == null -> OutlinedButton(onClick = viewModel.evals::replay) { Text("Replay how it learned") }
            points.isEmpty() -> Note("Needs at least 16 labels to replay.")
            else -> {
                points.forEach { p ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("After ${count(p.taught)} of your labels, on the next ${count(p.tested)}", style = MaterialTheme.typography.titleSmall)
                        RateBar("Your model then", p.right, p.tested)
                        RateBar("As it ships", p.shippedRight, p.tested, color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Note("Each score is on labels the model hadn't seen yet, so the gap between the two bars is what your teaching had won by then. Rounds bring the texts it's least sure of, so stretches can differ in how hard they are.")
                TextButton(onClick = viewModel.evals::replay, contentPadding = PaddingValues(0.dp)) { Text("Replay again") }
            }
        }
    }
}

@Composable
private fun OverTimeCard(viewModel: ModelViewModel) {
    val history by viewModel.evals.history.collectAsStateWithLifecycle()
    val all = history ?: return
    val nows = all.filter { it.model == "now" && it.examples > 0 }.sortedBy { it.at }
    val runs = all.groupBy { it.at }.toSortedMap(compareByDescending { it })
    if (runs.size <= 1 && nows.size <= 1) return
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val scope = rememberCoroutineScope()
    InsightCard("Over time", subtitle = "Every time you've scored them") {
        if (nows.size > 1) {
            Text("The model now, each time it was scored", style = MaterialTheme.typography.labelLarge)
            nows.takeLast(12).forEach { e ->
                RateBar(format.format(Date(e.at)), (e.accuracy * e.examples).toInt(), e.examples, detail = "macro F1 ${f2(e.macroF1)}")
            }
        }
        Text("Earlier scorings", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        runs.entries.drop(1).take(20).forEach { (at, evals) ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(format.format(Date(at)), style = MaterialTheme.typography.titleSmall)
                evals.sortedBy { it.id }.forEach { e ->
                    Row {
                        Text(e.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text("${if (e.examples == 0) "—" else pct(e.accuracy)} · ${count(e.examples)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { scope.launch { evals.forEach { viewModel.evals.delete(it) } } }, contentPadding = PaddingValues(0.dp)) { Text("Delete this scoring") }
            }
        }
    }
}

private const val MISSES_SHOWN = 30

/** The weight for the service's labels a scored rebuild used, if it was one: "your labels only" is 0. */
internal fun weightOf(model: String): Double? = when {
    model == com.ericflo.winnow.classify.EvalSubject.YoursOnly.key -> 0.0
    model.startsWith("variant:service-weight:") -> model.removePrefix("variant:service-weight:").toDoubleOrNull()
    else -> null
}
