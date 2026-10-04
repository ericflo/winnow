package com.ericflo.winnow.ui.metrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ericflo.winnow.classifier.local.CalibrationBin
import com.ericflo.winnow.classifier.local.CurvePoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** A reference line drawn behind a curve: chance for ROC, prevalence for precision–recall. */
sealed interface Baseline {
    data object Diagonal : Baseline
    data class Horizontal(val y: Double) : Baseline
}

/** A labeled dot on a curve, in data coordinates. */
data class Marker(val x: Double, val y: Double, val label: String)

private val AxisPadStart = 40.dp
private val AxisPadBottom = 46.dp
private val AxisPadTop = 26.dp
private val AxisPadEnd = 12.dp

/**
 * A ROC or precision–recall curve: it draws itself in, fills the area under it, and follows
 * a finger. Tapping or dragging selects the nearest point, which the caller shows in words.
 */
@Composable
fun CurveChart(
    points: List<CurvePoint>,
    xRange: ClosedFloatingPointRange<Double>,
    yRange: ClosedFloatingPointRange<Double>,
    baseline: Baseline,
    marker: Marker?,
    selected: CurvePoint?,
    onSelect: (CurvePoint) -> Unit,
    xLabel: String,
    yLabel: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val axisStyle = MaterialTheme.typography.labelMedium.copy(color = colors.onSurfaceVariant)
    val markerStyle = MaterialTheme.typography.labelMedium.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
    }

    fun nearest(plot: Rect, px: Float): CurvePoint? {
        val x = xRange.start + (px - plot.left) / plot.width * (xRange.endInclusive - xRange.start)
        return points.filter { it.x in xRange && it.y in yRange }.minByOrNull { abs(it.x - x) } ?: points.minByOrNull { abs(it.x - x) }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.05f)
            .semantics { contentDescription = description }
            .pointerInput(points, xRange, yRange) {
                detectTapGestures { pos -> nearest(plotRect(Size(size.width.toFloat(), size.height.toFloat())), pos.x)?.let(onSelect) }
            }
            .pointerInput(points, xRange, yRange) {
                detectHorizontalDragGestures { change, _ ->
                    nearest(plotRect(Size(size.width.toFloat(), size.height.toFloat())), change.position.x)?.let(onSelect)
                }
            },
    ) {
        val plot = plotRect(size)
        fun px(x: Double) = plot.left + ((x - xRange.start) / (xRange.endInclusive - xRange.start)).toFloat() * plot.width
        fun py(y: Double) = plot.bottom - ((y - yRange.start) / (yRange.endInclusive - yRange.start)).toFloat() * plot.height

        drawGrid(plot, xRange, yRange, measurer, tickStyle, colors.outlineVariant)
        drawAxisTitles(plot, xLabel, yLabel, measurer, axisStyle)

        clipRect(plot.left, plot.top, plot.right, plot.bottom) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
            when (baseline) {
                Baseline.Diagonal -> drawLine(colors.outline, Offset(px(0.0), py(0.0)), Offset(px(1.0), py(1.0)), 1.5.dp.toPx(), pathEffect = dash)
                is Baseline.Horizontal -> drawLine(colors.outline, Offset(plot.left, py(baseline.y)), Offset(plot.right, py(baseline.y)), 1.5.dp.toPx(), pathEffect = dash)
            }

            val line = Path()
            points.forEachIndexed { i, p -> if (i == 0) line.moveTo(px(p.x), py(p.y)) else line.lineTo(px(p.x), py(p.y)) }
            val area = Path().apply {
                addPath(line)
                lineTo(px(points.last().x), plot.bottom)
                lineTo(px(points.first().x), plot.bottom)
                close()
            }
            clipRect(plot.left, plot.top, plot.left + plot.width * reveal.value, plot.bottom) {
                drawPath(area, Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.38f), colors.primary.copy(alpha = 0.02f)), plot.top, plot.bottom))
                // A soft glow under the line: two wide, faint strokes.
                drawPath(line, colors.primary.copy(alpha = 0.12f), style = Stroke(12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(line, colors.primary.copy(alpha = 0.25f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(line, colors.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            selected?.let { s ->
                val x = px(s.x)
                drawLine(colors.onSurfaceVariant.copy(alpha = 0.6f), Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())
                drawRingedDot(Offset(x, py(s.y)), colors.onSurface, colors.surfaceContainer, 5.dp)
            }
        }

        marker?.let { m ->
            if (m.x in xRange && m.y in yRange && reveal.value > 0.95f) {
                val center = Offset(px(m.x), py(m.y))
                drawRingedDot(center, colors.tertiary, colors.surfaceContainer, 7.dp)
                val text = measurer.measure(m.label, markerStyle)
                // Right of the dot unless that runs off the plot; below it unless that does.
                val right = center.x + 12.dp.toPx()
                val left = if (right + text.size.width <= plot.right) right else center.x - 12.dp.toPx() - text.size.width
                val below = center.y + 8.dp.toPx()
                val top = if (below + text.size.height <= plot.bottom) below else center.y - 8.dp.toPx() - text.size.height
                drawText(text, topLeft = Offset(left, top))
            }
        }
    }
}

/** Where a chart's plot sits inside its canvas, leaving room for tick labels. Shared by drawing and gestures. */
private fun Density.plotRect(size: Size): Rect =
    Rect(AxisPadStart.toPx(), AxisPadTop.toPx(), size.width - AxisPadEnd.toPx(), size.height - AxisPadBottom.toPx())

private fun DrawScope.drawRingedDot(center: Offset, fill: Color, ring: Color, radius: Dp) {
    drawCircle(ring, radius.toPx() + 2.5.dp.toPx(), center)
    drawCircle(fill, radius.toPx(), center)
}

private fun DrawScope.drawGrid(
    plot: Rect,
    xRange: ClosedFloatingPointRange<Double>,
    yRange: ClosedFloatingPointRange<Double>,
    measurer: TextMeasurer,
    style: TextStyle,
    color: Color,
) {
    for (i in 0..4) {
        val f = i / 4f
        val x = plot.left + plot.width * f
        val y = plot.bottom - plot.height * f
        drawLine(color, Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())
        drawLine(color, Offset(plot.left, y), Offset(plot.right, y), 1.dp.toPx())
        val xLabel = measurer.measure(percent(xRange.start + (xRange.endInclusive - xRange.start) * f), style)
        drawText(xLabel, topLeft = Offset((x - xLabel.size.width / 2f).coerceIn(plot.left - 8.dp.toPx(), plot.right - xLabel.size.width), plot.bottom + 4.dp.toPx()))
        val yLabel = measurer.measure(percent(yRange.start + (yRange.endInclusive - yRange.start) * f), style)
        drawText(yLabel, topLeft = Offset(plot.left - yLabel.size.width - 6.dp.toPx(), y - yLabel.size.height / 2f))
    }
}

private fun DrawScope.drawAxisTitles(plot: Rect, xLabel: String, yLabel: String, measurer: TextMeasurer, style: TextStyle) {
    val x = measurer.measure(xLabel, style)
    drawText(x, topLeft = Offset(plot.center.x - x.size.width / 2f, size.height - x.size.height))
    // The y title sits above the plot, reading across, so it never needs rotating or covers data.
    val y = measurer.measure("↑ $yLabel", style)
    drawText(y, topLeft = Offset(plot.left - 6.dp.toPx(), 0f))
}

internal fun percent(x: Double): String = "${(x * 100).roundToInt()}%"

/** A 270° gauge for a score between 0 and 1, sweeping in once. */
@Composable
fun ScoreGauge(value: Double, label: String, modifier: Modifier = Modifier, size: Dp = 156.dp, format: (Float) -> String = { "%.3f".format(it) }) {
    val colors = MaterialTheme.colorScheme
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(value) { sweep.animateTo(value.toFloat(), tween(1400, easing = FastOutSlowInEasing)) }
    Box(modifier.size(size).semantics { contentDescription = "$label ${format(value.toFloat())}" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(colors.surfaceContainerHighest, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(colors.tertiary, colors.primary, colors.tertiary), center),
                135f, 270f * sweep.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(format(sweep.value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

/**
 * Reliability diagram: for each confidence bin, how often the model was right. Bars on the
 * dashed diagonal mean "90% sure" really is right 90% of the time. Thin bins are faded,
 * since a handful of texts says little.
 */
@Composable
fun CalibrationChart(bins: List<CalibrationBin>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val axisStyle = MaterialTheme.typography.labelMedium.copy(color = colors.onSurfaceVariant)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(bins) { grow.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    val described = bins.filter { it.count > 0 }.joinToString { "${percent(it.from)}–${percent(it.to)} sure: right ${percent(it.accuracy)} of ${it.count}" }
    Canvas(modifier.fillMaxWidth().aspectRatio(1.25f).semantics { contentDescription = "Calibration. $described" }) {
        val plot = plotRect(size)
        drawGrid(plot, 0.0..1.0, 0.0..1.0, measurer, tickStyle, colors.outlineVariant)
        drawAxisTitles(plot, "How sure it was →", "How often it was right", measurer, axisStyle)
        drawLine(colors.outline, Offset(plot.left, plot.bottom), Offset(plot.right, plot.top), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())))
        val maxCount = bins.maxOf { it.count }.coerceAtLeast(1)
        val gap = 3.dp.toPx()
        bins.filter { it.count > 0 }.forEach { b ->
            val left = plot.left + plot.width * b.from.toFloat() + gap / 2
            val width = plot.width * (b.to - b.from).toFloat() - gap
            val height = plot.height * b.accuracy.toFloat() * grow.value
            val weight = (0.35f + 0.65f * (b.count / maxCount.toFloat()).coerceAtLeast(0.15f)).coerceAtMost(1f)
            drawRoundRect(
                colors.primary.copy(alpha = weight),
                topLeft = Offset(left, plot.bottom - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            // Where the bar would be if confidence were perfectly honest.
            val ideal = plot.bottom - plot.height * b.confidence.toFloat()
            drawLine(colors.tertiary, Offset(left, ideal), Offset(left + width, ideal), 2.dp.toPx(), cap = StrokeCap.Round)
            val n = measurer.measure("${b.count}", tickStyle)
            drawText(n, topLeft = Offset(left + width / 2 - n.size.width / 2, (plot.bottom - height - n.size.height - 2.dp.toPx()).coerceAtLeast(plot.top)))
        }
    }
}
