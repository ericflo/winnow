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
    item("experiment") { ExperimentCard(viewModel, onOpenThread) }
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

/** What one text costs to ask Jev about (see TrainViewModel.JEV_USD_PER_TEXT), twice here. */
private const val JEV_USD_PER_TEXT = 0.00013

/**
 * The test of whether the user's labels change the service's answers: ask it about their labeled
 * texts plainly and with their labels as examples, and compare, against their labels.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExperimentCard(viewModel: ModelViewModel, onOpenThread: (Long, List<String>) -> Unit) {
    val status by viewModel.experiment.status.collectAsStateWithLifecycle()
    val plan by viewModel.experimentPlan.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val service = overview?.provider ?: "the service"
    LaunchedEffect(status is com.ericflo.winnow.classify.ExperimentStatus.Idle) { viewModel.planExperiment() }
    var size by rememberSaveable { mutableStateOf(20) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    InsightCard("Do your labels change $service's answers?", subtitle = "Measured, not assumed: ask it about your own labeled texts twice, and compare") {
        Text(
            "Each text is asked once with the plain question $service gets as texts arrive, and once with your labels as examples, the way a backlog run asks, " +
                "never with an example from its own conversation. Both are scored against your label.",
            style = MaterialTheme.typography.bodyMedium,
        )
        when (val st = status) {
            is com.ericflo.winnow.classify.ExperimentStatus.Running -> {
                androidx.compose.material3.LinearProgressIndicator(progress = { if (st.total == 0) 0f else st.done / st.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("${count(st.done)} of ${count(st.total)} texts asked · ${com.ericflo.winnow.ui.insight.money(st.costUsd)} so far", style = MaterialTheme.typography.bodyMedium)
                st.waiting?.let { Note(it) }
                TextButton(onClick = viewModel.experiment::stop, contentPadding = PaddingValues(0.dp)) { Text("Stop") }
            }
            is com.ericflo.winnow.classify.ExperimentStatus.Finished -> ExperimentResult(viewModel, st, service, onOpenThread)
            com.ericflo.winnow.classify.ExperimentStatus.Idle -> {
                val p = plan
                when {
                    p == null -> CircularProgressIndicator()
                    p.unavailable != null -> Text(p.unavailable, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    p.available == 0 -> Note("None of your labeled texts could be sent ($service only ever sees texts the privacy settings let it), so there's nothing to ask about yet.")
                    p.examples == 0 -> Note("You have no labeled texts that could go as examples yet, so there's nothing to compare.")
                    else -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(20, 50, 100).filter { it <= p.available || it == 20 }.forEach { n ->
                                FilterChip(selected = size == n, onClick = { size = n }, label = { Text("${count(minOf(n, p.available))} texts") })
                            }
                        }
                        val n = minOf(size, p.available)
                        Note(
                            "${count(n)} of your ${count(p.available)} labeled texts that could be sent, a category at a time; ${count(p.examples)} labeled texts as the examples. " +
                                (if (service == "Jev") "At Jev's price, about ${com.ericflo.winnow.ui.insight.money(2 * n * JEV_USD_PER_TEXT)} in all." else "$service bills each answer as usual."),
                        )
                        Button(onClick = { confirming = true }) { Text("Ask $service twice about ${count(n)} texts") }
                        if (confirming) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { confirming = false },
                                title = { Text("Send ${count(n)} of your labeled texts to $service, twice?") },
                                text = {
                                    Text(
                                        "Only texts your privacy settings already let $service see, masked as they say, with zero data retention as for a backlog run. " +
                                            "The second time, up to ${com.ericflo.winnow.classify.Bootstrap.EXAMPLES_PER_CATEGORY} of your labeled texts per category go with each, as examples. " +
                                            "Nothing is labeled or taught by this: it only measures. You can stop it at any time; keep Winnow open while it runs.",
                                    )
                                },
                                confirmButton = { TextButton(onClick = { confirming = false; viewModel.experiment.start(n) }) { Text("Ask") } },
                                dismissButton = { TextButton(onClick = { confirming = false }) { Text("Not now") } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExperimentResult(viewModel: ModelViewModel, st: com.ericflo.winnow.classify.ExperimentStatus.Finished, service: String, onOpenThread: (Long, List<String>) -> Unit) {
    val s = st.summary
    if (st.stopped) Note("Stopped partway; these are the texts asked both ways before then.")
    st.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    if (s.trials == 0) {
        Note("No text got an answer both ways.")
    } else {
        RateBar("Asked plainly", s.plainRight, s.trials, color = MaterialTheme.colorScheme.outline, detail = "agreed with your label")
        RateBar("With your labels as examples", s.withRight, s.trials, detail = "agreed with your label")
        Text(
            when {
                s.changed == 0 -> "Your examples changed none of its answers."
                else -> "Your examples changed ${count(s.changed)} of its ${count(s.trials)} answers: ${count(s.toward)} to your label, ${count(s.away)} away from it" +
                    (if (s.changed > s.toward + s.away) ", ${count(s.changed - s.toward - s.away)} from one wrong answer to another." else ".")
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Note(
            "So: with your labels as examples, $service " + when {
                s.withRight > s.plainRight -> "sorted these more the way you do."
                s.withRight < s.plainRight -> "sorted these less the way you do."
                else -> "sorted these just as it did without them."
            } + " It doesn't keep or learn from them: each answer is only that question's. Cost ${com.ericflo.winnow.ui.insight.money(st.costUsd)}; both sets of answers are kept under Results.",
        )
        var trials by remember(st.at) { mutableStateOf<List<com.ericflo.winnow.classify.Trial>?>(null) }
        var texts by remember(st.at) { mutableStateOf<Map<String, String>>(emptyMap()) }
        LaunchedEffect(st.at) {
            val t = viewModel.experimentTrials(st.at).filter { it.plain != it.withExamples }
            texts = viewModel.textsOf(t.map { it.key })
            trials = t
        }
        trials?.takeIf { it.isNotEmpty() }?.let { changed ->
            Text("The answers that changed", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            changed.take(MISSES_SHOWN).forEach { t ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(texts[t.key] ?: "No longer on your phone", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "You: ${t.label.label} · plainly ${t.plain?.label} → with examples ${t.withExamples?.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (t.withExamples == t.label) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    TextButton(onClick = viewModel.experiment::dismiss, contentPadding = PaddingValues(0.dp)) { Text("Done") }
}

/** The weight for the service's labels a scored rebuild used, if it was one: "your labels only" is 0. */
internal fun weightOf(model: String): Double? = when {
    model == com.ericflo.winnow.classify.EvalSubject.YoursOnly.key -> 0.0
    model.startsWith("variant:service-weight:") -> model.removePrefix("variant:service-weight:").toDoubleOrNull()
    else -> null
}
