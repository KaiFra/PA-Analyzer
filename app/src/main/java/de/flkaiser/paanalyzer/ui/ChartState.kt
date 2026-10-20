package de.flkaiser.paanalyzer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal val Y_LIMIT = -250.0..100.0

/**
 * Zoom, pan and cursor of a [FrequencyPlot]; [fullX] is the zoomed-out frequency range and
 * [defaultY] the level range after a reset (null = automatic from the visible curves).
 */
class ChartState(val fullX: ClosedFloatingPointRange<Double>, val defaultY: ClosedFloatingPointRange<Double>? = null) {
    var xRange by mutableStateOf(fullX)
    /** null = automatic from the visible curves. */
    var yRange by mutableStateOf(defaultY)
    var cursorHz by mutableStateOf<Double?>(null)

    fun gestures(autoY: () -> ClosedFloatingPointRange<Double>) = object : PlotGestures {
        override fun cursor(frequencyHz: Double) {
            cursorHz = frequencyHz
        }

        override fun zoom(factorX: Double, factorY: Double, anchorX: Double, anchorY: Double) {
            if (factorX != 1.0) xRange = PlotMath.zoomLog(xRange, factorX, anchorX, fullX)
            if (factorY != 1.0) yRange = PlotMath.zoomLinear(yRange ?: autoY(), factorY, anchorY, Y_LIMIT)
        }

        override fun pan(fractionX: Double, fractionY: Double) {
            xRange = PlotMath.panLog(xRange, fractionX, fullX)
            if (fractionY != 0.0) {
                val y = yRange ?: autoY()
                yRange = PlotMath.panLinear(y, fractionY, Y_LIMIT)
            }
        }

        override fun reset() {
            xRange = fullX
            yRange = defaultY
        }
    }
}
