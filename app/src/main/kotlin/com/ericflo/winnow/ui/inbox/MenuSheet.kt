package com.ericflo.winnow.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ericflo.winnow.R
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete

/** Full-screen menu behind the header button, laid out like the Messages account sheet. */
@Composable
fun MenuSheet(
    state: InboxUiState,
    onDismiss: () -> Unit,
    onOpenFiltered: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenStarred: () -> Unit,
    onMarkAllRead: () -> Unit,
    scheduledCount: Int = 0,
    onOpenScheduled: () -> Unit = {},
    trashCount: Int = 0,
    onOpenTrash: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxSize()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                IdentityCard(state, onMakeDefault)
                MenuGroup {
                    MenuItem(painterResource(R.drawable.ic_shield), "Filtered", trailing = state.filteredCount.takeIf { it > 0 }?.toString(), onClick = onOpenFiltered)
                    MenuDivider()
                    MenuItem(painterResource(R.drawable.ic_archive), "Archived", trailing = state.archivedCount.takeIf { it > 0 }?.toString(), onClick = onOpenArchived)
                    MenuDivider()
                    MenuItem(rememberVectorPainter(Icons.Outlined.Star), "Starred", onClick = onOpenStarred)
                    MenuDivider()
                    // Only while there's something scheduled, so it doesn't crowd the menu otherwise.
                    if (scheduledCount > 0) {
                        MenuItem(rememberVectorPainter(Icons.Filled.DateRange), "Scheduled", trailing = scheduledCount.toString(), onClick = onOpenScheduled)
                        MenuDivider()
                    }
                    if (trashCount > 0) {
                        MenuItem(rememberVectorPainter(Icons.Filled.Delete), "Recently deleted", trailing = trashCount.toString(), onClick = onOpenTrash)
                        MenuDivider()
                    }
                    MenuItem(rememberVectorPainter(Icons.Outlined.CheckCircle), "Mark all as read", onClick = onMarkAllRead)
                }
                MenuGroup {
                    MenuItem(painterResource(R.drawable.ic_insights), "Activity", onClick = onOpenActivity)
                    MenuDivider()
                    MenuItem(rememberVectorPainter(Icons.Outlined.Settings), "Winnow settings", onClick = onOpenSettings)
                }
            }
        }
    }
}

@Composable
private fun IdentityCard(state: InboxUiState, onMakeDefault: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, shape = RoundedCornerShape(36.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp)) {
            Box(Modifier.size(64.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_notification),
                    contentDescription = null,
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Winnow", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (state.isDefault) "Your SMS app" else "Not your SMS app yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Surface(color = colors.secondaryContainer, shape = CircleShape, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        state.classifier,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (!state.isDefault) {
                    FilledTonalButton(onClick = onMakeDefault, modifier = Modifier.padding(top = 8.dp)) { Text("Set as default") }
                }
            }
        }
    }
}

@Composable
private fun MenuGroup(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(36.dp), modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh)
}

@Composable
private fun MenuItem(icon: Painter, label: String, trailing: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).height(72.dp).padding(horizontal = 24.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
