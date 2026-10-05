package com.ericflo.winnow.ui.insight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.ericflo.winnow.classify.ProvenanceSource
import com.ericflo.winnow.ui.components.CategoryDot

/**
 * Why Winnow did what it did with one text: who decided and why it was them, what each of the
 * user, the classifier service and the on-device model thought, how the category became an
 * action, and what the text taught the model. [load] fetches it; [onOpenRun] opens the run that
 * decided it, if one did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvenanceSheet(load: suspend () -> ProvenanceSource.Explained?, onDismiss: () -> Unit, onOpenRun: ((Long) -> Unit)? = null) {
    var loaded by remember { mutableStateOf<ProvenanceSource.Explained?>(null) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        loaded = runCatching { load() }.getOrNull()
        done = true
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Why Winnow did this", style = MaterialTheme.typography.titleLarge)
            val e = loaded
            when {
                !done -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                e == null -> Text("Winnow kept no decision for this message: it arrived before Winnow was your SMS app, or you sent it.", style = MaterialTheme.typography.bodyMedium)
                else -> Body(e, onOpenRun?.let { open -> { id: Long -> onDismiss(); open(id) } })
            }
        }
    }
}

@Composable
private fun Body(e: ProvenanceSource.Explained, onOpenRun: ((Long) -> Unit)?) {
    val x = e.explanation
    val c = MaterialTheme.colorScheme
    e.text?.let {
        Surface(color = c.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
        }
    }
    Text(x.outcome.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Section("Who decided") {
        Text(x.decidedBy, style = MaterialTheme.typography.bodyLarge)
        x.why.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    if (x.opinions.isNotEmpty()) {
        Section("What each thought") {
            x.opinions.forEach { o ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryDot(o.category)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.who, style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                        Text(
                            listOfNotNull(o.category?.label ?: "No category", o.confidence?.let(::pct)).joinToString(" · "),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        o.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    x.policy?.let { Section("From category to what happened") { Text(it, style = MaterialTheme.typography.bodyMedium) } }
    Section("What it taught Winnow") { x.learning.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } }
    // The run that decided it or taught from it: every answer it got, beside what the model made of each.
    x.runId?.let { id -> if (onOpenRun != null && id != 0L) TextButton(onClick = { onOpenRun(id) }) { Text("See that run's answers") } }
    x.gaps?.let { Note(it) }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
