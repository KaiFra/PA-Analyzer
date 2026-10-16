package de.flkaiser.paanalyzer

import de.flkaiser.paanalyzer.dsp.MeasurementResult
import de.flkaiser.paanalyzer.dsp.RubBuzzResult
import de.flkaiser.paanalyzer.dsp.SweepSettings
import de.flkaiser.paanalyzer.ui.Compare
import de.flkaiser.paanalyzer.ui.Comparison
import de.flkaiser.paanalyzer.ui.CurveKind
import de.flkaiser.paanalyzer.ui.Curves
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComparisonTest {

    private val f = doubleArrayOf(100.0, 300.0, 1_000.0, 3_000.0)

    private fun result(vararg db: Double, thd: Double = -40.0) = MeasurementResult(
        settings = SweepSettings(),
        frequencies = f,
        fundamentalDb = db,
        thdDb = DoubleArray(db.size) { thd },
        harmonics = emptyList(),
        rubBuzz = RubBuzzResult.EMPTY,
        delayMs = 0.0,
        inputPeakDbfs = -10.0,
        irSnrDb = 60.0,
        warnings = emptyList(),
    )

    private val current = result(-10.0, -10.0, -10.0, -10.0)
    private val quieter = Comparison("b.csv", "After EQ", result(-15.0, -20.0, -20.0, -25.0), 0)

    @Test
    fun drawsFundamentalAndThdAtAbsoluteLevel() {
        val curves = Compare.curves(quieter, current, align = false)
        assertEquals(listOf(CurveKind.FUNDAMENTAL, CurveKind.DISTORTION), curves.map { it.kind })
        assertEquals(-20.0, curves[0].absoluteDb[1])
        assertEquals(-60.0, curves[1].absoluteDb[1])
        assertEquals(-40.0, curves[1].relativeDb!![1])
        // Ids don't clash with the current measurement's curves.
        kotlin.test.assertTrue(curves.none { it.id == Curves.FUNDAMENTAL || it.id == Curves.THD })
    }

    @Test
    fun alignsToTheMidBandLevelOfTheCurrentMeasurement() {
        // Mid band 200 Hz – 2 kHz: 300 Hz and 1 kHz, median -20 dB vs -10 dB.
        assertEquals(10.0, Compare.alignOffset(current, quieter.result), 1e-9)
        val aligned = Compare.curves(quieter, current, align = true)
        assertEquals(listOf(-5.0, -10.0, -10.0, -15.0), aligned[0].absoluteDb.toList())
    }

    @Test
    fun readoutShowsLevelAndDifference() {
        val curves = Compare.curves(quieter, current, align = false)
        val reference = Curves.build(current).first { it.id == Curves.FUNDAMENTAL }
        assertEquals("-20.0 dB (-10.0)", Compare.readout(curves[0], reference, 1_000.0, Locale.US))
        assertEquals("-20.0 dB", Compare.readout(curves[0], null, 1_000.0, Locale.US))
        assertNull(Compare.readout(curves[0], reference, 10_000.0, Locale.US))
    }

    @Test
    fun colorsCycle() {
        assertEquals(Compare.color(Comparison("a", "a", current, 0)), Compare.color(Comparison("b", "b", current, Compare.COLORS.size)))
    }
}
