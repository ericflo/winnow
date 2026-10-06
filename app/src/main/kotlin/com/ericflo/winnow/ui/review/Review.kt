package com.ericflo.winnow.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ericflo.winnow.classify.ReviewStatus

/** Asks before classifying older conversations, naming how many and with what. */
@Composable
fun ReviewConfirmDialog(pending: Int, classifier: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check $pending older ${if (pending == 1) "conversation" else "conversations"}?") },
        text = {
            Text(
                "Winnow will classify the newest message in each with $classifier, under the same privacy settings " +
                    "as new messages. Spam moves to Filtered; nothing is deleted, and you won't get notifications.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Check them") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The inbox prompt: offer, progress, then a summary. Hidden when there's nothing to say. */
@Composable
fun ReviewInboxCard(status: ReviewStatus, classifier: String, onStart: () -> Unit, onDismiss: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val (title, body) = when (status) {
        is ReviewStatus.Ready -> if (status.pending == 0) return else
            "Check older conversations for spam?" to
                if (status.pending == 1) "1 conversation arrived before Winnow could look at it." else "${status.pending} conversations arrived before Winnow could look at them."
        is ReviewStatus.Running -> "Checking older conversations…" to "${status.done} of ${status.total}"
        is ReviewStatus.Finished -> "Older conversations checked" to summary(status, classifier)
        ReviewStatus.Unknown -> return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (status is ReviewStatus.Running) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { status.done / status.total.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Filled: a tonal button is the card's own color, and would show as bare text on it.
                    if (status is ReviewStatus.Ready) Button(onClick = { confirming = true }) { Text("Review") }
                    TextButton(onClick = onDismiss) { Text(if (status is ReviewStatus.Finished) "Done" else "Not now") }
                }
            }
        }
    }
    if (confirming && status is ReviewStatus.Ready) {
        ReviewConfirmDialog(status.pending, classifier, onConfirm = { confirming = false; onStart() }, onDismiss = { confirming = false })
    }
}

/** The Settings entry for the same review, always available. */
@Composable
fun ReviewSettingsRow(status: ReviewStatus, classifier: String, onStart: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Check older conversations") },
        supportingContent = {
            Column {
                Text(
                    when (status) {
                        ReviewStatus.Unknown -> "Counting…"
                        is ReviewStatus.Ready -> if (status.pending == 0) "Every conversation has been checked" else "${status.pending} not checked yet"
                        is ReviewStatus.Running -> "Checking ${status.done} of ${status.total}…"
                        is ReviewStatus.Finished -> summary(status, classifier)
                    },
                )
                if (status is ReviewStatus.Running) {
                    LinearProgressIndicator(
                        progress = { status.done / status.total.coerceAtLeast(1).toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        trailingContent = if (status is ReviewStatus.Ready && status.pending > 0) {
            { FilledTonalButton(onClick = { confirming = true }) { Text("Review") } }
        } else {
            null
        },
    )
    if (confirming && status is ReviewStatus.Ready) {
        ReviewConfirmDialog(status.pending, classifier, onConfirm = { confirming = false; onStart() }, onDismiss = { confirming = false })
    }
}

private fun summary(status: ReviewStatus.Finished, classifier: String): String =
    "Checked ${status.reviewed}: ${status.filtered} filtered, ${status.silenced} silenced." +
        if (status.unreached > 0) " Couldn't reach $classifier for the other ${status.unreached}: check them again when you're online (Settings → Check older conversations)." else ""
