package de.flkaiser.paanalyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

class PlotSeries(
    val frequencies: DoubleArray,
    val values: DoubleArray,
    val color: Color,
    val dashed: Boolean = false,
    val width: Float = 2f,
    /** Fills the area below the line with a gradient that fades out towards the bottom. */
    val fill: Boolean = false,
    /** How points that fall into the same pixel column are drawn (for dense FFT spectra). */
    val reduce: PlotReduce = PlotReduce.NONE,
)

enum class PlotReduce {
    /** Every point is drawn. */
    NONE,

    /** One point per pixel column: the highest, so narrow peaks stay visible and noise doesn't turn into a solid band. */
    MAX,
}

/** A labelled point on the chart, e.g. the strongest peak. */
class PlotMarker(val frequencyHz: Double, val value: Double, val label: String, val color: Color)

/** What the chart's gestures ask for. Ranges are owned by the caller. */
interface PlotGestures {
    fun cursor(frequencyHz: Double)
    /** Zoom factors per axis (> 1 = in) around an anchor given as fractions of the plot (x from left, y from bottom). */
    fun zoom(factorX: Double, factorY: Double, anchorX: Double, anchorY: Double)
    /** Pan by fractions of the plot size (x: positive = finger moved right, y: positive = finger moved down). */
    fun pan(fractionX: Double, fractionY: Double)
    fun reset()
}

/**
 * Line chart with a logarithmic frequency axis.
 * One finger: cursor. Two fingers: pinch to zoom (horizontal and vertical pinch
 * zoom their own axis) and drag to pan. Double tap: reset the view.
 */
@Composable
fun FrequencyPlot(
    series: List<PlotSeries>,
    xRange: ClosedFloatingPointRange<Double>,
    yRange: ClosedFloatingPointRange<Double>,
    cursorHz: Double?,
    gestures: PlotGestures,
    modifier: Modifier = Modifier,
    markers: List<PlotMarker> = emptyList(),
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val markerStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
    val markerBackground = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
    val cursorColor = MaterialTheme.colorScheme.primary
    val currentX by rememberUpdatedState(xRange)
    val currentGestures by rememberUpdatedState(gestures)

    Canvas(
        modifier
            .clipToBounds()
            .plotGestures({ currentX }, { currentGestures }, BOTTOM_MARGIN),
    ) {
        val left = LEFT_MARGIN.toPx()
        val bottom = BOTTOM_MARGIN.toPx()
        val w = size.width - left
        val h = size.height - bottom
        if (w <= 0f || h <= 0f) return@Canvas
        val minHz = xRange.start
        val maxHz = xRange.endInclusive

        for (v in PlotMath.valueTicks(yRange.start, yRange.endInclusive, target = (h / 40.dp.toPx()).toInt().coerceIn(3, 10))) {
            val y = PlotMath.valueToY(v, yRange.start, yRange.endInclusive, h)
            drawLine(gridColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
            val layout = textMeasurer.measure("%.0f".format(v), labelStyle)
            drawText(layout, topLeft = Offset(left - layout.size.width - 4.dp.toPx(), (y - layout.size.height / 2f).coerceIn(0f, (h - layout.size.height).coerceAtLeast(0f))))
        }
        val ticks = PlotMath.frequencyTicks(minHz, maxHz).let { if (it.size < 3) PlotMath.denseFrequencyTicks(minHz, maxHz) else it }
        for (f in ticks) {
            val x = left + PlotMath.frequencyToX(f, minHz, maxHz, w)
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            val layout = textMeasurer.measure(PlotMath.formatFrequency(f), labelStyle)
            drawText(layout, topLeft = Offset((x - layout.size.width / 2f).coerceIn(left, (size.width - layout.size.width).coerceAtLeast(left)), h + 2.dp.toPx()))
        }
        drawLine(axisColor, Offset(left, 0f), Offset(left, h), strokeWidth = 1.5f)
        drawLine(axisColor, Offset(left, h), Offset(size.width, h), strokeWidth = 1.5f)

        clipRect(left = left, top = 0f, right = size.width, bottom = h) {
            translate(left = left) {
                for (s in series) drawSeries(s, minHz, maxHz, yRange, w, h)
            }
        }
        cursorHz?.takeIf { it in xRange }?.let {
            val x = left + PlotMath.frequencyToX(it, minHz, maxHz, w)
            drawLine(cursorColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
        }
        for (m in markers) {
            if (m.frequencyHz !in xRange || !m.value.isFinite() || m.value !in yRange) continue
            val point = Offset(left + PlotMath.frequencyToX(m.frequencyHz, minHz, maxHz, w), PlotMath.valueToY(m.value, yRange.start, yRange.endInclusive, h))
            drawCircle(m.color, radius = 4.dp.toPx(), center = point, style = Stroke(width = 1.5.dp.toPx()))
            val layout = textMeasurer.measure(m.label, markerStyle)
            val pad = 4.dp.toPx()
            val boxW = layout.size.width + 2 * pad
            val boxH = layout.size.height + pad
            val above = point.y - 8.dp.toPx() - boxH
            val top = if (above >= 0f) above else point.y + 8.dp.toPx()
            val boxLeft = (point.x - boxW / 2).coerceIn(left, (size.width - boxW).coerceAtLeast(left))
            drawRoundRect(markerBackground, topLeft = Offset(boxLeft, top), size = Size(boxW, boxH), cornerRadius = CornerRadius(boxH / 2))
            drawRoundRect(m.color, topLeft = Offset(boxLeft, top), size = Size(boxW, boxH), cornerRadius = CornerRadius(boxH / 2), style = Stroke(width = 1.dp.toPx()))
            drawText(layout, topLeft = Offset(boxLeft + pad, top + pad / 2))
        }
    }
}

/**
 * Touch handling shared by the chart and the waterfall below it.
 * One finger: cursor. Two fingers: pinch to zoom and drag to pan. Double tap: reset.
 */
internal fun Modifier.plotGestures(
    xRange: () -> ClosedFloatingPointRange<Double>,
    gestures: () -> PlotGestures,
    bottomMargin: Dp,
): Modifier = pointerInput(Unit) {
    val left = PLOT_LEFT_MARGIN.toPx()
    val bottom = bottomMargin.toPx()
    fun plotWidth() = (size.width - left).coerceAtLeast(1f)
    fun plotHeight() = (size.height - bottom).coerceAtLeast(1f)
    fun frequencyAt(x: Float) = PlotMath.xToFrequency(x - left, xRange().start, xRange().endInclusive, plotWidth())
    var lastTap = 0L

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        gestures().cursor(frequencyAt(down.position.x))
        var multiTouch = false
        var moved = false
        var upTime = down.uptimeMillis
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                upTime = event.changes.firstOrNull()?.uptimeMillis ?: upTime
                break
            }
            if (pressed.size >= 2) {
                multiTouch = true
                pinch(pressed[0], pressed[1], left, plotWidth(), plotHeight(), gestures())
                event.changes.forEach { it.consume() }
            } else if (!multiTouch) {
                val c = pressed[0]
                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                gestures().cursor(frequencyAt(c.position.x))
                c.consume()
            }
        }
        val isTap = !multiTouch && !moved && upTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
        if (isTap) {
            if (upTime - lastTap < viewConfiguration.doubleTapTimeoutMillis) {
                gestures().reset()
                lastTap = 0L
            } else {
                lastTap = upTime
            }
        }
    }
}

private fun pinch(a: PointerInputChange, b: PointerInputChange, left: Float, width: Float, height: Float, gestures: PlotGestures) {
    val minSpread = 48f
    val dxNow = abs(a.position.x - b.position.x)
    val dxBefore = abs(a.previousPosition.x - b.previousPosition.x)
    val dyNow = abs(a.position.y - b.position.y)
    val dyBefore = abs(a.previousPosition.y - b.previousPosition.y)
    val zoomX = if (dxNow > minSpread && dxBefore > minSpread) (dxNow / dxBefore).toDouble() else 1.0
    val zoomY = if (dyNow > minSpread && dyBefore > minSpread) (dyNow / dyBefore).toDouble() else 1.0
    val centroid = (a.position + b.position) / 2f
    val previousCentroid = (a.previousPosition + b.previousPosition) / 2f
    if (zoomX != 1.0 || zoomY != 1.0) {
        gestures.zoom(
            zoomX,
            zoomY,
            ((centroid.x - left) / width).toDouble().coerceIn(0.0, 1.0),
            (1.0 - centroid.y / height).coerceIn(0.0, 1.0),
        )
    }
    val pan = centroid - previousCentroid
    if (pan.x != 0f || pan.y != 0f) gestures.pan((pan.x / width).toDouble(), (pan.y / height).toDouble())
}

private fun DrawScope.drawSeries(
    s: PlotSeries,
    minHz: Double,
    maxHz: Double,
    range: ClosedFloatingPointRange<Double>,
    w: Float,
    h: Float,
) {
    val path = Path()
    val fillPath = if (s.fill) Path() else null
    var penDown = false
    var segmentStart = 0f
    var lastX = 0f
    fun closeFill() {
        if (penDown && fillPath != null) {
            fillPath.lineTo(lastX, h)
            fillPath.lineTo(segmentStart, h)
            fillPath.close()
        }
    }
    val (xs, values) = when (s.reduce) {
        PlotReduce.NONE -> FloatArray(s.frequencies.size) { PlotMath.frequencyToX(s.frequencies[it], minHz, maxHz, w) } to s.values
        PlotReduce.MAX -> PlotMath.maxEnvelope(s.frequencies, s.values, minHz, maxHz, w)
    }
    // Points outside the visible range are kept so lines run to the border when zoomed in; the caller clips.
    val span = range.endInclusive - range.start
    for (i in xs.indices) {
        val v = values[i]
        if (!v.isFinite()) {
            closeFill()
            penDown = false
            continue
        }
        // Keep extreme values just outside the plot so lines leave the frame instead of vanishing.
        val clamped = PlotMath.clamp(v, (range.start - span)..(range.endInclusive + span))
        val x = xs[i]
        val y = PlotMath.valueToY(clamped, range.start, range.endInclusive, h)
        if (penDown) {
            path.lineTo(x, y)
            fillPath?.lineTo(x, y)
        } else {
            path.moveTo(x, y)
            fillPath?.moveTo(x, y)
            segmentStart = x
        }
        lastX = x
        penDown = true
    }
    closeFill()
    if (fillPath != null) {
        drawPath(fillPath, brush = Brush.verticalGradient(listOf(s.color.copy(alpha = 0.38f), s.color.copy(alpha = 0.02f)), startY = 0f, endY = h))
    }
    drawPath(
        path,
        color = s.color,
        style = Stroke(
            width = s.width.dp.toPx(),
            pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
        ),
    )
}

/** Width of the value labels left of the plot; the waterfall uses the same margin so both line up. */
internal val PLOT_LEFT_MARGIN = 36.dp
private val LEFT_MARGIN = PLOT_LEFT_MARGIN
private val BOTTOM_MARGIN = 18.dp
