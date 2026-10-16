package de.flkaiser.paanalyzer

import de.flkaiser.paanalyzer.dsp.HarmonicCurve
import de.flkaiser.paanalyzer.dsp.MeasurementResult
import de.flkaiser.paanalyzer.dsp.RubBuzzResult
import de.flkaiser.paanalyzer.dsp.SweepSettings
import de.flkaiser.paanalyzer.ui.CurveKind
import de.flkaiser.paanalyzer.ui.Curves
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurvesTest {

    private val result = MeasurementResult(
        settings = SweepSettings(maxHarmonic = 3),
        frequencies = doubleArrayOf(100.0, 200.0, 400.0),
        fundamentalDb = doubleArrayOf(-10.0, -20.0, -30.0),
        thdDb = doubleArrayOf(-40.0, -20.0, Double.NaN),
        harmonics = listOf(
            HarmonicCurve(2, doubleArrayOf(-41.0, -21.0, Double.NaN)),
            HarmonicCurve(3, doubleArrayOf(-50.0, -30.0, Double.NaN)),
        ),
        rubBuzz = RubBuzzResult(
            frequencies = doubleArrayOf(100.0, 141.42135623730951),
            rmsDb = doubleArrayOf(-70.0, -60.0),
            peakDb = doubleArrayOf(-55.0, -45.0),
            crestDb = doubleArrayOf(12.0, 15.0),
            noiseFloorDb = doubleArrayOf(-80.0, -80.0),
        ),
        delayMs = 0.0,
        inputPeakDbfs = -10.0,
        irSnrDb = 60.0,
        warnings = emptyList(),
    )

    private val curves = Curves.build(result)

    @Test
    fun buildsAllCurvesInOrder() {
        assertEquals(listOf("fundamental", "thd", "h2", "h3", "rb_rms", "rb_peak", "rb_noise"), curves.map { it.id })
        assertEquals(setOf("fundamental", "thd"), Curves.DEFAULT_VISIBLE)
    }

    @Test
    fun distortionIsDrawnAtAbsoluteLevel() {
        val thd = curves.first { it.id == Curves.THD }
        assertEquals(CurveKind.DISTORTION, thd.kind)
        assertEquals(-50.0, thd.absoluteDb[0])
        assertEquals(-40.0, thd.absoluteDb[1])
        assertTrue(thd.absoluteDb[2].isNaN())
        assertEquals(-40.0, thd.relativeDb!![0])
    }

    @Test
    fun rubBuzzUsesInterpolatedFundamental() {
        val rms = curves.first { it.id == Curves.RUB_BUZZ_RMS }
        assertEquals(-80.0, rms.absoluteDb[0], 1e-9)
        // 141 Hz is half an octave above 100 Hz: fundamental -15 dB there.
        assertEquals(-75.0, rms.absoluteDb[1], 1e-9)
    }

    @Test
    fun logInterpolation() {
        val f = doubleArrayOf(100.0, 1_000.0)
        val v = doubleArrayOf(0.0, 10.0)
        assertEquals(5.0, Curves.interpolateLog(f, v, 316.22776601683796), 1e-9)
        assertTrue(Curves.interpolateLog(f, v, 50.0).isNaN())
        assertEquals(10.0, Curves.interpolateLog(f, v, 1_000.0))
    }

    @Test
    fun readoutsAreRelativeForDistortion() {
        val fundamental = curves.first { it.id == Curves.FUNDAMENTAL }
        val thd = curves.first { it.id == Curves.THD }
        val rb = curves.first { it.id == Curves.RUB_BUZZ_RMS }
        assertEquals("-20.0 dB", Curves.readout(fundamental, 200.0, Locale.ROOT))
        assertEquals("-20.0 dB · 10 %", Curves.readout(thd, 200.0, Locale.ROOT))
        assertEquals("-40.0 dB · 1.0 %", Curves.readout(thd, 100.0, Locale.ROOT))
        assertEquals("–", Curves.readout(thd, 400.0, Locale.ROOT))
        assertEquals("-70.0 dB re fund.", Curves.readout(rb, 100.0, Locale.ROOT))
        assertNull(Curves.readout(rb, 1_000.0, Locale.ROOT))
        assertEquals("-20,0 dB", Curves.readout(fundamental, 200.0, Locale.GERMANY))
    }

    @Test
    fun percentPrecision() {
        assertEquals("12", Curves.percent(12.3, Locale.ROOT))
        assertEquals("1.2", Curves.percent(1.23, Locale.ROOT))
        assertEquals("0.12", Curves.percent(0.123, Locale.ROOT))
        assertEquals("0.012", Curves.percent(0.0123, Locale.ROOT))
    }
}
