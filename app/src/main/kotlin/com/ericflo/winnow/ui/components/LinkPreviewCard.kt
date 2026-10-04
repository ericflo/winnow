package com.ericflo.winnow.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ericflo.winnow.data.LinkPreview
import java.io.File

/**
 * A link's title, site and picture under the message that has it. Shows nothing until (and
 * unless) a preview loads. Tapping opens the link.
 */
@Composable
fun LinkPreviewCard(url: String, outgoing: Boolean, load: suspend (String) -> LinkPreview?, onClick: (() -> Unit)? = null, onLongClick: () -> Unit) {
    val preview by produceState<LinkPreview?>(null, url) { value = runCatching { load(url) }.getOrNull() }
    val p = preview ?: return
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (outgoing) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (outgoing) colors.onPrimaryContainer else colors.onSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .widthIn(max = 280.dp)
            .combinedClickable(
                onClickLabel = "Open link",
                onClick = onClick ?: { runCatching { uriHandler.openUri(p.url) }; Unit },
                onLongClick = onLongClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = "Link: ${p.title}, ${p.site}" },
    ) {
        Column {
            p.imagePath?.let { path ->
                AsyncImage(
                    model = File(path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.91f),
                )
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p.site, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
        }
    }
}
