package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.math.log10
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MicCalibrationTest {

    /** Shortened UMIK-1 style file: a header with the sensitivity, then frequency, dB and phase. */
    private val umik = """
        "Sens Factor =-1.378dB, SERNO: 7000001"
        10.054	-4.2	0.0
        20.0	-1.0	0.0
        100.0	0.0	0.0
        1000.0	0.0	0.0
        4000.0	1.0	0.0
        8000.0	2.0	0.0
        20000.0	-3.5	0.0
    """.trimIndent()

    private val cal = MicCalibration.parse(umik, "7000001_90deg.txt")

    @Test
    fun readsUmikFiles() {
        assertEquals(7, cal.size)
        assertEquals(-1.378, cal.sensitivityDb)
        assertEquals("7000001_90deg.txt", cal.name)
        assertEquals(-1.0, cal.deviationAt(20.0), 1e-9)
    }

    @Test
    fun readsCommaSeparatedFilesWithComments() {
        val c = MicCalibration.parse("* EMM-6\n; comment\nfreq,db\n20,1.5\n200;0.5\n20000, -2\n", "emm6.csv")
        assertEquals(3, c.size)
        assertNull(c.sensitivityDb)
        assertEquals(0.5, c.deviationAt(200.0), 1e-9)
    }

    @Test
    fun interpolatesOverLogFrequencyAndHoldsTheEnds() {
        // 2 kHz is halfway between 1 kHz (0 dB) and 4 kHz (+1 dB) on a log axis.
        assertEquals(0.5, cal.deviationAt(2_000.0), 1e-9)
        assertEquals(-4.2, cal.deviationAt(5.0), 1e-9)
        assertEquals(-3.5, cal.deviationAt(24_000.0), 1e-9)
    }

    @Test
    fun rejectsFilesWithoutUsableData() {
        assertFailsWith<MeasurementFileException> { MicCalibration.parse("hello\nworld", "x.txt") }
        assertFailsWith<MeasurementFileException> { MicCalibration.parse("100 0\n200 0\n", "narrow.txt") }
        assertFailsWith<MeasurementFileException> { MicCalibration.parse("20 0\n20000 95\n", "spl.txt") }
    }

    @Test
    fun writtenFilesReadBack() {
        val back = MicCalibration.parse(MicCalibration.write(cal), cal.name)
        assertEquals(cal.frequencies.toList(), back.frequencies.toList())
        assertEquals(cal.deviationDb.toList(), back.deviationDb.toList())
        assertEquals(cal.sensitivityDb, back.sensitivityDb)
    }

    @Test
    fun correctsFundamentalAndHarmonics() {
        val f = doubleArrayOf(1_000.0, 4_000.0)
        val result = MeasurementResult(
            settings = SweepSettings(maxHarmonic = 3),
            frequencies = f,
            fundamentalDb = doubleArrayOf(-10.0, -9.0),
            thdDb = doubleArrayOf(-30.0, Double.NaN),
            harmonics = listOf(
                HarmonicCurve(2, doubleArrayOf(-33.0, Double.NaN)),
                HarmonicCurve(3, doubleArrayOf(-33.0, Double.NaN)),
            ),
            rubBuzz = RubBuzzResult.EMPTY,
            delayMs = 0.0,
            inputPeakDbfs = -10.0,
            irSnrDb = 60.0,
            warnings = emptyList(),
        )
        val corrected = cal.apply(result)
        // The mic is 1 dB hot at 4 kHz: the speaker is 1 dB quieter there than measured.
        assertEquals(-10.0, corrected.fundamentalDb[0], 1e-9)
        assertEquals(-10.0, corrected.fundamentalDb[1], 1e-9)
        // H2 of 1 kHz is heard at 2 kHz (mic +0.5 dB), H3 at 3 kHz (mic +log2(3)/2 dB).
        assertEquals(-33.5, corrected.harmonics[0].levelDb[0], 1e-9)
        val h3 = -33.0 - 0.5 * kotlin.math.log2(3.0)
        assertEquals(h3, corrected.harmonics[1].levelDb[0], 1e-9)
        assertEquals(10 * log10(Math.pow(10.0, -3.35) + Math.pow(10.0, h3 / 10)), corrected.thdDb[0], 1e-9)
        assertTrue(corrected.thdDb[1].isNaN())
        assertEquals("7000001_90deg.txt", corrected.calibration)
    }

    @Test
    fun calibrationIsStoredInMeasurementFiles() {
        val r = MeasurementResult(
            SweepSettings(), doubleArrayOf(100.0, 1_000.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(-40.0, -40.0),
            emptyList(), RubBuzzResult.EMPTY, 0.0, 0.0, 60.0, emptyList(), calibration = "umik.txt",
        )
        val back = MeasurementFile.read(MeasurementFile.write(MeasurementDocument("m", 1L, r))).result
        assertEquals("umik.txt", back.calibration)
        val none = MeasurementFile.read(MeasurementFile.write(MeasurementDocument("m", 1L, cal.apply(r).let {
            MeasurementResult(it.settings, it.frequencies, it.fundamentalDb, it.thdDb, it.harmonics, it.rubBuzz, 0.0, 0.0, 60.0, emptyList())
        }))).result
        assertNull(none.calibration)
    }
}
