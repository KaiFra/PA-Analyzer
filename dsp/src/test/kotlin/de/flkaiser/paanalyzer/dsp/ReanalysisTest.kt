package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReanalysisTest {

    private val settings = SweepSettings(durationSec = 2.0, levelDbfs = -6.0206, preSilenceSec = 0.5, postSilenceSec = 1.0, averages = 2)

    private val recording = SimulatedSystem.record(settings, noiseDbfs = -80.0, system = SimulatedSystem.memoryless { it + 0.2 * it * it + 0.3 * it * it * it })

    private val prepared = MeasurementAnalyzer(settings).prepare(recording)

    private fun assertSameResult(expected: MeasurementResult, actual: MeasurementResult) {
        assertEquals(expected.settings, actual.settings)
        assertContentEquals(expected.frequencies, actual.frequencies)
        assertContentEquals(expected.fundamentalDb, actual.fundamentalDb)
        assertContentEquals(expected.thdDb, actual.thdDb)
        assertEquals(expected.harmonics.map { it.order }, actual.harmonics.map { it.order })
        for ((e, a) in expected.harmonics.zip(actual.harmonics)) assertContentEquals(e.levelDb, a.levelDb)
        assertContentEquals(expected.rubBuzz.frequencies, actual.rubBuzz.frequencies)
        assertContentEquals(expected.rubBuzz.rmsDb, actual.rubBuzz.rmsDb)
        assertContentEquals(expected.rubBuzz.noiseFloorDb, actual.rubBuzz.noiseFloorDb)
        assertEquals(expected.irSnrDb, actual.irSnrDb)
        assertEquals(expected.delayMs, actual.delayMs)
        assertEquals(expected.warnings, actual.warnings)
    }

    @Test
    fun evaluatingWithOriginalSettingsMatchesAnalyze() {
        assertSameResult(MeasurementAnalyzer(settings).analyze(recording), prepared.evaluate())
    }

    @Test
    fun changedAnalysisSettingsMatchAFreshAnalysis() {
        val analysis = AnalysisSettings(irWindowMs = 40.0, maxHarmonic = 8, rubBuzzStartOrder = 20, smoothing = Smoothing.OCT_3)
        val fresh = MeasurementAnalyzer(settings.withAnalysis(analysis)).analyze(recording)
        assertSameResult(fresh, prepared.evaluate(analysis))
    }

    @Test
    fun analysisSettingsChangeTheResult() {
        val base = prepared.evaluate()
        val more = prepared.evaluate(settings.analysis.copy(maxHarmonic = 8, rubBuzzStartOrder = 20))
        assertEquals(4, base.harmonics.size)
        assertEquals(7, more.harmonics.size)
        // A higher start order leaves less room below 20 kHz, so the rub & buzz curve ends earlier.
        assertTrue(more.rubBuzz.frequencies.last() < base.rubBuzz.frequencies.last())
        val smoothed = prepared.evaluate(settings.analysis.copy(smoothing = Smoothing.OCT_3))
        assertTrue(smoothed.fundamentalDb.zip(base.fundamentalDb).any { (a, b) -> a != b })
    }

    @Test
    fun sweepSettingsStayThoseOfTheRecording() {
        val result = prepared.evaluate(settings.analysis.copy(smoothing = Smoothing.NONE))
        assertEquals(settings.durationSec, result.settings.durationSec)
        assertEquals(settings.averages, result.settings.averages)
        assertEquals(Smoothing.NONE, result.settings.smoothing)
    }

    @Test
    fun rejectsInvalidAnalysisSettings() {
        assertFailsWith<MeasurementException> { prepared.evaluate(settings.analysis.copy(maxHarmonic = 20)) }
    }
}
