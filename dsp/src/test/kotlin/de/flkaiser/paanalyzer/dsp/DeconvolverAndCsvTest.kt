package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeconvolverTest {

    @Test
    fun findPeakInterpolatesBetweenSamples() {
        val ir = DoubleArray(100) { maxOf(0.0, 100.0 - (it - 40.3) * (it - 40.3)) }
        assertEquals(40.3, Deconvolver.findPeak(ir), 1e-9)
    }

    @Test
    fun findPeakUsesMagnitude() {
        val ir = DoubleArray(64).also { it[10] = 0.5; it[20] = -1.0 }
        assertEquals(20.0, Deconvolver.findPeak(ir), 0.01)
    }

    @Test
    fun butterworthHighPassMagnitude() {
        val hp = ButterworthHighPass(8, 10.0)
        fun mag(f: Double) = hp.response(f).let { kotlin.math.hypot(it.first, it.second) }
        assertEquals(1 / kotlin.math.sqrt(2.0), mag(10.0), 1e-9)
        assertEquals(1.0, mag(20.0), 1e-3)
        assertEquals(1.0, mag(1_000.0), 1e-9)
        assertTrue(mag(5.0) < 0.005)
    }

    @Test
    fun delayedSweepGivesImpulseAtDelay() {
        val sweep = ExpSweep(50.0, 15_000.0, 1.0, 48_000)
        val size = Fft.nextPowerOfTwo(sweep.length + 10_000)
        val recording = FloatArray(size)
        for (i in 0 until sweep.length) recording[i + 1_234] = (0.25 * sweep.samples[i]).toFloat()
        val deconvolver = Deconvolver(sweep, size)
        val ir = deconvolver.impulseResponse(recording, 0, size)
        assertEquals(1_234.0, Deconvolver.findPeak(ir), 0.1)
        // Band-limited impulse: the peak is close to the gain times the passband fraction.
        assertTrue(ir[1_234] in 0.15..0.3, "peak value ${ir[1_234]}")
    }
}

class CsvExporterTest {

    private fun result(): MeasurementResult {
        val settings = SweepSettings(durationSec = 2.0, maxHarmonic = 3)
        val f = doubleArrayOf(100.0, 1_000.0, 12_000.0)
        return MeasurementResult(
            settings = settings,
            frequencies = f,
            fundamentalDb = doubleArrayOf(-10.0, -12.5, -20.0),
            thdDb = doubleArrayOf(-40.0, -26.0206, Double.NaN),
            harmonics = listOf(
                HarmonicCurve(2, doubleArrayOf(-42.0, -27.0, Double.NaN)),
                HarmonicCurve(3, doubleArrayOf(-45.0, -33.0, Double.NaN)),
            ),
            rubBuzz = RubBuzzResult(
                doubleArrayOf(100.0, 200.0),
                doubleArrayOf(-80.0, -50.0),
                doubleArrayOf(-70.0, -35.0),
                doubleArrayOf(11.0, 20.0),
                doubleArrayOf(-85.0, -84.0),
            ),
            delayMs = 187.25,
            inputPeakDbfs = -9.5,
            irSnrDb = 72.0,
            warnings = listOf("Low signal-to-noise ratio"),
        )
    }

    @Test
    fun writesBothTablesWithHeaders() {
        val lines = CsvExporter.export(result()).lines()
        assertTrue(lines.first().startsWith("# PA Analyzer"))
        assertTrue(lines.any { it == "# warning: Low signal-to-noise ratio" })
        val h = lines.indexOf("frequency_hz,fundamental_db,thd_db,thd_percent,h2_db,h3_db")
        assertTrue(h >= 0, "missing distortion header")
        assertEquals("100,-10,-40,1,-42,-45", lines[h + 1])
        assertEquals("1000,-12.5,-26.021,5,-27,-33", lines[h + 2])
        assertEquals("12000,-20,,,,", lines[h + 3])
        val r = lines.indexOf("frequency_hz,rub_buzz_rms_db,rub_buzz_peak_db,crest_factor_db,noise_floor_db")
        assertTrue(r > h)
        assertEquals("200,-50,-35,20,-84", lines[r + 2])
    }

    @Test
    fun numbersUsePlainNotation() {
        assertEquals("20000", CsvExporter.num(20_000.0))
        assertEquals("0.00012346", CsvExporter.num(0.000123456))
        assertEquals("", CsvExporter.num(Double.NaN))
    }
}
