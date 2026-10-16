package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpSweepTest {

    private val sweep = ExpSweep(startHz = 20.0, endHz = 20_000.0, requestedDurationSec = 5.0, sampleRate = 48_000, amplitude = 0.5)

    @Test
    fun rateIsSynchronized() {
        val cycles = sweep.startHz * sweep.rate
        assertEquals(cycles.roundToInt().toDouble(), cycles, 1e-9)
    }

    @Test
    fun durationIsCloseToRequested() {
        // Synchronization changes the duration by at most half a step of ln(f2/f1)/f1.
        val maxError = ln(1000.0) / (2 * 20.0)
        assertTrue(abs(sweep.durationSec - 5.0) <= maxError, "duration ${sweep.durationSec}")
        assertEquals((sweep.durationSec * 48_000).roundToInt(), sweep.length)
    }

    @Test
    fun instantaneousFrequencyMatchesZeroCrossings() {
        for (t in listOf(1.0, 2.5, 4.0)) {
            val expected = sweep.frequencyAt(t)
            // Count zero crossings in a short window around t.
            val half = (0.01 * 48_000).roundToInt()
            val center = (t * 48_000).roundToInt()
            var crossings = 0
            for (i in center - half until center + half) {
                if ((sweep.samples[i] >= 0) != (sweep.samples[i + 1] >= 0)) crossings++
            }
            val measured = crossings / 2.0 / (2 * half / 48_000.0)
            assertEquals(expected, measured, expected * 0.05 + 50.0 / 2, "frequency at $t s")
        }
    }

    @Test
    fun timeAndFrequencyAreInverse() {
        assertEquals(1_000.0, sweep.frequencyAt(sweep.timeAt(1_000.0)), 1e-9)
        assertEquals(sweep.endHz, sweep.frequencyAt(sweep.durationSec), 1e-6)
        assertEquals(sweep.rate * ln(3.0), sweep.harmonicOffsetSec(3), 1e-12)
    }

    @Test
    fun amplitudeAndFades() {
        val peak = sweep.samples.maxOf { abs(it) }
        assertEquals(0.5, peak, 0.001)
        assertEquals(0.0, sweep.samples.first(), 1e-12)
        assertEquals(0.0, sweep.samples.last(), 1e-12)
    }

    @Test
    fun playbackPlanPlacesSweepsAfterSilence() {
        val settings = SweepSettings(durationSec = 2.0, averages = 3, preSilenceSec = 0.5, postSilenceSec = 1.0)
        val plan = PlaybackPlan(settings)
        val signal = plan.render()
        assertEquals(plan.periodSamples * 3, signal.size)
        assertEquals(plan.preSamples + plan.sweep.length + plan.postSamples, plan.periodSamples)
        for (r in 0 until 3) {
            val base = r * plan.periodSamples
            for (i in 0 until plan.preSamples) assertEquals(0f, signal[base + i])
            for (i in 0 until plan.postSamples) assertEquals(0f, signal[base + plan.preSamples + plan.sweep.length + i])
            assertEquals(plan.sweep.samples[1000].toFloat(), signal[base + plan.preSamples + 1000])
        }
        assertTrue(plan.recordSamples > plan.totalSamples)
    }
}
