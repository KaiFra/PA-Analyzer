package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.test.assertTrue

class RubBuzzTest {

    private val settings = SweepSettings(
        durationSec = 3.0,
        levelDbfs = -6.0206,
        preSilenceSec = 0.5,
        postSilenceSec = 1.0,
        rubBuzzStartOrder = 10,
    )

    /**
     * A loose part that ticks once per cycle (at the positive peak) while the
     * excitation is between 150 and 300 Hz.
     */
    private fun rattle(x: DoubleArray): DoubleArray {
        val plan = PlaybackPlan(settings)
        val sweep = plan.sweep
        val fs = settings.sampleRate.toDouble()
        val y = x.copyOf()
        for (i in 1 until x.size) {
            val t = (i - plan.preSamples) / fs
            if (t < 0 || t > sweep.durationSec) continue
            val f = sweep.frequencyAt(t)
            if (f in 150.0..300.0 && x[i - 1] < 0.45 && x[i] >= 0.45) y[i] += 0.025
        }
        return y
    }

    private fun analyze(system: (DoubleArray) -> DoubleArray): RubBuzzResult {
        val recording = SimulatedSystem.record(settings, noiseDbfs = -90.0, system = system)
        return MeasurementAnalyzer(settings).analyze(recording).rubBuzz
    }

    private fun RubBuzzResult.indicesIn(lo: Double, hi: Double) = frequencies.indices.filter { frequencies[it] in lo..hi }

    @Test
    fun cleanSystemStaysNearNoiseFloor() {
        val rb = analyze(SimulatedSystem.memoryless { it })
        assertTrue(rb.size > 20, "too few frames: ${rb.size}")
        for (i in rb.indicesIn(40.0, 1_500.0)) {
            assertTrue(rb.rmsDb[i] < -70.0, "R&B ${rb.rmsDb[i]} dB at ${rb.frequencies[i]} Hz")
            assertTrue(rb.rmsDb[i] < rb.noiseFloorDb[i] + 6.0, "R&B ${rb.rmsDb[i]} vs noise ${rb.noiseFloorDb[i]} dB")
            assertTrue(rb.crestDb[i] < 14.0, "crest ${rb.crestDb[i]} dB at ${rb.frequencies[i]} Hz")
        }
    }

    @Test
    fun rattleIsDetectedAndLocalized() {
        val clean = analyze(SimulatedSystem.memoryless { it })
        val buzzing = analyze(::rattle)
        val inside = buzzing.indicesIn(180.0, 270.0)
        assertTrue(inside.isNotEmpty())
        for (i in inside) {
            assertTrue(buzzing.rmsDb[i] > -60.0, "R&B ${buzzing.rmsDb[i]} dB at ${buzzing.frequencies[i]} Hz")
            assertTrue(buzzing.rmsDb[i] > clean.rmsDb[i] + 20.0, "R&B not above clean system at ${buzzing.frequencies[i]} Hz")
            assertTrue(buzzing.crestDb[i] > 15.0, "crest ${buzzing.crestDb[i]} dB at ${buzzing.frequencies[i]} Hz")
            assertTrue(buzzing.peakDb[i] > buzzing.rmsDb[i] + 10.0, "peak should exceed RMS for impulsive buzz")
        }
        for (i in buzzing.indicesIn(450.0, 1_200.0)) {
            assertTrue(buzzing.rmsDb[i] < -70.0, "R&B leaked to ${buzzing.frequencies[i]} Hz: ${buzzing.rmsDb[i]} dB")
        }
    }

    @Test
    fun framesAreOrderedAndBelowAnalysisLimit() {
        val rb = analyze(SimulatedSystem.memoryless { it })
        for (i in 1 until rb.size) assertTrue(rb.frequencies[i] > rb.frequencies[i - 1])
        // Order 10 × frame frequency must leave room below 20 kHz.
        assertTrue(rb.frequencies.last() * settings.rubBuzzStartOrder < 0.7 * 20_000.0 * 1.1)
    }
}
