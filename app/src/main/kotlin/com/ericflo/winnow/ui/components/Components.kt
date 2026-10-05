package com.ericflo.winnow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ericflo.winnow.R
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.Member
import com.ericflo.winnow.data.showsInitial
import androidx.compose.ui.graphics.Color
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.data.StoredVerdict
import com.ericflo.winnow.ui.theme.avatarColors
import com.ericflo.winnow.ui.theme.categoryColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import androidx.compose.ui.text.TextStyle

/**
 * A group's avatar: two of its people, overlapping, as in Messages. With fewer than two known
 * (an older list), the group glyph. [ring] is what's behind it, so the front face stands apart.
 */
@Composable
fun GroupAvatar(members: List<Member>, size: Dp = 52.dp, modifier: Modifier = Modifier, ring: Color = MaterialTheme.colorScheme.surface) {
    if (members.size < 2) {
        Box(modifier.size(size).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_group), contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(size * 0.5f))
        }
        return
    }
    val small = size * 0.66f
    Box(modifier.size(size).clearAndSetSemantics {}) {
        // The first in front (see groupFaces), the second peeking out behind it.
        val (front, back) = members
        Avatar(back.name, seed = back.address, size = small, photoUri = back.photoUri, modifier = Modifier.align(Alignment.TopStart))
        // The front one ringed in the background color, so the two read as separate faces.
        Box(
            Modifier.align(Alignment.BottomEnd).size(small + 3.dp).background(ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Avatar(front.name, seed = front.address, size = small, photoUri = front.photoUri) }
    }
}

/**
 * The contact's photo when there is one; otherwise a colored initial for named senders and a
 * neutral person glyph for bare numbers, like Messages.
 */
@Composable
fun Avatar(name: String, seed: String, size: Dp = 52.dp, modifier: Modifier = Modifier, photoUri: String? = null) {
    val named = showsInitial(name)
    val (container, content) = if (named) {
        avatarColors(seed)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Decorative: the name is always written beside it, and a lone "P" read aloud is noise.
    Box(modifier = modifier.size(size).background(container, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (named) {
            Text(name.first().uppercase(), color = content, fontSize = (size.value * 0.42f).sp, style = MaterialTheme.typography.titleMedium)
        } else {
            Icon(Icons.Filled.Person, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
        }
        // Drawn over the initial, so a missing or unreadable photo just shows the initial. At
        // this size and up the 96-pixel thumbnail is soft: the full photo instead, if there is one.
        if (photoUri != null) {
            // Back to the thumbnail if the full photo won't load (it was removed since, say); a
            // new full photo for the same contact is tried afresh.
            var failed by remember(photoUri) { mutableStateOf<String?>(null) }
            val large = ContactLookup.displayPhoto(photoUri)?.takeIf { size >= SHARP_PHOTO_SIZE && it != failed }
            AsyncImage(
                model = large ?: photoUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { if (large != null) failed = large },
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

/** At this size and up, an avatar shows the contact's full photo rather than its thumbnail. */
private val SHARP_PHOTO_SIZE = 44.dp

/** The Spam & blocked list's leading icon: a red "!" for spam, a block sign for everything else filtered. */
@Composable
fun FilteredAvatar(category: Category?, size: Dp = 52.dp) {
    val fraud = category == Category.SPAM
    val c = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(size).background(if (fraud) c.errorContainer else c.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (fraud) {
            Text("!", color = c.onErrorContainer, fontWeight = FontWeight.Black, fontSize = (size.value * 0.45f).sp)
        } else {
            Icon(painterResource(R.drawable.ic_block), contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(size * 0.55f))
        }
    }
}

@Composable
fun UnreadCountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(20.dp, 20.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clearAndSetSemantics { contentDescription = "$count unread" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/** "Spam · 98%" or "Marketing · silenced". */
@Composable
fun VerdictBadge(verdict: StoredVerdict, modifier: Modifier = Modifier) {
    val (container, content) = categoryColors(verdict.category)
    val label = verdict.label
    val detail = when {
        verdict.labeledByUser -> "your label"
        verdict.userAction != null -> "your call"
        verdict.effectiveAction == Action.SILENCE -> "silenced"
        verdict.confidence < 1.0 -> "${(verdict.confidence * 100).toInt()}%"
        else -> null
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(
            text = if (detail != null) "$label · $detail" else label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val weekdayFormat = DateTimeFormatter.ofPattern("EEE")
private val monthDayFormat = DateTimeFormatter.ofPattern("MMM d")
private val fullDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)

/** Conversation-list time: "Now", "6 min", a time today, a weekday this week, a date otherwise. */
fun shortTimestamp(epochMillis: Long, now: Long = System.currentTimeMillis(), relative: Boolean = true): String {
    val minutes = (now - epochMillis) / 60_000
    if (relative && minutes in 0..59) return if (minutes == 0L) "Now" else "$minutes min"
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)
    return when {
        days <= 0 -> at.format(timeFormat)
        days < 7 -> at.format(weekdayFormat)
        at.year == today.year -> at.format(monthDayFormat)
        else -> at.format(fullDateFormat)
    }
}

/** Centered header inside a conversation: "Today • 2:25 PM", "Thursday, Sep 17 • 2:25 AM". */
fun headerLabel(epochMillis: Long, today: LocalDate = LocalDate.now()): String {
    val at = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val date = at.toLocalDate()
    val day = when (ChronoUnit.DAYS.between(date, today)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEEE, MMM d" else "EEEE, MMM d, yyyy"))
    }
    return "$day • ${at.format(timeFormat)}"
}

/** This style at [scale] times its size: message text under the user's text-size setting. */
fun TextStyle.scaled(scale: Float): TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

fun timeOfDay(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFormat)
