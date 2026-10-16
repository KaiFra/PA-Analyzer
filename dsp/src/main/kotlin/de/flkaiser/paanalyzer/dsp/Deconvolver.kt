package de.flkaiser.paanalyzer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * Deconvolves recordings by the played sweep: H = Y · conj(X) / (|X|² + ε),
 * band-limited with a raised-cosine taper to the swept range (plus 1/3 octave).
 *
 * The result is a circular impulse response of length [fftSize]: the linear
 * response sits at the sweep's arrival time, harmonic responses precede it and
 * wrap around to the end of the buffer.
 */
class Deconvolver(val sweep: ExpSweep, val fftSize: Int) {

    private val fft = Fft(fftSize)
    private val invRe = DoubleArray(fftSize)
    private val invIm = DoubleArray(fftSize)

    init {
        require(fftSize >= sweep.length) { "FFT size $fftSize shorter than sweep (${sweep.length})" }
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        sweep.samples.copyInto(re)
        fft.forward(re, im)
        var maxPower = 0.0
        for (k in 0..fftSize / 2) maxPower = maxOf(maxPower, re[k] * re[k] + im[k] * im[k])
        val epsilon = REGULARIZATION * maxPower
        val binHz = sweep.sampleRate.toDouble() / fftSize
        val highPass = ButterworthHighPass(HIGH_PASS_ORDER, sweep.startHz / 2.0)
        for (k in 0..fftSize / 2) {
            val f = k * binHz
            val w = highWeight(f)
            if (w == 0.0 || f < sweep.startHz / 4.0) continue
            val p = re[k] * re[k] + im[k] * im[k]
            val g = w / (p + epsilon)
            // conj(X) · g · HP(f)
            val ar = re[k] * g
            val ai = -im[k] * g
            val (hr, hi) = highPass.response(f)
            invRe[k] = ar * hr - ai * hi
            invIm[k] = ar * hi + ai * hr
            if (k == fftSize / 2) invIm[k] = 0.0
            if (k in 1 until fftSize / 2) {
                invRe[fftSize - k] = invRe[k]
                invIm[fftSize - k] = -invIm[k]
            }
        }
    }

    /**
     * Zero-phase taper above the sweep. At the low end a causal (minimum-phase)
     * Butterworth high-pass is used instead, because the pre-ringing of a
     * zero-phase low-frequency taper would be cut off by the IR window and
     * cause a broad dip in the bass response.
     */
    private fun highWeight(f: Double): Double {
        val highEdge = min(sweep.endHz * TAPER_RATIO, sweep.sampleRate / 2.0)
        return when {
            f >= highEdge -> 0.0
            f > sweep.endHz -> raisedCosine(ln(highEdge / f) / ln(highEdge / sweep.endHz))
            else -> 1.0
        }
    }

    private fun raisedCosine(x: Double) = 0.5 * (1.0 - cos(PI * x.coerceIn(0.0, 1.0)))

    /**
     * Transfer function spectrum (full complex FFT, length [fftSize]) of
     * [length] samples of [signal] starting at [start]. Samples outside the
     * signal are treated as zero.
     */
    fun transferSpectrum(signal: FloatArray, start: Int, length: Int): Pair<DoubleArray, DoubleArray> {
        require(length <= fftSize) { "Segment of $length samples does not fit FFT size $fftSize" }
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        for (i in 0 until length) {
            val src = start + i
            if (src >= 0 && src < signal.size) re[i] = signal[src].toDouble()
        }
        fft.forward(re, im)
        for (k in 0 until fftSize) {
            val yr = re[k]
            val yi = im[k]
            re[k] = yr * invRe[k] - yi * invIm[k]
            im[k] = yr * invIm[k] + yi * invRe[k]
        }
        return re to im
    }

    /** Inverse FFT of a transfer spectrum; destroys the input arrays and returns the real part. */
    fun toImpulseResponse(re: DoubleArray, im: DoubleArray): DoubleArray {
        fft.inverse(re, im)
        return re
    }

    fun impulseResponse(signal: FloatArray, start: Int, length: Int): DoubleArray {
        val (re, im) = transferSpectrum(signal, start, length)
        return toImpulseResponse(re, im)
    }

    companion object {
        private const val REGULARIZATION = 1e-6
        private const val HIGH_PASS_ORDER = 8
        private val TAPER_RATIO = 2.0.pow(1.0 / 3.0)

        /** Index of the largest magnitude sample, refined by parabolic interpolation. */
        fun findPeak(ir: DoubleArray): Double {
            var best = 0
            var bestValue = -1.0
            for (i in ir.indices) {
                val v = abs(ir[i])
                if (v > bestValue) {
                    bestValue = v
                    best = i
                }
            }
            val n = ir.size
            val a = abs(ir[(best - 1 + n) % n])
            val b = bestValue
            val c = abs(ir[(best + 1) % n])
            val denominator = a - 2 * b + c
            val delta = if (denominator < 0.0) (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5) else 0.0
            return best + delta
        }
    }
}
