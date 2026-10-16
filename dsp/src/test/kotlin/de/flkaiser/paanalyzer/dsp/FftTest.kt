package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FftTest {

    @Test
    fun matchesNaiveDft() {
        val n = 64
        val random = Random(7)
        val re = DoubleArray(n) { random.nextGaussian() }
        val im = DoubleArray(n) { random.nextGaussian() }
        val expectedRe = DoubleArray(n)
        val expectedIm = DoubleArray(n)
        for (k in 0 until n) {
            for (t in 0 until n) {
                val phase = -2.0 * PI * k * t / n
                expectedRe[k] += re[t] * cos(phase) - im[t] * sin(phase)
                expectedIm[k] += re[t] * sin(phase) + im[t] * cos(phase)
            }
        }
        Fft(n).forward(re, im)
        for (k in 0 until n) {
            assertEquals(expectedRe[k], re[k], 1e-9)
            assertEquals(expectedIm[k], im[k], 1e-9)
        }
    }

    @Test
    fun inverseRestoresSignal() {
        val n = 4096
        val random = Random(3)
        val original = DoubleArray(n) { random.nextGaussian() }
        val re = original.copyOf()
        val im = DoubleArray(n)
        val fft = Fft(n)
        fft.forward(re, im)
        fft.inverse(re, im)
        for (i in 0 until n) {
            assertEquals(original[i], re[i], 1e-10)
            assertEquals(0.0, im[i], 1e-10)
        }
    }

    @Test
    fun cosineLandsInItsBin() {
        val n = 1024
        val bin = 37
        val re = DoubleArray(n) { cos(2.0 * PI * bin * it / n) }
        val im = DoubleArray(n)
        Fft(n).forward(re, im)
        assertEquals(n / 2.0, hypot(re[bin], im[bin]), 1e-9)
        assertEquals(n / 2.0, hypot(re[n - bin], im[n - bin]), 1e-9)
        for (k in 0 until n) {
            if (k != bin && k != n - bin) assertTrue(hypot(re[k], im[k]) < 1e-9, "leakage in bin $k")
        }
    }

    @Test
    fun impulseHasFlatSpectrum() {
        val n = 256
        val re = DoubleArray(n).also { it[0] = 1.0 }
        val im = DoubleArray(n)
        Fft(n).forward(re, im)
        for (k in 0 until n) assertEquals(1.0, hypot(re[k], im[k]), 1e-12)
    }

    @Test
    fun rejectsNonPowerOfTwo() {
        assertFailsWith<IllegalArgumentException> { Fft(1000) }
    }

    @Test
    fun nextPowerOfTwo() {
        assertEquals(2, Fft.nextPowerOfTwo(1))
        assertEquals(1024, Fft.nextPowerOfTwo(1024))
        assertEquals(2048, Fft.nextPowerOfTwo(1025))
        assertTrue(abs(Fft.nextPowerOfTwo(360_000) - 524_288) == 0)
    }
}
