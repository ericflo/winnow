package com.ericflo.winnow.ui.model

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.classifier.message.SenderKind
import com.ericflo.winnow.classify.MissBreakdown
import com.ericflo.winnow.data.db.EvalEntity
import com.ericflo.winnow.ui.components.CategoryDot
import com.ericflo.winnow.ui.insight.InsightCard
import com.ericflo.winnow.ui.insight.Note
import com.ericflo.winnow.ui.insight.count
import com.ericflo.winnow.ui.insight.pct

/**
 * Every text the best model on the user's labels got wrong, broken down (see MissBreakdown): how
 * far it is to 90% and the fewest kinds of miss that would get there, then each kind with what
 * its misses have in common, and the texts themselves to open. The user's labels are the answer:
 * these are where the model doesn't yet follow them.
 */
@Composable
fun MissesCard(viewModel: ModelViewModel, onOpenThread: (Long, List<String>) -> Unit, onOpenTrain: () -> Unit = {}) {
    val entries by viewModel.lab.entries.collectAsStateWithLifecycle()
    val history by viewModel.evals.history.collectAsStateWithLifecycle()
    val all = history ?: return
    // The same model as "What would help it follow your labels": the best of the latest scorings.
    val lab = entries.mapNotNull { e -> e.evalId?.let { id -> all.firstOrNull { it.id == id } } }
    val own = all.firstOrNull { it.model == "now" && it.dataset == EvalEntity.DATASET_MINE && it.examples > 0 }
    val eval = (lab + listOfNotNull(own)).filter { it.method != EvalEntity.METHOD_TRAINED_ON }.maxByOrNull { it.accuracy } ?: return
    var result by remember(eval.id) { mutableStateOf<MissBreakdown.Result?>(null) }
    LaunchedEffect(eval.id) { result = viewModel.evals.breakdown(eval) }
    InsightCard("Every miss, broken down", subtitle = "${eval.label}: each of your labels it didn't follow, on conversations it hadn't seen") {
        val r = result
        if (r == null) {
            CircularProgressIndicator()
            return@InsightCard
        }
        if (r.misses == 0) {
            Text("It followed every one of your ${count(r.total)} labels.", style = MaterialTheme.typography.bodyLarge)
            return@InsightCard
        }
        Text(
            "${count(r.misses)} misses of ${count(r.total)} labels: ${pct(1.0 - r.misses.toDouble() / r.total)} followed.",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        )
        if (r.needed > 0) {
            Text(
                "${pct(r.target)} allows ${count(r.allowed)} misses: it has to follow ${count(r.needed)} more of your labels.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Note(
                "The fewest kinds of miss that would get there: " + r.path.joinToString(", and ") { (g, n) ->
                    (if (n == g.count) "all ${count(g.count)}" else "${count(n)} of the ${count(g.count)}") +
                        " where you said ${g.label.label.lowercase()} and it said ${g.predicted.label.lowercase()}"
                } + ".",
            )
        } else {
            Text("That's ${pct(r.target)} or better.", style = MaterialTheme.typography.bodyLarge)
        }
        if (r.closeCalls > 0) {
            Note("${count(r.closeCalls)} of the misses were close calls (it was under ${pct(MissBreakdown.CLOSE)} sure): a little more to go on could turn these.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            r.groups.forEach { g -> GroupRow(g, r.misses, onOpenThread) { viewModel.labelMoreLike(g.label, onOpenTrain) } }
        }
    }
}

@Composable
private fun GroupRow(g: MissBreakdown.Group, misses: Int, onOpenThread: (Long, List<String>) -> Unit, onLabelMore: () -> Unit) {
    var open by remember(g.label, g.predicted) { mutableStateOf(false) }
    val label = g.label.label.lowercase()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
            CategoryDot(g.label)
            Spacer(Modifier.width(6.dp))
            Text("You: $label · it: ${g.predicted.label.lowercase()}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${count(g.count)} · ${pct(g.count.toDouble() / misses)} of misses", style = MaterialTheme.typography.labelMedium)
        }
        Note(
            listOfNotNull(
                if (g.count == g.ofLabel) "every one of your ${count(g.ofLabel)} $label labels" else "${count(g.count)} of your ${count(g.ofLabel)} $label labels",
                if (g.ofLabel < RARE) "one of your rarest categories: more examples of it are what help most" else null,
                (if (g.conversations == 1) "all from one conversation" else "from ${count(g.conversations)} conversations") +
                    (if (g.conversations > 1 && g.mostFromOne >= 3 && g.mostFromOne * 2 >= g.count) ", ${count(g.mostFromOne)} from one" else ""),
                g.senderKinds.entries.sortedByDescending { it.value }.takeIf { it.isNotEmpty() }?.joinToString(", ") { (k, n) -> "${count(n)} from ${kindLabel(k)}" },
                g.sure.takeIf { it > 0 }?.let { "${count(it)} it was sure of (their words read as ${g.predicted.label.lowercase()})" },
                g.close.takeIf { it > 0 }?.let { "${count(it)} close call${if (it == 1) "" else "s"}" },
            ).joinToString(" · "),
        )
        if (open) {
            g.misses.take(SHOWN).forEach { m -> MissRow(m, onOpenThread) }
            if (g.misses.size > SHOWN) Note("And ${count(g.misses.size - SHOWN)} more.")
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) { Text(if (open) "Hide them" else "Show them") }
            // More of the user's own sense of the category: texts that read like their labels of it, to label.
            TextButton(onClick = onLabelMore, contentPadding = PaddingValues(0.dp)) { Text("Label more like your $label") }
        }
    }
}

@Composable
private fun MissRow(m: MissBreakdown.Item, onOpenThread: (Long, List<String>) -> Unit) {
    val canOpen = m.threadId != null && m.sender != null
    Column(Modifier.fillMaxWidth().clickable(enabled = canOpen) { onOpenThread(m.threadId!!, listOf(m.sender!!)) }.padding(vertical = 4.dp)) {
        Text(m.text ?: "No longer on your phone", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "You: ${m.label.label} · it said ${m.predicted.label}, ${pct(m.confidence)} sure" + (m.sender?.let { " · from ${kindLabel(SenderKind.of(it)).removeSuffix("s")}" } ?: ""),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun kindLabel(k: SenderKind) = when (k) {
    SenderKind.PHONE_NUMBER -> "phone numbers"
    SenderKind.SHORT_CODE -> "short codes"
    SenderKind.ALPHANUMERIC -> "named senders"
    SenderKind.EMAIL -> "email addresses"
    SenderKind.UNKNOWN -> "unknown senders"
}

/** Under this many labels, a category is one of the user's rarest. */
private const val RARE = 20
private const val SHOWN = 30
