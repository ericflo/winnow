package com.ericflo.winnow.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Google Messages, the only app Android lets use RCS. */
const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"

/**
 * Says plainly what RCS means for Winnow, and what to do about it. Android gives RCS to Google
 * Messages alone, and only while it's the SMS app. While Winnow is, a message someone sends over
 * RCS isn't delivered at all: it waits with Google until Google Messages is the SMS app again.
 * One person's phone may send it as a text after a while; an RCS group chat's doesn't, so a
 * group's messages go missing until then.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RcsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val messages = remember { context.packageManager.getLaunchIntentForPackage(GOOGLE_MESSAGES) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RCS chats don't reach Winnow", style = MaterialTheme.typography.titleLarge)
            Text(
                "Android lets only Google Messages use RCS, and only while it's your SMS app. While Winnow is, a message someone sends you over RCS " +
                    "isn't delivered to your phone at all: it waits with Google until Google Messages is your SMS app again.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "One person's phone may send it again as a text after a while, which Winnow does get. A group chat that's RCS doesn't: its messages " +
                    "wait, and you won't see them anywhere until you switch back.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("So that nothing waits", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. Make Google Messages your SMS app for a moment, and open it: anything waiting arrives.\n" +
                            "2. In Google Messages, open Settings, then RCS chats, and turn RCS off. People's phones then send you texts and picture " +
                            "messages instead, group chats too, which Winnow receives.\n" +
                            "3. Make Winnow your SMS app again. Everything that arrived meanwhile shows here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Google can take a while to notice RCS is off: until it does, some messages may still go the RCS way.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } }) { Text("Choose the SMS app") }
                if (messages != null) OutlinedButton(onClick = { runCatching { context.startActivity(messages) } }) { Text("Open Google Messages") }
            }
            Text(
                "Winnow can't receive RCS itself: Android has no way for another app to. It can tell which conversations were RCS (Google Messages leaves " +
                    "them in the phone's store with RCS addresses), and marks them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A conversation that was RCS: new messages in it may not reach Winnow. */
@Composable
fun RcsBanner(onWhy: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text("An RCS chat: new messages may not reach Winnow", style = MaterialTheme.typography.titleSmall)
            Text(
                "Its messages came through Google Messages over RCS. While Winnow is your SMS app, new ones wait with Google instead of arriving.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
            )
            // In the banner's own ink: the app's accent reads poorly on it.
            val ink = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, colors = ink) { Text("Got it") }
                TextButton(onClick = onWhy, colors = ink) { Text("What to do", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Gives someone Winnow can't name (an RCS member) a name, shown wherever they appear. */
@Composable
fun NamePersonDialog(current: String?, label: String, onName: (String?) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who is this?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Google Messages left only an RCS id for $label, not a number, so Winnow can't find them in your contacts. " +
                        "Give them a name: only you see it, on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
            }
        },
        confirmButton = { TextButton(onClick = { onName(name.trim().ifEmpty { null }) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onName(null) }) { Text("Remove name") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
