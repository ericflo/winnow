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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.classifier.local.Recipe
import com.ericflo.winnow.classifier.local.Knob
import com.ericflo.winnow.classifier.local.RecipeKind
import com.ericflo.winnow.classifier.local.SweepSpace
import com.ericflo.winnow.classifier.local.SweepTrial
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
internal fun LazyListScope.lab(viewModel: ModelViewModel, onOpenThread: (Long, List<String>) -> Unit = { _, _ -> }) {
    item("in-use") { InUseCard(viewModel) }
    item("status") { StatusCard(viewModel) }
    // What holds the models back from following the user's labels, and what would help.
    item("follow") { FollowYourLabelsCard(viewModel, onOpenThread) }
    item("sweep") { SweepCard(viewModel) }
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
                        "${o.provider}'s labels at ${pct(o.weight)}; ${whoSentIt(o.senderMemory)}. It learns as you label; nothing to retrain by hand, but you can.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(inUse.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${inUse.recipe.describe()}; ${whoSentIt(o.senderMemory)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(
                        inUse.trainedAt?.let { "trained ${ago(it)}" } ?: "not trained yet",
                        inUse.accuracy?.let { "${pct(it)} on conversations it hadn't seen" },
                        inUse.newestAccuracy?.let { "${pct(it)} on your newest ${count(inUse.newestCount)} labels" },
                        "learns again " + if (o.labAutoRetrain) "after each Train round and backlog run" else "only when you retrain it",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                // Said while it's under way, and not asked for twice.
                val status by viewModel.lab.status.collectAsStateWithLifecycle()
                val own by viewModel.retrainingOwn.collectAsStateWithLifecycle()
                val busy = own || (inUse != null && (status as? ModelLab.Status.Running)?.id == inUse.id)
                Button(onClick = viewModel::retrainNow, enabled = !busy) { Text(if (busy) "Retraining…" else "Retrain on device now") }
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
        is ModelLab.Status.Running -> InsightCard(if (s.id == ModelLab.SWEEP) "Sweeping recipes on your labels" else "Training ${entries.firstOrNull { it.id == s.id }?.name ?: ""}") {
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            Text(s.what, style = MaterialTheme.typography.bodyMedium)
            val steeredBy = viewModel.lab.sweep.collectAsStateWithLifecycle().value?.steeredBy?.takeIf { s.id == ModelLab.SWEEP }
            Note(
                (if (steeredBy != null) "Trained on this phone. Between rounds, $steeredBy is shown the tries' settings and scores, never a text."
                else "On this phone, with nothing sent anywhere.") + " It keeps going if you leave Winnow, with its progress and a Stop button in a notification.",
            )
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
        // Blends come from sweeps, of recipes designed here.
        val kinds = RecipeKind.entries.filter { it != RecipeKind.BLEND }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { i, k ->
                SegmentedButton(selected = r.kind == k, onClick = { viewModel.editDraft { it.copy(kind = k) } }, shape = SegmentedButtonDefaults.itemShape(i, kinds.size)) {
                    Text(when (k) { RecipeKind.PERSONAL -> "Personal"; RecipeKind.LINEAR -> "Linear"; else -> "Neural" })
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.layers.size < 4) OutlinedButton(onClick = { viewModel.editDraft { it.copy(layers = it.layers + (it.layers.last() / 2).coerceAtLeast(4)) } }) { Text("Add a layer") }
                if (r.layers.size > 1) OutlinedButton(onClick = { viewModel.editDraft { it.copy(layers = it.layers.dropLast(1)) } }) { Text("Take one off") }
            }
            Toggle("Wide part beside it", ModelLab.HELP.getValue("wide"), r.wide) { v -> viewModel.editDraft { it.copy(wide = v) } }
            Step("Dropout", ModelLab.HELP.getValue("dropout"), DROPOUTS, r.dropout, { pct(it) }) { v -> viewModel.editDraft { it.copy(dropout = v) } }
        }
        if (r.kind == RecipeKind.LINEAR) Step("Bags", ModelLab.HELP.getValue("bags"), (1..10).toList(), r.bags, { "$it" }) { v -> viewModel.editDraft { it.copy(bags = v) } }
        if (r.kind == RecipeKind.LINEAR || r.kind == RecipeKind.NEURAL) {
            Step("Words left out", ModelLab.HELP.getValue("inputDropout"), WORDS_OUT, r.inputDropout, { pct(it) }) { v -> viewModel.editDraft { it.copy(inputDropout = v) } }
            Toggle("Pieces of words", ModelLab.HELP.getValue("pieces"), r.pieces) { v -> viewModel.editDraft { it.copy(pieces = v) } }
            Toggle("When it came and what came before", ModelLab.HELP.getValue("context"), r.context) { v -> viewModel.editDraft { it.copy(context = v) } }
            Toggle("Words by who sent them", ModelLab.HELP.getValue("crosses"), r.crosses) { v -> viewModel.editDraft { it.copy(crosses = v) } }
        }
        Step("Passes", ModelLab.HELP.getValue("epochs"), EPOCHS, r.epochs, { "$it" }) { v -> viewModel.editDraft { it.copy(epochs = v) } }
        Step("Step size", ModelLab.HELP.getValue("learningRate"), STEPS, r.learningRate, { "$it" }) { v -> viewModel.editDraft { it.copy(learningRate = v) } }
        Step("L2", ModelLab.HELP.getValue("l2"), L2S, r.l2, { if (it == 0.0) "0" else "%.0e".format(it) }) { v -> viewModel.editDraft { it.copy(l2 = v) } }
        Text("What it learns from", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        if (r.kind != RecipeKind.PERSONAL) {
            Toggle("The shipped examples", ModelLab.HELP.getValue("corpus"), r.includeCorpus) { v -> viewModel.editDraft { it.copy(includeCorpus = v) } }
            if (r.includeCorpus) Step("Each shipped example counts", null, WEIGHTS, r.corpusWeight, { times(it) }) { v -> viewModel.editDraft { it.copy(corpusWeight = v) } }
            Step("Each of your labels counts", ModelLab.HELP.getValue("userWeight"), USER_WEIGHTS, r.userWeight, { times(it) }) { v -> viewModel.editDraft { it.copy(userWeight = v) } }
            Step("The rest of your conversations count", ModelLab.HELP.getValue("conversationWeight"), CONVERSATION_WEIGHTS, r.conversationWeight, { if (it == 0.0) "left out" else times(it) }) { v ->
                viewModel.editDraft { it.copy(conversationWeight = v) }
            }
        }
        Step("Each of $service's labels counts", ModelLab.HELP.getValue("serviceWeight"), SERVICE_WEIGHTS, r.serviceWeight, { if (it == 0.0) "left out" else times(it) }) { v -> viewModel.editDraft { it.copy(serviceWeight = v) } }
        Step("Who sent it: your labels of each sender count", ModelLab.HELP.getValue("senderMemory"), SENDER_STRENGTHS, r.senderMemory, { if (it == 0.0) "not at all" else times(it) }) { v -> viewModel.editDraft { it.copy(senderMemory = v) } }
        Step("What came before in its conversation counts", ModelLab.HELP.getValue("conversationReading"), READINGS, r.conversationReading, { if (it == 0.0) "not at all" else times(it) }) { v ->
            viewModel.editDraft { it.copy(conversationReading = v) }
        }
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
        Note("Every one scored the same two ways on your labels: on conversations it hadn't seen (cross-validated by conversation), and on your newest labels after learning from the ones before them, the way the phone meets a new text. Their scorings are under Evaluate too, beside Winnow's own.")
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
                own?.let { RateBar("Winnow's own (personal layer)", Math.round(it.accuracy * it.examples).toInt(), it.examples, color = MaterialTheme.colorScheme.outline, detail = "macro F1 ${f2(it.macroF1)}") }
                shipped?.let { RateBar("As it ships", Math.round(it.accuracy * it.examples).toInt(), it.examples, color = MaterialTheme.colorScheme.outline, detail = "macro F1 ${f2(it.macroF1)}") }
                entries.filter { it.accuracy != null }.forEach { e ->
                    RateBar(e.name, Math.round((e.accuracy ?: 0.0) * e.scoredOn).toInt(), e.scoredOn, detail = "macro F1 ${e.macroF1?.let(::f2) ?: "—"}")
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
                    Text(e.name + if (inUse) " · in use" else "", style = MaterialTheme.typography.titleSmall)
                    Text(e.recipe.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.recipe.details(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (e.accuracy != null) Scores(e)
                    Text(
                        if (e.trainedAt == null) "Not trained yet."
                        else listOfNotNull(
                            "trained ${ago(e.trainedAt)} (final fit ${com.ericflo.winnow.ui.insight.duration(e.trainMillis)})",
                            e.accuracy?.let { "macro F1 ${e.macroF1?.let(::f2)} on ${count(e.scoredOn)} labels" },
                            e.fitAccuracy?.let { "follows ${pct(it)} of the labels it learns from" },
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
                        // A blend is made of recipes a sweep tried; it's edited by sweeping again.
                        if (e.recipe.kind != RecipeKind.BLEND) {
                            TextButton(onClick = { viewModel.setDraft(e.recipe, "${e.name} (copy)") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Edit a copy") }
                        }
                        TextButton(onClick = { deleting = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Delete") }
                    }
                }
            }
        }
    }
}

/**
 * Sweeps: Winnow trying recipes on the user's labels by itself, round after round, steered by
 * the classifier service when the user lets it (each steer one paid call), and keeping the best.
 * Everything it tried is listed, with how each round was steered, what the steering leaned
 * toward and what it cost.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SweepCard(viewModel: ModelViewModel) {
    val sweep by viewModel.lab.sweep.collectAsStateWithLifecycle()
    val prefs by viewModel.lab.sweepPrefs.collectAsStateWithLifecycle()
    val status by viewModel.lab.status.collectAsStateWithLifecycle()
    val service by viewModel.steeringService.collectAsStateWithLifecycle()
    val entries by viewModel.lab.entries.collectAsStateWithLifecycle()
    val running = (status as? ModelLab.Status.Running)?.id == ModelLab.SWEEP
    val busy = status is ModelLab.Status.Running
    InsightCard("Sweep for a better recipe", subtitle = "Winnow tries recipes on your labels by itself, round after round, and keeps the best") {
        Note(
            "Each try is trained on this phone and scored the way your models are: on conversations it hadn't seen, with your labels of each sender. " +
                "It starts from your best so far and a few different ways to keep a model from memorizing your labels. When its best beats yours, " +
                "it's kept below as a model of its own, trained on everything and scored on your newest labels too, for you to use or not.",
        )
        val s = service
        if (s != null) {
            Toggle("Let $s steer between rounds", "It's shown each try's settings and scores, and how many labels you have in each category: never a text, a sender or a word. It answers which settings to try next, and whether another round is worth it. Each round it steers is one paid call.", prefs.steer) { v ->
                viewModel.lab.setSweepPrefs(prefs.copy(steer = v))
            }
            if (prefs.steer) {
                Step("Calls to $s a sweep, at most", "One a round at most. Past that, or if it can't be reached, the phone steers.", (1..20).toList(), prefs.steeringCalls, { "$it" }) { v ->
                    viewModel.lab.setSweepPrefs(prefs.copy(steeringCalls = v))
                }
            }
        } else {
            Note("No classifier service is set up, so the phone steers: each round leans toward the settings that have done best so far.")
        }
        Step("Rounds", null, (1..20).toList(), prefs.rounds, { "$it" }) { v -> viewModel.lab.setSweepPrefs(prefs.copy(rounds = v)) }
        Step("Tries a round", null, (2..12).toList(), prefs.perRound, { "$it" }) { v -> viewModel.lab.setSweepPrefs(prefs.copy(perRound = v)) }
        Toggle(
            "End early when there's nothing left to find",
            "Only once at least half its rounds are done, and only when the steering twice running puts another round's chance of gaining under 10%. Off (as it starts), it runs every round.",
            prefs.endEarlyWhenSettled,
        ) { v -> viewModel.lab.setSweepPrefs(prefs.copy(endEarlyWhenSettled = v)) }
        Note("About ${prefs.rounds * prefs.perRound + SweepSpace.STARTS.size + 5} tries, starting from your models, the last sweep's best and a few different ways to keep a model from memorizing your labels. Each takes seconds to a minute on a phone; it keeps going if you leave Winnow, with a Stop button in its notification.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel.lab::startSweep, enabled = !busy) { Text(if (running) "Sweeping…" else if (sweep == null) "Start a sweep" else "Sweep again") }
            if (running) OutlinedButton(onClick = viewModel.lab::cancel) { Text("Stop") }
        }
        sweep?.let { SweepResults(it, running, busy, entries, viewModel) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SweepResults(sw: ModelLab.Sweep, running: Boolean, busy: Boolean, entries: List<ModelLab.Entry>, viewModel: ModelViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(if (running) "This sweep so far" else "The last sweep", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(
        listOfNotNull(
            "started ${ago(sw.startedAt)}",
            "${count(sw.tried)} tries",
            sw.rounds.size.takeIf { it > 0 }?.let { "$it round${if (it == 1) "" else "s"} after the starting points" },
            when {
                sw.stopped -> "stopped by you"
                sw.failed != null -> "stopped: ${sw.failed}"
                sw.interrupted -> "cut off before it finished (Winnow was closed)"
                sw.settled -> "ended early: the steering twice saw almost nothing left to gain"
                else -> null
            },
            sw.steeredBy?.let { name ->
                "$name was asked to steer ${sw.steeringCalls} time${if (sw.steeringCalls == 1) "" else "s"}" +
                    (if (sw.steeringFailed > 0) " (${sw.steeringFailed} failed; the phone steered from there)" else "") + when {
                        sw.steeringCalls == 0 -> ""
                        sw.costUsd > 0 -> ", about ${usd(sw.costUsd)} in all"
                        else -> "; its cost isn't reported"
                    }
            } ?: if (sw.noService) "no service was set up, so the phone steered" else "the phone steered",
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
    )
    val best = sw.best
    if (best != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                Text(pct(best.accuracy), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("its best, on conversations it hadn't seen", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            sw.baselines.forEach { b ->
                Column {
                    Text(pct(b.accuracy), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("${b.name}, scored the same way", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }
    sw.keptId?.let { id -> entries.firstOrNull { it.id == id } }?.let { kept ->
        Text(
            "Kept below as “${kept.name}”" + (kept.newestAccuracy?.let { ": ${pct(it)} on your newest ${count(kept.newestCount)} labels by a model trained only on the ones before them" } ?: "") + ". Use it from there.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    val bar = sw.baselines.maxByOrNull { it.accuracy }
    if (!running && best != null && sw.keptId == null && bar != null && best.accuracy <= bar.accuracy) {
        Text("Nothing it tried beat ${bar.name}, so nothing was kept.", style = MaterialTheme.typography.bodyMedium)
    }
    if (sw.trials.isNotEmpty()) {
        Note(
            "The best of many tries is a little lucky: it was picked by the very scores shown, so one that wins by under a point may not hold up on new texts. " +
                "A kept model is also scored on your newest labels by a model trained only on the ones before them: a second look, though the tries were scored on those labels too.",
        )
        sw.trials.take(8).forEach { t -> TrialRow(t, sw, busy, viewModel) }
        if (sw.trials.size > 8) Note("And ${sw.trials.size - 8} more, each scored below these.")
    }
    sw.rounds.forEach { r ->
        Column {
            Text("Round ${r.round}: steered by ${r.steeredBy}" + (r.costUsd.takeIf { it > 0 }?.let { " (${usd(it)})" } ?: ""), style = MaterialTheme.typography.labelLarge)
            // A lean is how far its odds are above even ones: 50% of two values is none at all.
            val leans = r.leaning.map { it to it.odds * (Knob.byKey(it.knob)?.values?.size ?: 1) }.filter { it.second >= 1.25 }.sortedByDescending { it.second }.take(5)
            Text(
                (if (leans.isEmpty()) "Leaned no way in particular" else "Leaned toward " + leans.joinToString(", ") { (l, _) -> "${l.knob.replace('_', ' ')} ${l.value} (${pct(l.odds)})" }) +
                    (r.more?.let { " · another round worth it: ${pct(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
            r.made?.let { Text("Tried: $it. Its odds were spread so the round looks around: none above half, values tried less counting more, last round's lean at half.", style = MaterialTheme.typography.bodySmall, color = muted) }
            r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun TrialRow(t: SweepTrial, sw: ModelLab.Sweep, busy: Boolean, viewModel: ModelViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${pct(t.accuracy)} · macro F1 ${f2(t.macroF1)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                // The user's own is theirs already; the kept one is below.
                val kept = sw.keptId != null && t == sw.best
                if (sw.baselines.none { it.name == t.from } && !kept) {
                    TextButton(onClick = { viewModel.lab.keepTrial(t) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Keep it") }
                }
            }
            val yours = sw.baselines.firstOrNull { t.from == it.name || t.from.startsWith("${it.name}, ") }
            Text(
                when {
                    yours != null && t.from == yours.name -> "${yours.name}, as you have it"
                    yours != null -> "${yours.name}, " + t.from.removePrefix("${yours.name}, ")
                    t.from == "start" -> "a starting point"
                    t.from == "the last sweep's best" -> "one of the last sweep's best, scored again"
                    t.from == "blend" -> "a blend of tries above, averaged"
                    else -> "round ${t.round}, steered by ${t.from}"
                } + " · ${pct(t.wordsAccuracy)} from the words alone" + when {
                    t.millis >= 1000 -> " · ${com.ericflo.winnow.ui.insight.duration(t.millis)}"
                    t.millis > 0 -> " · under a second"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall, color = muted,
            )
            Text(t.recipe.describe(), style = MaterialTheme.typography.bodySmall)
            Text(t.recipe.details(), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

/** "$0.0008", "$0.12": what steering cost, in dollars. */
private fun usd(x: Double) = if (x >= 0.01) "$" + "%.2f".format(x) else "$" + "%.4f".format(x)

/** One setting stepped through [options] with − and +, its value between, and what it does under. */
@Composable
private fun <T> Step(label: String, help: String?, options: List<T>, value: T, format: (T) -> String, onChange: (T) -> Unit) {
    val at = options.indexOf(value).let { if (it >= 0) it else options.indexOfFirst { o -> (o as? Comparable<T>)?.let { c -> c >= value } == true }.coerceAtLeast(0) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            // Read aloud as what they change: "Less: Passes", not "minus".
            FilledTonalIconButton(
                onClick = { onChange(options[(at - 1).coerceAtLeast(0)]) }, enabled = at > 0,
                modifier = Modifier.semantics { contentDescription = "Less: $label" },
            ) { Text("−") }
            Text(format(value), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.width(84.dp))
            FilledTonalIconButton(
                onClick = { onChange(options[(at + 1).coerceAtMost(options.lastIndex)]) }, enabled = at < options.lastIndex,
                modifier = Modifier.semantics { contentDescription = "More: $label" },
            ) { Text("+") }
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
private val WORDS_OUT = (0..12).map { it * 0.05 }.map { Math.round(it * 100) / 100.0 }
private val EPOCHS = listOf(1, 2, 3, 5, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 80, 100, 150, 200, 300, 500)
private val STEPS = listOf(0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.3, 0.5, 0.75, 1.0, 1.5, 2.0)
private val L2S = listOf(0.0, 1e-8, 1e-7, 1e-6, 1e-5, 1e-4, 1e-3, 1e-2, 1e-1)
private val WEIGHTS = listOf(0.0, 0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0)
private val USER_WEIGHTS = listOf(0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0)
private val CONVERSATION_WEIGHTS = listOf(0.0, 0.05, 0.1, 0.25, 0.5, 1.0)
private val SERVICE_WEIGHTS = listOf(0.0, 0.1, 0.2, 0.35, 0.5, 0.75, 1.0, 1.5, 2.0)
private val SENDER_STRENGTHS = listOf(0.0, 0.5, 1.0, 2.0, 3.0, 4.0)
private val READINGS = listOf(0.0, 0.5, 1.0, 2.0, 3.0, 4.0)

/** "8.0 MB", "640 KB": what a trained model takes up on the phone. */
private fun sizeOf(bytes: Long): String =
    if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1_000_000.0) else "${(bytes / 1000).coerceAtLeast(1)} KB"

/** How the user's labels of each sender count, in a few words (see SenderMemory). */
private fun whoSentIt(strength: Double) = if (strength <= 0.0) "your labels of each sender left out" else "your labels of each sender count ${times(strength)}"

/** "×1", "×0.5". */
private fun times(x: Double) = "×" + if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

/**
 * A Lab model's two scores side by side: on conversations it hadn't seen (cross-validated; a
 * sender's texts are held out together, so the user's labels of the sender seldom count), and on
 * the user's newest labels after learning from the older ones, the way the phone meets a text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Scores(e: ModelLab.Entry) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column {
            Text(e.accuracy?.let(::pct) ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("on conversations it hadn't seen", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        e.newestAccuracy?.let { a ->
            Column {
                Text(pct(a), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("on your newest ${count(e.newestCount)} labels", style = MaterialTheme.typography.labelSmall, color = muted)
                e.newestWordsAccuracy?.takeIf { e.recipe.senderMemory > 0 && it != a }?.let {
                    Text("${pct(it)} from the words alone", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }
}
