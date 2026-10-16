package de.flkaiser.paanalyzer

import de.flkaiser.paanalyzer.ui.PlotMath
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlotMathTest {

    @Test
    fun frequencyTicksFollowOneTwoFive() {
        assertEquals(
            listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0),
            PlotMath.frequencyTicks(20.0, 20_000.0),
        )
        assertEquals(listOf(100.0, 200.0, 500.0), PlotMath.frequencyTicks(60.0, 800.0))
    }

    @Test
    fun formatsFrequencies() {
        assertEquals("20", PlotMath.formatFrequency(20.0))
        assertEquals("500", PlotMath.formatFrequency(500.0))
        assertEquals("1k", PlotMath.formatFrequency(1_000.0))
        assertEquals("1.5k", PlotMath.formatFrequency(1_500.0))
        assertEquals("20k", PlotMath.formatFrequency(20_000.0))
        assertEquals("12.5", PlotMath.formatFrequency(12.5))
    }

    @Test
    fun valueTicksAreRound() {
        assertEquals(listOf(-60.0, -50.0, -40.0, -30.0, -20.0, -10.0, 0.0), PlotMath.valueTicks(-60.0, 0.0))
        assertEquals(listOf(-80.0, -60.0, -40.0, -20.0, 0.0), PlotMath.valueTicks(-85.0, 5.0, target = 5))
        assertEquals(listOf(1.0), PlotMath.valueTicks(1.0, 1.0))
    }

    @Test
    fun logAxisMappingIsInvertible() {
        val width = 800f
        assertEquals(0f, PlotMath.frequencyToX(20.0, 20.0, 20_000.0, width), 1e-3f)
        assertEquals(width, PlotMath.frequencyToX(20_000.0, 20.0, 20_000.0, width), 1e-3f)
        // 632 Hz is the geometric middle of 20 Hz .. 20 kHz.
        assertEquals(width / 2, PlotMath.frequencyToX(632.4555, 20.0, 20_000.0, width), 0.01f)
        assertEquals(1_000.0, PlotMath.xToFrequency(PlotMath.frequencyToX(1_000.0, 20.0, 20_000.0, width), 20.0, 20_000.0, width), 0.01)
        // Outside the plot clamps to the range.
        assertEquals(20.0, PlotMath.xToFrequency(-50f, 20.0, 20_000.0, width), 1e-9)
    }

    @Test
    fun rangeCoversMaximumWithHeadroom() {
        val range = PlotMath.rangeBelowMax(listOf(doubleArrayOf(-12.0, Double.NaN, -3.5)), span = 60.0)
        assertEquals(0.0, range.endInclusive)
        assertEquals(-60.0, range.start)
        val empty = PlotMath.rangeBelowMax(listOf(doubleArrayOf(Double.NaN)), span = 50.0)
        assertEquals(10.0, empty.endInclusive)
    }

    @Test
    fun nearestIndexOnLogScale() {
        val grid = doubleArrayOf(100.0, 200.0, 400.0)
        assertEquals(1, PlotMath.nearestIndex(grid, 270.0))
        assertEquals(2, PlotMath.nearestIndex(grid, 300.0))
        assertEquals(-1, PlotMath.nearestIndex(DoubleArray(0), 1.0))
    }

    @Test
    fun autoRangeIgnoresSilenceAndKeepsSpanLimits() {
        val r = PlotMath.autoRange(listOf(doubleArrayOf(-12.0, -48.0, -300.0, Double.NaN)))
        assertEquals(0.0, r.endInclusive)
        assertEquals(-60.0, r.start)
        val narrow = PlotMath.autoRange(listOf(doubleArrayOf(-5.0, -6.0)))
        assertEquals(40.0, narrow.endInclusive - narrow.start)
        assertEquals(-40.0..0.0, PlotMath.autoRange(emptyList()))
    }

    @Test
    fun zoomLogKeepsAnchorFixed() {
        val full = 20.0..20_000.0
        val zoomed = PlotMath.zoomLog(full, factor = 2.0, anchor = 0.5, limit = full)
        // Geometric centre stays, span halves (in octaves).
        assertEquals(632.4555, kotlin.math.sqrt(zoomed.start * zoomed.endInclusive), 0.01)
        assertEquals(kotlin.math.sqrt(1_000.0), zoomed.endInclusive / zoomed.start, 1e-6)
        // Cannot zoom out beyond the data or in beyond the minimum span.
        val out = PlotMath.zoomLog(zoomed, factor = 0.1, anchor = 0.5, limit = full)
        assertEquals(20.0, out.start, 1e-6)
        assertEquals(20_000.0, out.endInclusive, 1e-6)
        val tight = PlotMath.zoomLog(full, factor = 1e6, anchor = 0.0, limit = full)
        assertEquals(20.0, tight.start, 1e-9)
        assertEquals(1.5, tight.endInclusive / tight.start, 1e-9)
    }

    @Test
    fun panLogStaysInsideLimits() {
        val full = 20.0..20_000.0
        val view = 200.0..2_000.0
        val moved = PlotMath.panLog(view, fraction = 1.0, limit = full)
        assertEquals(20.0, moved.start, 1e-9)
        assertEquals(200.0, moved.endInclusive, 1e-9)
        val far = PlotMath.panLog(view, fraction = -10.0, limit = full)
        assertEquals(20_000.0, far.endInclusive, 1e-9)
        assertEquals(2_000.0, far.start, 1e-6)
    }

    @Test
    fun linearZoomAndPan() {
        val limit = -250.0..100.0
        val zoomed = PlotMath.zoomLinear(-60.0..0.0, factor = 2.0, anchor = 1.0, limit = limit)
        assertEquals(-30.0..0.0, zoomed)
        assertEquals(-45.0..-15.0, PlotMath.panLinear(zoomed, fraction = -0.5, limit = limit))
        assertEquals(6.0, PlotMath.zoomLinear(-60.0..0.0, factor = 1e3, anchor = 0.5, limit = limit).let { it.endInclusive - it.start }, 1e-9)
        assertEquals(70.0..100.0, PlotMath.panLinear(zoomed, fraction = 100.0, limit = limit))
    }

    @Test
    fun denseTicksForNarrowRanges() {
        assertEquals(listOf(200.0, 300.0, 400.0, 500.0), PlotMath.denseFrequencyTicks(150.0, 550.0))
        assertTrue(PlotMath.denseFrequencyTicks(20.0, 20_000.0).size <= 10)
    }
}
