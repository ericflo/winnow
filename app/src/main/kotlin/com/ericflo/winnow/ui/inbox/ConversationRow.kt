package com.ericflo.winnow.ui.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ericflo.winnow.R
import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.ui.components.Avatar
import com.ericflo.winnow.ui.components.GroupAvatar
import com.ericflo.winnow.ui.components.UnreadCountBadge
import com.ericflo.winnow.ui.components.VerdictBadge
import com.ericflo.winnow.ui.components.shortTimestamp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** One conversation, laid out like Messages: avatar, name over a one-line snippet, time over an unread count. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(
    conversation: ConversationSummary,
    showVerdict: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /** The conversation open in the other pane of a two-pane layout. */
    highlighted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    // A group's front face is ringed in the row's own color: a highlighted row's isn't the surface.
    leading: @Composable () -> Unit = {
        ConversationAvatar(conversation, ring = if (highlighted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface)
    },
    trailing: (@Composable () -> Unit)? = null,
) {
    val unread = conversation.unread
    val colors = MaterialTheme.colorScheme
    val badge = conversation.verdict?.takeIf { showVerdict }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(
                when {
                    selected -> colors.secondaryContainer
                    highlighted -> colors.surfaceContainerHighest
                    else -> colors.surface
                },
            )
            .combinedClickable(onClick = onClick, onLongClickLabel = if (onLongClick != null) "Select" else null, onLongClick = onLongClick)
            .semantics { if (selected) stateDescription = "Selected" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (selected) SelectedAvatar() else leading()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                conversation.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val draft = conversation.draft
            Text(
                buildAnnotatedString {
                    if (draft != null) {
                        withStyle(SpanStyle(color = colors.error)) { append("Draft: ") }
                        append(draft)
                    } else if (conversation.notSent) {
                        // As Messages does: the newest text didn't go out, so say so first.
                        withStyle(SpanStyle(color = colors.error)) { append("Not sent: ") }
                        append(conversation.snippet.removePrefix("You: "))
                    } else {
                        append(conversation.snippet)
                    }
                },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.muted) StatusIcon(R.drawable.ic_muted, "Muted")
                if (conversation.pinned) StatusIcon(R.drawable.ic_pin, "Pinned")
                Text(
                    shortTimestamp(conversation.timestamp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    color = if (unread) colors.onSurface else colors.onSurfaceVariant,
                )
            }
            if (unread) {
                Spacer(Modifier.height(6.dp))
                UnreadCountBadge(conversation.unreadCount)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun StatusIcon(icon: Int, description: String) {
    Icon(
        painterResource(icon),
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 4.dp).size(16.dp),
    )
}

/** A person's avatar, or two of a group's people for group conversations. */
@Composable
fun ConversationAvatar(conversation: ConversationSummary, size: Dp = 52.dp, ring: Color = MaterialTheme.colorScheme.surface) {
    if (conversation.isGroup) {
        GroupAvatar(conversation.members, size, ring = ring)
    } else {
        Avatar(conversation.displayName, seed = conversation.address, size = size, photoUri = conversation.photoUri)
    }
}

@Composable
private fun SelectedAvatar() {
    Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary)
    }
}
