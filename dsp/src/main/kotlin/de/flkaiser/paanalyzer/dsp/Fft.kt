package de.flkaiser.paanalyzer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT working on split real/imaginary arrays.
 *
 * [forward] computes X[k] = sum x[n] e^(-j 2 pi k n / N) (no scaling),
 * [inverse] computes x[n] = 1/N sum X[k] e^(+j 2 pi k n / N).
 */
class Fft(val size: Int) {

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two >= 2, was $size" }
    }

    private val levels = Integer.numberOfTrailingZeros(size)
    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }

    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = false)

    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, inverse = true)
        val scale = 1.0 / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        require(re.size == size && im.size == size) { "Arrays must have length $size" }
        val shift = 32 - levels
        for (i in 0 until size) {
            val j = Integer.reverse(i) ushr shift
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sign * sinTable[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }

    companion object {
        fun nextPowerOfTwo(n: Int): Int {
            require(n in 1..(1 shl 30)) { "Unsupported size $n" }
            var p = 2
            while (p < n) p = p shl 1
            return p
        }
    }
}
