package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SweepSettingsTest {

    @Test
    fun defaultsAreValid() {
        assertEquals(emptyList(), SweepSettings().validate())
    }

    @Test
    fun allSupportedSampleRatesWorkWithDefaults() {
        for (rate in SweepSettings.SAMPLE_RATES) {
            assertEquals(emptyList(), SweepSettings(sampleRate = rate).validate(), "at $rate Hz")
        }
    }

    @Test
    fun levelToAmplitude() {
        assertEquals(0.1, SweepSettings(levelDbfs = -20.0).amplitude, 1e-12)
        assertEquals(1.0, SweepSettings(levelDbfs = 0.0).amplitude, 1e-12)
    }

    @Test
    fun rejectsInvalidValues() {
        val invalid = listOf(
            SweepSettings(sampleRate = 22_050),
            SweepSettings(startHz = 5.0),
            SweepSettings(endHz = 22_000.0, sampleRate = 44_100),
            SweepSettings(startHz = 1_000.0, endHz = 3_000.0),
            SweepSettings(durationSec = 0.5),
            SweepSettings(durationSec = 30.0),
            SweepSettings(levelDbfs = 3.0),
            SweepSettings(levelDbfs = -80.0),
            SweepSettings(averages = 0),
            SweepSettings(averages = 20),
            SweepSettings(preSilenceSec = 0.0),
            SweepSettings(postSilenceSec = 0.1),
            SweepSettings(irWindowMs = 1.0),
            SweepSettings(maxHarmonic = 1),
            SweepSettings(maxHarmonic = 11),
            SweepSettings(rubBuzzStartOrder = 2),
        )
        for (s in invalid) assertTrue(s.validate().isNotEmpty(), "expected $s to be rejected")
    }

    @Test
    fun reportsEveryProblem() {
        val errors = SweepSettings(levelDbfs = 3.0, averages = 0).validate()
        assertEquals(2, errors.size)
    }
}
