package com.ericflo.winnow.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.ui.components.Avatar
import com.ericflo.winnow.ui.components.UnreadCountBadge
import com.ericflo.winnow.ui.components.VerdictBadge
import com.ericflo.winnow.ui.components.shortTimestamp

/** One conversation, laid out like Messages: avatar, name over a one-line snippet, time over an unread count. */
@Composable
fun ConversationRow(
    conversation: ConversationSummary,
    showVerdict: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit = { Avatar(conversation.displayName, seed = conversation.address) },
) {
    val unread = conversation.unread
    val colors = MaterialTheme.colorScheme
    val badge = conversation.verdict?.takeIf { showVerdict }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        leading()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                conversation.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                conversation.snippet,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                color = if (unread) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (badge != null) VerdictBadge(badge, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(12.dp))
        // Pin the time beside the name, as Messages does, even when a verdict badge adds a third line.
        Column(
            horizontalAlignment = Alignment.End,
            modifier = if (badge != null) Modifier.align(Alignment.Top).padding(top = 4.dp) else Modifier,
        ) {
            Text(
                shortTimestamp(conversation.timestamp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                color = if (unread) colors.onSurface else colors.onSurfaceVariant,
            )
            if (unread) {
                Spacer(Modifier.height(6.dp))
                UnreadCountBadge(conversation.unreadCount)
            }
        }
    }
}
