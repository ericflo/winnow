package com.ericflo.winnow.ui.thread

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.ericflo.winnow.data.PhotoCrop
import kotlin.math.roundToInt

/**
 * Edits a photo in the composer: crop it (drag the corners or edges, or the box itself) and draw
 * on it. [width] and [height] are the photo's upright size, which is how it's shown and what
 * [onDone]'s box and strokes are in.
 */
@Composable
fun CropEditor(uri: String, width: Int, height: Int, onDone: (PhotoCrop.Box, List<PhotoCrop.Stroke>) -> Unit, onDismiss: () -> Unit) {
    var box by remember(uri) { mutableStateOf(PhotoCrop.Box.FULL) }
    // A square, in fractions of this photo: as much of its width as of its height times height/width.
    val squareAspect = height.toFloat() / width
    var square by remember(uri) { mutableStateOf(false) }
    var drawing by remember(uri) { mutableStateOf(false) }
    val strokes = remember(uri) { mutableStateListOf<PhotoCrop.Stroke>() }
    var color by remember { mutableIntStateOf(PEN_COLORS.first().second.toArgb()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White) }
                Text("Edit photo", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onDone(box, strokes.toList()) }, enabled = box != PhotoCrop.Box.FULL || strokes.isNotEmpty()) { Text("Done") }
            }
            // Inset from the screen's sides, where a drag would be the back gesture.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val margin = with(density) { REACH.toPx() }
                // The photo as big as fits with a finger's reach around it, which takes the handles too.
                val scale = minOf((constraints.maxWidth - 2 * margin) / width, (constraints.maxHeight - 2 * margin) / height)
                val shownWidth = width * scale + 2 * margin
                val shownHeight = height * scale + 2 * margin
                Box(Modifier.size(with(density) { shownWidth.roundToInt().toDp() }, with(density) { shownHeight.roundToInt().toDp() })) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Photo being edited",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.padding(REACH).fillMaxSize(),
                    )
                    // Under the crop box, so lines outside it are dimmed with the rest; each layer
                    // takes touches only in its own mode, so the one beneath still gets them.
                    DrawLayer(
                        strokes = strokes,
                        color = color,
                        enabled = drawing,
                        onStroke = { strokes += it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    CropOverlay(
                        box = box,
                        aspect = if (square) squareAspect else null,
                        enabled = !drawing,
                        onBox = { box = it },
                        modifier = Modifier.fillMaxSize().semantics {
                            contentDescription = "Crop area"
                            stateDescription = "${(box.width * 100).roundToInt()}% wide, ${(box.height * 100).roundToInt()}% high"
                        },
                    )
                }
            }
            val chipColors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedLabelColor = Color.White)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            ) {
                if (drawing) {
                    PEN_COLORS.forEach { (name, swatch) ->
                        val selected = swatch.toArgb() == color
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (selected) Color.White else Color.Transparent)
                                .padding(if (selected) 3.dp else 4.dp)
                                .clip(CircleShape)
                                // A dark pen shows against the black around it.
                                .background(Color.Gray)
                                .padding(1.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .selectable(selected = selected, role = Role.RadioButton) { color = swatch.toArgb() }
                                .semantics { contentDescription = name },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) { Text("Undo") }
                } else {
                    FilterChip(selected = !square, onClick = { square = false }, label = { Text("Free") }, colors = chipColors)
                    FilterChip(
                        selected = square,
                        onClick = {
                            square = true
                            box = PhotoCrop.fit(box, squareAspect)
                        },
                        label = { Text("Square") },
                        colors = chipColors,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { box = if (square) PhotoCrop.fit(PhotoCrop.Box.FULL, squareAspect) else PhotoCrop.Box.FULL }) { Text("Reset") }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                listOf(false to "Crop", true to "Draw").forEachIndexed { i, (draws, label) ->
                    SegmentedButton(
                        selected = drawing == draws,
                        onClick = { drawing = draws },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        colors = SegmentedButtonDefaults.colors(inactiveContentColor = Color.White, inactiveContainerColor = Color.Black),
                    ) { Text(label) }
                }
            }
        }
    }
}

/** The pens: a name for TalkBack, and the color. */
private val PEN_COLORS = listOf(
    "Red" to Color(0xFFF44336),
    "Yellow" to Color(0xFFFFEB3B),
    "Green" to Color(0xFF4CAF50),
    "Blue" to Color(0xFF2196F3),
    "White" to Color.White,
    "Black" to Color.Black,
)

/** A pen line's width, on the screen; on the photo it's the same share of its width. */
private val PEN_WIDTH = 5.dp

/**
 * What's drawn on the photo, and (when [enabled]) a finger drawing more: each line goes to
 * [onStroke] when the finger lifts. Like [CropOverlay], it's [REACH] bigger than the photo all round.
 */
@Composable
private fun DrawLayer(strokes: List<PhotoCrop.Stroke>, color: Int, enabled: Boolean, onStroke: (PhotoCrop.Stroke) -> Unit, modifier: Modifier = Modifier) {
    val reach = with(LocalDensity.current) { REACH.toPx() }
    val pen = with(LocalDensity.current) { PEN_WIDTH.toPx() }
    val drawing = remember { mutableStateListOf<PhotoCrop.Point>() }
    val currentColor by rememberUpdatedState(color)
    // Only while drawing does it take touches at all: otherwise they go to the crop box beneath.
    Canvas(
        modifier.then(if (!enabled) Modifier else Modifier.pointerInput(Unit) {
            val w = size.width - 2 * reach
            val h = size.height - 2 * reach
            fun at(o: Offset) = PhotoCrop.Point(((o.x - reach) / w).coerceIn(0f, 1f), ((o.y - reach) / h).coerceIn(0f, 1f))
            detectDragGestures(
                onDragStart = { drawing.clear(); drawing += at(it) },
                onDragEnd = {
                    if (drawing.isNotEmpty()) onStroke(PhotoCrop.Stroke(drawing.toList(), currentColor, pen / w))
                    drawing.clear()
                },
                onDragCancel = { drawing.clear() },
            ) { change, _ ->
                change.consume()
                drawing += at(change.position)
            }
        }),
    ) {
        val w = size.width - 2 * reach
        val h = size.height - 2 * reach
        fun line(points: List<PhotoCrop.Point>, argb: Int, width: Float) {
            if (points.isEmpty()) return
            val path = Path().apply {
                moveTo(reach + points[0].x * w, reach + points[0].y * h)
                points.drop(1).forEach { lineTo(reach + it.x * w, reach + it.y * h) }
                if (points.size == 1) lineTo(reach + points[0].x * w + 0.1f, reach + points[0].y * h)
            }
            drawPath(path, Color(argb), style = Stroke(width = width * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        strokes.forEach { line(it.points, it.argb, it.width) }
        line(drawing, currentColor, pen / w)
    }
}

/** How far from a handle a finger can be and still take it; the photo has this much room around it. */
private val REACH = 28.dp

/**
 * The box over the photo: the rest dimmed, thirds marked, corners drawn as handles. It's
 * [REACH] bigger than the photo all round, so a handle on the photo's edge can be taken from outside.
 */
@Composable
private fun CropOverlay(box: PhotoCrop.Box, aspect: Float?, enabled: Boolean, onBox: (PhotoCrop.Box) -> Unit, modifier: Modifier = Modifier) {
    val current = remember { mutableStateOf(box) }
    current.value = box
    val reach = with(LocalDensity.current) { REACH.toPx() }
    val handleLength = with(LocalDensity.current) { 22.dp.toPx() }
    val handleWidth = with(LocalDensity.current) { 4.dp.toPx() }
    val line = with(LocalDensity.current) { 1.5.dp.toPx() }
    BoxWithConstraints(modifier) {
    // Around the handles on the box's sides, drags are the box's, not Android's back gesture.
    // Six 56dp squares: within the 200dp a side Android allows.
    val zone = 56.dp
    val zonePx = with(LocalDensity.current) { zone.toPx() }
    val photoWidth = constraints.maxWidth - 2 * reach
    val photoHeight = constraints.maxHeight - 2 * reach
    for (x in listOf(box.left, box.right)) {
        for (y in listOf(box.top, (box.top + box.bottom) / 2, box.bottom)) {
            Box(
                Modifier
                    .offset { IntOffset((reach + x * photoWidth - zonePx / 2).roundToInt(), (reach + y * photoHeight - zonePx / 2).roundToInt()) }
                    .size(zone)
                    .systemGestureExclusion(),
            )
        }
    }
    Canvas(
        Modifier.fillMaxSize().then(if (!enabled) Modifier else Modifier.pointerInput(aspect) {
            var handle: PhotoCrop.Handle? = null
            val w = size.width - 2 * reach
            val h = size.height - 2 * reach
            detectDragGestures(
                onDragStart = { at ->
                    handle = PhotoCrop.handleAt(current.value, (at.x - reach) / w, (at.y - reach) / h, reach / w, reach / h)
                },
                onDragEnd = { handle = null },
                onDragCancel = { handle = null },
            ) { change, amount ->
                val held = handle ?: return@detectDragGestures
                change.consume()
                // At least a finger's width either way, however big the photo.
                val min = (2 * reach / minOf(w, h)).coerceAtMost(0.5f)
                val next = PhotoCrop.drag(current.value, held, amount.x / w, amount.y / h, min, aspect)
                current.value = next
                onBox(next)
            }
        }),
    ) {
        // The photo's area, inside the reach around it.
        val photoLeft = reach
        val photoTop = reach
        val photoRight = size.width - reach
        val photoBottom = size.height - reach
        val left = photoLeft + box.left * (photoRight - photoLeft)
        val top = photoTop + box.top * (photoBottom - photoTop)
        val right = photoLeft + box.right * (photoRight - photoLeft)
        val bottom = photoTop + box.bottom * (photoBottom - photoTop)
        val dim = Color.Black.copy(alpha = 0.6f)
        drawRect(dim, Offset(photoLeft, photoTop), Size(photoRight - photoLeft, top - photoTop))
        drawRect(dim, Offset(photoLeft, bottom), Size(photoRight - photoLeft, photoBottom - bottom))
        drawRect(dim, Offset(photoLeft, top), Size(left - photoLeft, bottom - top))
        drawRect(dim, Offset(right, top), Size(photoRight - right, bottom - top))
        val faint = Color.White.copy(alpha = 0.45f)
        for (i in 1..2) {
            val x = left + (right - left) * i / 3
            val y = top + (bottom - top) * i / 3
            drawLine(faint, Offset(x, top), Offset(x, bottom), strokeWidth = line / 1.5f)
            drawLine(faint, Offset(left, y), Offset(right, y), strokeWidth = line / 1.5f)
        }
        drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(line))
        val corners = listOf(Offset(left, top) to Offset(1f, 1f), Offset(right, top) to Offset(-1f, 1f), Offset(left, bottom) to Offset(1f, -1f), Offset(right, bottom) to Offset(-1f, -1f))
        for ((corner, inward) in corners) {
            drawLine(Color.White, corner, corner + Offset(inward.x * handleLength, 0f), strokeWidth = handleWidth)
            drawLine(Color.White, corner, corner + Offset(0f, inward.y * handleLength), strokeWidth = handleWidth)
        }
    }
    }
}
