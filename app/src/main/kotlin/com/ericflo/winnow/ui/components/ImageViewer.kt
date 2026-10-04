package com.ericflo.winnow.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.ericflo.winnow.R
import com.ericflo.winnow.data.Attachment

/**
 * A conversation's photos, full screen. Swipe between them, pinch or double-tap to zoom, and
 * tap to show or hide Share and Save.
 */
@Composable
fun ImageViewer(
    images: List<Attachment>,
    start: Int,
    onDismiss: () -> Unit,
    onShare: (Attachment) -> Unit,
    onSave: (Attachment) -> Unit,
) {
    if (images.isEmpty()) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, images.lastIndex)) { images.size }
        var chrome by remember { mutableStateOf(true) }
        // A zoomed photo pans under one finger, so the pager mustn't take the swipe.
        var zoomed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed,
                // The index too: the same photo can be attached twice (sample conversations reuse one).
                key = { "$it:${images[it].uri}" },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableImage(
                    image = images[page],
                    onTap = { chrome = !chrome },
                    onZoomed = { if (page == pager.currentPage) zoomed = it },
                )
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(4.dp),
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                    Text(
                        if (images.size > 1) "${pager.currentPage + 1} of ${images.size}" else "",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onShare(images[pager.currentPage]) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                    }
                    IconButton(onClick = { onSave(images[pager.currentPage]) }) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = "Save to phone", tint = Color.White)
                    }
                }
            }
        }
    }
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * One photo that zooms around the fingers and pans within its edges. One finger on an
 * unzoomed photo isn't consumed, so the pager underneath still swipes.
 */
@Composable
private fun ZoomableImage(image: Attachment, onTap: () -> Unit, onZoomed: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(scale > 1f) { onZoomed(scale > 1f) }

    fun clamp(value: Offset, at: Float): Offset {
        val maxX = size.width * (at - 1) / 2
        val maxY = size.height * (at - 1) / 2
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    // Keeps the content point under [focus] where it is while the scale changes by [zoom].
    fun zoomAround(focus: Offset, zoom: Float) {
        val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        val applied = next / scale
        val center = Offset(size.width / 2f, size.height / 2f)
        offset = clamp((focus - center) * (1 - applied) + offset * applied, next)
        scale = next
    }

    AsyncImage(
        model = image.uri,
        contentDescription = image.name ?: "Photo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            zoomAround(tap, DOUBLE_TAP_ZOOM)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) zoomAround(event.calculateCentroid(useCurrent = true), zoom)
                            offset = clamp(offset + event.calculatePan(), scale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                    if (scale < 1.05f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
