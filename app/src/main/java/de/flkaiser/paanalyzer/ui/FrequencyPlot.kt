package de.flkaiser.paanalyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class PlotSeries(
    val label: String,
    val frequencies: DoubleArray,
    val values: DoubleArray,
    val color: Color,
    val dashed: Boolean = false,
    val width: Float = 2f,
)

/**
 * Line chart with a logarithmic frequency axis. Tap or drag horizontally to
 * place a cursor; the values at the cursor are shown in the legend.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FrequencyPlot(
    series: List<PlotSeries>,
    minHz: Double,
    maxHz: Double,
    range: ClosedFloatingPointRange<Double>,
    unit: String,
    modifier: Modifier = Modifier,
    valueLabel: (Double) -> String = { "%.0f".format(it) },
    cursorValue: (Double) -> String = { "%.1f %s".format(it, unit) },
) {
    var cursorHz by remember(minHz, maxHz) { mutableStateOf<Double?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val cursorColor = MaterialTheme.colorScheme.primary

    Column(modifier) {
        val cursor = cursorHz
        Text(
            text = if (cursor == null) "Tap the chart for values" else "${PlotMath.formatFrequency(cursor)} Hz",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            for (s in series) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                    val value = cursor?.let { valueAt(s, it) }
                    val text = if (value == null) s.label else "${s.label}: ${if (value.isNaN()) "–" else cursorValue(value)}"
                    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clipToBounds()
                .pointerInput(minHz, maxHz) {
                    detectTapGestures { pos ->
                        val plotWidth = size.width - LEFT_MARGIN.toPx()
                        cursorHz = PlotMath.xToFrequency(pos.x - LEFT_MARGIN.toPx(), minHz, maxHz, plotWidth)
                    }
                }
                .pointerInput(minHz, maxHz) {
                    detectHorizontalDragGestures { change, _ ->
                        val plotWidth = size.width - LEFT_MARGIN.toPx()
                        cursorHz = PlotMath.xToFrequency(change.position.x - LEFT_MARGIN.toPx(), minHz, maxHz, plotWidth)
                    }
                },
        ) {
            val left = LEFT_MARGIN.toPx()
            val bottom = BOTTOM_MARGIN.toPx()
            val w = size.width - left
            val h = size.height - bottom

            // Grid and labels.
            for (v in PlotMath.valueTicks(range.start, range.endInclusive)) {
                val y = PlotMath.valueToY(v, range.start, range.endInclusive, h)
                drawLine(gridColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
                val layout = textMeasurer.measure(valueLabel(v), labelStyle)
                drawText(layout, topLeft = Offset(left - layout.size.width - 4.dp.toPx(), (y - layout.size.height / 2f).coerceIn(0f, h - layout.size.height)))
            }
            for (f in PlotMath.frequencyTicks(minHz, maxHz)) {
                val x = left + PlotMath.frequencyToX(f, minHz, maxHz, w)
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                val layout = textMeasurer.measure(PlotMath.formatFrequency(f), labelStyle)
                drawText(layout, topLeft = Offset((x - layout.size.width / 2f).coerceIn(left, size.width - layout.size.width), h + 2.dp.toPx()))
            }
            drawLine(axisColor, Offset(left, 0f), Offset(left, h), strokeWidth = 1.5f)
            drawLine(axisColor, Offset(left, h), Offset(size.width, h), strokeWidth = 1.5f)

            clipRect(left = left, top = 0f, right = size.width, bottom = h) {
                translate(left = left) {
                    for (s in series) drawSeries(s, minHz, maxHz, range, w, h)
                }
            }
            cursor?.let {
                val x = left + PlotMath.frequencyToX(it, minHz, maxHz, w)
                drawLine(cursorColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
            }
        }
    }
}

private fun valueAt(s: PlotSeries, f: Double): Double? {
    if (s.frequencies.isEmpty() || f < s.frequencies.first() / 1.05 || f > s.frequencies.last() * 1.05) return null
    val i = PlotMath.nearestIndex(s.frequencies, f)
    return s.values[i]
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
    var penDown = false
    for (i in s.frequencies.indices) {
        val f = s.frequencies[i]
        val v = s.values[i]
        if (!v.isFinite() || f < minHz || f > maxHz) {
            penDown = false
            continue
        }
        // Keep extreme values just outside the plot so lines leave the frame instead of vanishing.
        val clamped = PlotMath.clamp(v, (range.start - 1.0)..(range.endInclusive + 1.0))
        val x = PlotMath.frequencyToX(f, minHz, maxHz, w)
        val y = PlotMath.valueToY(clamped, range.start, range.endInclusive, h)
        if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
        penDown = true
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

private val LEFT_MARGIN = 44.dp
private val BOTTOM_MARGIN = 18.dp
