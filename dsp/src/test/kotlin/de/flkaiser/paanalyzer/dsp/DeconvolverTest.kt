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
