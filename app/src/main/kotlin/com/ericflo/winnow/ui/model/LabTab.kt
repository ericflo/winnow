package com.ericflo.winnow.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.classifier.local.Recipe
import com.ericflo.winnow.classifier.local.RecipeKind
import com.ericflo.winnow.classify.ModelLab
import com.ericflo.winnow.ui.insight.InsightCard
import com.ericflo.winnow.ui.insight.Note
import com.ericflo.winnow.ui.insight.RateBar
import com.ericflo.winnow.ui.insight.ago
import com.ericflo.winnow.ui.insight.count
import com.ericflo.winnow.ui.insight.f2
import com.ericflo.winnow.ui.insight.pct

/**
 * The Lab tab: which model is sorting texts now, a button to retrain it on the phone, a designer
 * for new models (their kind, capacity, fitting and what they learn from, every number
 * changeable), and every model designed so far with how it scored on the user's own labels.
 */
internal fun LazyListScope.lab(viewModel: ModelViewModel) {
    item("in-use") { InUseCard(viewModel) }
    item("status") { StatusCard(viewModel) }
    item("design") { DesignCard(viewModel) }
    item("models-head") { ModelsHeader(viewModel) }
    item("models") { Models(viewModel) }
}

@Composable
private fun InUseCard(viewModel: ModelViewModel) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val entries by viewModel.lab.entries.collectAsStateWithLifecycle()
    val o = overview ?: return
    val inUse = entries.firstOrNull { it.id == o.labModel }
    val c = MaterialTheme.colorScheme
    Surface(color = c.primaryContainer, contentColor = c.onPrimaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sorting your texts now", style = MaterialTheme.typography.labelLarge)
            if (inUse == null) {
                Text("Winnow's own: the shipped model with your personal layer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Fitted on every label as it comes: ${o.personalEpochs} passes, step ${o.personalStep}, L2 ${o.personalL2}, " +
                        "${o.provider}'s labels at ${pct(o.weight)}. It learns as you label; nothing to retrain by hand, but you can.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(inUse.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(inUse.recipe.describe(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(
                        inUse.trainedAt?.let { "trained ${ago(it)}" } ?: "not trained yet",
                        inUse.accuracy?.let { "${pct(it)} on your labels (${count(inUse.scoredOn)})" },
                        "learns again " + if (o.labAutoRetrain) "after each Train round and backlog run" else "only when you retrain it",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::retrainNow) { Text("Retrain on device now") }
                if (inUse != null) OutlinedButton(onClick = { viewModel.useLab(null) }) { Text("Back to Winnow's own") }
            }
            if (inUse != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Retrain it after each Train round and backlog run", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = o.labAutoRetrain, onCheckedChange = viewModel::setLabAutoRetrain)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(viewModel: ModelViewModel) {
    val status by viewModel.lab.status.collectAsStateWithLifecycle()
    val entries by viewModel.lab.entries.collectAsStateWithLifecycle()
    when (val s = status) {
        is ModelLab.Status.Running -> InsightCard("Training ${entries.firstOrNull { it.id == s.id }?.name ?: ""}") {
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            Text(s.what, style = MaterialTheme.typography.bodyMedium)
            Note("On this phone, with nothing sent anywhere. It keeps going if you leave Winnow, with its progress and a Stop button in a notification.")
            TextButton(onClick = viewModel.lab::cancel, contentPadding = PaddingValues(0.dp)) { Text("Stop") }
        }
        is ModelLab.Status.Failed -> InsightCard("Training stopped") {
            Text(s.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        ModelLab.Status.Idle -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesignCard(viewModel: ModelViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val name by viewModel.draftName.collectAsStateWithLifecycle()
    val status by viewModel.lab.status.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val service = overview?.provider ?: "the service"
    val r = draft
    InsightCard("Design a model", subtitle = "Pick a starting point, change anything, then train it here and score it on your labels") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Recipe.PRESETS.forEach { (label, preset) ->
                FilterChip(selected = r == preset, onClick = { viewModel.setDraft(preset, label) }, label = { Text(label) })
            }
        }
        OutlinedTextField(value = name, onValueChange = viewModel::setDraftName, label = { Text("Its name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RecipeKind.entries.forEachIndexed { i, k ->
                SegmentedButton(selected = r.kind == k, onClick = { viewModel.editDraft { it.copy(kind = k) } }, shape = SegmentedButtonDefaults.itemShape(i, RecipeKind.entries.size)) {
                    Text(when (k) { RecipeKind.PERSONAL -> "Personal"; RecipeKind.LINEAR -> "Linear"; RecipeKind.NEURAL -> "Neural" })
                }
            }
        }
        Note(ModelLab.HELP.getValue("kind"))
        if (r.kind != RecipeKind.PERSONAL) {
            Step("Buckets", ModelLab.HELP.getValue("buckets"), BUCKETS, r.buckets, { count(it) }) { v -> viewModel.editDraft { it.copy(buckets = v) } }
        }
        if (r.kind == RecipeKind.NEURAL) {
            Text("Layers", style = MaterialTheme.typography.titleSmall)
            Note(ModelLab.HELP.getValue("layers"))
            r.layers.forEachIndexed { i, width ->
                Step(if (i == 0) "Embedding" else "Hidden layer $i", null, WIDTHS, width, { "$it" }) { v -> viewModel.editDraft { it.copy(layers = it.layers.toMutableList().also { l -> l[i] = v }) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.layers.size < 4) OutlinedButton(onClick = { viewModel.editDraft { it.copy(layers = it.layers + (it.layers.last() / 2).coerceAtLeast(4)) } }) { Text("Add a layer") }
                if (r.layers.size > 1) OutlinedButton(onClick = { viewModel.editDraft { it.copy(layers = it.layers.dropLast(1)) } }) { Text("Take one off") }
            }
            Toggle("Wide part beside it", ModelLab.HELP.getValue("wide"), r.wide) { v -> viewModel.editDraft { it.copy(wide = v) } }
            Step("Dropout", ModelLab.HELP.getValue("dropout"), DROPOUTS, r.dropout, { pct(it) }) { v -> viewModel.editDraft { it.copy(dropout = v) } }
        }
        if (r.kind == RecipeKind.LINEAR) Step("Bags", ModelLab.HELP.getValue("bags"), (1..10).toList(), r.bags, { "$it" }) { v -> viewModel.editDraft { it.copy(bags = v) } }
        Step("Passes", ModelLab.HELP.getValue("epochs"), EPOCHS, r.epochs, { "$it" }) { v -> viewModel.editDraft { it.copy(epochs = v) } }
        Step("Step size", ModelLab.HELP.getValue("learningRate"), STEPS, r.learningRate, { "$it" }) { v -> viewModel.editDraft { it.copy(learningRate = v) } }
        Step("L2", ModelLab.HELP.getValue("l2"), L2S, r.l2, { if (it == 0.0) "0" else "%.0e".format(it) }) { v -> viewModel.editDraft { it.copy(l2 = v) } }
        Text("What it learns from", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        if (r.kind != RecipeKind.PERSONAL) {
            Toggle("The shipped examples", ModelLab.HELP.getValue("corpus"), r.includeCorpus) { v -> viewModel.editDraft { it.copy(includeCorpus = v) } }
            if (r.includeCorpus) Step("Each shipped example counts", null, WEIGHTS, r.corpusWeight, { "×$it" }) { v -> viewModel.editDraft { it.copy(corpusWeight = v) } }
            Step("Each of your labels counts", ModelLab.HELP.getValue("userWeight"), USER_WEIGHTS, r.userWeight, { "×$it" }) { v -> viewModel.editDraft { it.copy(userWeight = v) } }
        }
        Step("Each of $service's labels counts", ModelLab.HELP.getValue("serviceWeight"), SERVICE_WEIGHTS, r.serviceWeight, { if (it == 0.0) "left out" else "×$it" }) { v -> viewModel.editDraft { it.copy(serviceWeight = v) } }
        if (r.kind != RecipeKind.PERSONAL) Toggle("Balance the categories", ModelLab.HELP.getValue("balance"), r.balance) { v -> viewModel.editDraft { it.copy(balance = v) } }
        Step("Seed", "The same seed trains the same model from the same texts.", (1..100).toList(), r.seed, { "$it" }) { v -> viewModel.editDraft { it.copy(seed = v) } }
        val problem = r.problem()
        problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Note(r.describe())
        Button(onClick = viewModel::trainDraft, enabled = problem == null && status !is ModelLab.Status.Running) { Text("Train it and score it on my labels") }
    }
}

@Composable
private fun ModelsHeader(viewModel: ModelViewModel) {
    val entries by viewModel.lab.entries.collectAsStateWithLifecycle()
    if (entries.isEmpty()) return
    Column(Modifier.padding(top = 8.dp)) {
        Text("Your models", style = MaterialTheme.typography.titleMedium)
        Note("Every one scored the same way, on your labels: cross-validated by conversation. Their scorings are under Evaluate too, beside Winnow's own.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Models(viewModel: ModelViewModel) {
    val entries by viewModel.lab.entries.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val history by viewModel.evals.history.collectAsStateWithLifecycle()
    val status by viewModel.lab.status.collectAsStateWithLifecycle()
    // The latest scores of Winnow's own and of the model as it ships, to compare against.
    val own = history?.firstOrNull { it.model == "now" && it.examples > 0 }
    val shipped = history?.firstOrNull { it.model == com.ericflo.winnow.data.db.EvalEntity.MODEL_BASE && it.examples > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (entries.isNotEmpty() && (own != null || shipped != null)) {
            InsightCard("Side by side", subtitle = "Accuracy on your labels, each from its latest scoring") {
                own?.let { RateBar("Winnow's own (personal layer)", (it.accuracy * it.examples).toInt(), it.examples, color = MaterialTheme.colorScheme.outline, detail = "macro F1 ${f2(it.macroF1)}") }
                shipped?.let { RateBar("As it ships", (it.accuracy * it.examples).toInt(), it.examples, color = MaterialTheme.colorScheme.outline, detail = "macro F1 ${f2(it.macroF1)}") }
                entries.filter { it.accuracy != null }.forEach { e ->
                    RateBar(e.name, ((e.accuracy ?: 0.0) * e.scoredOn).toInt(), e.scoredOn, detail = "macro F1 ${e.macroF1?.let(::f2) ?: "—"}")
                }
                if (own == null) Note("Score Winnow's own under Evaluate to see it here too.")
            }
        }
        entries.asReversed().forEach { e ->
            val inUse = overview?.labModel == e.id
            var deleting by remember(e.id) { mutableStateOf(false) }
            if (deleting) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { deleting = false },
                    title = { Text("Delete ${e.name}?") },
                    text = {
                        Text(
                            "Its design goes" + (if (e.bytes > 0) ", and its trained model (${sizeOf(e.bytes)})" else if (e.trainedAt != null) ", and what it learned" else "") +
                                ". Its scorings stay under Evaluate, and your labels aren't touched." +
                                if (inUse) " It's in use: Winnow goes back to its own model." else "",
                        )
                    },
                    confirmButton = { TextButton(onClick = { deleting = false; viewModel.lab.delete(e.id) }) { Text("Delete") } },
                    dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep it") } },
                )
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name + if (inUse) " · in use" else "", style = MaterialTheme.typography.titleSmall)
                            Text(e.recipe.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(e.accuracy?.let(::pct) ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        if (e.trainedAt == null) "Not trained yet."
                        else listOfNotNull(
                            "trained ${ago(e.trainedAt)} (final fit ${com.ericflo.winnow.ui.insight.duration(e.trainMillis)})",
                            e.accuracy?.let { "macro F1 ${e.macroF1?.let(::f2)} on ${count(e.scoredOn)} labels" },
                            if (e.parameters > 0) "${count(e.parameters.toInt())} numbers" else null,
                            e.bytes.takeIf { it > 0 }?.let { "${sizeOf(it)} on this phone" },
                            "learned from ${count(e.learnedFrom)} texts",
                            e.leftOut.takeIf { it > 0 }?.let { "$it of your labels left out (their texts are gone)" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val busy = status is ModelLab.Status.Running
                    // Wraps: at a large text size four buttons don't fit one line, and the last went off the card.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!inUse && e.trainedAt != null) TextButton(onClick = { viewModel.useLab(e.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Use it") }
                        TextButton(onClick = { viewModel.trainEntry(e.id) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (e.trainedAt == null) "Train" else "Retrain & rescore") }
                        TextButton(onClick = { viewModel.setDraft(e.recipe, "${e.name} (copy)") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Edit a copy") }
                        TextButton(onClick = { deleting = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Delete") }
                    }
                }
            }
        }
    }
}

/** One setting stepped through [options] with − and +, its value between, and what it does under. */
@Composable
private fun <T> Step(label: String, help: String?, options: List<T>, value: T, format: (T) -> String, onChange: (T) -> Unit) {
    val at = options.indexOf(value).let { if (it >= 0) it else options.indexOfFirst { o -> (o as? Comparable<T>)?.let { c -> c >= value } == true }.coerceAtLeast(0) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            FilledTonalIconButton(onClick = { onChange(options[(at - 1).coerceAtLeast(0)]) }, enabled = at > 0) { Text("−") }
            Text(format(value), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.width(84.dp))
            FilledTonalIconButton(onClick = { onChange(options[(at + 1).coerceAtMost(options.lastIndex)]) }, enabled = at < options.lastIndex) { Text("+") }
        }
        help?.let { Note(it) }
    }
}

@Composable
private fun Toggle(label: String, help: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
        Note(help)
    }
}

private val BUCKETS = (10..18).map { 1 shl it }
private val WIDTHS = listOf(2, 4, 8, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512)
private val DROPOUTS = (0..16).map { it * 0.05 }.map { Math.round(it * 100) / 100.0 }
private val EPOCHS = listOf(1, 2, 3, 5, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 80, 100, 150, 200, 300, 500)
private val STEPS = listOf(0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.3, 0.5, 0.75, 1.0, 1.5, 2.0)
private val L2S = listOf(0.0, 1e-8, 1e-7, 1e-6, 1e-5, 1e-4, 1e-3, 1e-2, 1e-1)
private val WEIGHTS = listOf(0.0, 0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0)
private val USER_WEIGHTS = listOf(0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0)
private val SERVICE_WEIGHTS = listOf(0.0, 0.1, 0.2, 0.35, 0.5, 0.75, 1.0, 1.5, 2.0)

/** "8.0 MB", "640 KB": what a trained model takes up on the phone. */
private fun sizeOf(bytes: Long): String =
    if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1_000_000.0) else "${(bytes / 1000).coerceAtLeast(1)} KB"
