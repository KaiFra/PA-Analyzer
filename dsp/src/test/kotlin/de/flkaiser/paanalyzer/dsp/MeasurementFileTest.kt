package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MeasurementFileTest {

    private val settings = SweepSettings(durationSec = 2.0, maxHarmonic = 3, levelDbfs = -12.5, smoothing = Smoothing.OCT_6, averages = 2)

    private fun result() = MeasurementResult(
        settings = settings,
        frequencies = doubleArrayOf(100.0, 1_000.0, 12_000.0),
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
            doubleArrayOf(-85.0, Double.NaN),
        ),
        delayMs = 187.25,
        inputPeakDbfs = -9.5,
        irSnrDb = 72.0,
        warnings = listOf("Low signal-to-noise ratio", "Second warning"),
    )

    private val doc = MeasurementDocument("Main PA, left\nstack", 1_760_616_000_000, result())

    @Test
    fun writesSpreadsheetFriendlyTables() {
        val lines = MeasurementFile.write(doc).lines()
        assertEquals("# PA Analyzer measurement", lines.first())
        assertTrue("# name=Main PA, left stack" in lines, "names are kept on one line")
        val h = lines.indexOf("frequency_hz,fundamental_db,thd_db,thd_percent,h2_db,h3_db")
        assertTrue(h >= 0, "missing distortion header")
        assertEquals("100,-10,-40,1,-42,-45", lines[h + 1])
        assertEquals("1000,-12.5,-26.021,5,-27,-33", lines[h + 2])
        assertEquals("12000,-20,,,,", lines[h + 3])
        val r = lines.indexOf("frequency_hz,rub_buzz_rms_db,rub_buzz_peak_db,crest_factor_db,noise_floor_db")
        assertTrue(r > h)
        assertEquals("200,-50,-35,20,", lines[r + 2])
    }

    @Test
    fun roundTripsEverything() {
        val read = MeasurementFile.read(MeasurementFile.write(doc))
        assertEquals("Main PA, left stack", read.name)
        assertEquals(doc.createdAtMillis, read.createdAtMillis)
        val r = read.result
        assertEquals(settings, r.settings)
        assertContentEquals(doubleArrayOf(100.0, 1_000.0, 12_000.0), r.frequencies)
        assertContentEquals(doubleArrayOf(-10.0, -12.5, -20.0), r.fundamentalDb)
        assertEquals(-26.021, r.thdDb[1], 1e-9)
        assertTrue(r.thdDb[2].isNaN())
        assertEquals(listOf(2, 3), r.harmonics.map { it.order })
        assertEquals(-33.0, r.harmonics[1].levelDb[1])
        assertContentEquals(doubleArrayOf(-80.0, -50.0), r.rubBuzz.rmsDb)
        assertTrue(r.rubBuzz.noiseFloorDb[1].isNaN())
        assertEquals(187.25, r.delayMs)
        assertEquals(listOf("Low signal-to-noise ratio", "Second warning"), r.warnings)
    }

    @Test
    fun roundTripsARealMeasurement() {
        val s = SweepSettings(durationSec = 2.0, preSilenceSec = 0.5, postSilenceSec = 1.0)
        val recording = SimulatedSystem.record(s, system = SimulatedSystem.memoryless { 0.5 * it + 0.1 * it * it })
        val original = MeasurementAnalyzer(s).analyze(recording)
        val read = MeasurementFile.read(MeasurementFile.write(MeasurementDocument("sim", 1L, original))).result
        assertEquals(original.frequencies.size, read.frequencies.size)
        for (i in original.frequencies.indices) {
            assertEquals(original.fundamentalDb[i], read.fundamentalDb[i], 0.01)
            if (!original.thdDb[i].isNaN()) assertEquals(original.thdDb[i], read.thdDb[i], 0.01)
        }
        assertEquals(original.rubBuzz.size, read.rubBuzz.size)
        assertEquals(original.harmonics.size, read.harmonics.size)
    }

    @Test
    fun readsWindowsLineEndings() {
        val text = MeasurementFile.write(doc).replace("\n", "\r\n")
        assertEquals(3, MeasurementFile.read(text).result.frequencies.size)
    }

    @Test
    fun headerOnly() {
        assertEquals("Main PA, left stack" to 1_760_616_000_000, MeasurementFile.readHeader(MeasurementFile.write(doc)))
    }

    @Test
    fun rejectsOtherFiles() {
        assertFailsWith<MeasurementFileException> { MeasurementFile.read("a,b,c\n1,2,3\n") }
        assertFailsWith<MeasurementFileException> { MeasurementFile.read("# PA Analyzer measurement\n# format=1\n\n") }
        assertFailsWith<MeasurementFileException> { MeasurementFile.read("# PA Analyzer measurement\n# format=99\n") }
    }

    @Test
    fun numbersUsePlainNotation() {
        assertEquals("20000", MeasurementFile.num(20_000.0))
        assertEquals("0.00012346", MeasurementFile.num(0.000123456))
        assertEquals("", MeasurementFile.num(Double.NaN))
    }

    @Test
    fun settingsCodecRoundTripAndDefaults() {
        assertEquals(settings, SweepSettingsCodec.decode(SweepSettingsCodec.encode(settings)))
        assertEquals(SweepSettings(), SweepSettingsCodec.decode(mapOf("durationSec" to "x", "smoothing" to "nope")))
    }
}
