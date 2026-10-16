package de.flkaiser.paanalyzer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Frequency response of an analog Butterworth high-pass of the given order (causal, minimum phase). */
class ButterworthHighPass(private val order: Int, private val cutoffHz: Double) {

    init {
        require(order >= 1 && cutoffHz > 0.0)
    }

    // Normalized poles on the left half of the unit circle.
    private val poleRe = DoubleArray(order) { k -> cos(PI * (2.0 * (k + 1) + order - 1) / (2.0 * order)) }
    private val poleIm = DoubleArray(order) { k -> sin(PI * (2.0 * (k + 1) + order - 1) / (2.0 * order)) }

    /** H(j·2πf) as (real, imaginary). H(s) = Π s / (s - p_k) with s normalized to the cutoff. */
    fun response(frequencyHz: Double): Pair<Double, Double> {
        if (frequencyHz <= 0.0) return 0.0 to 0.0
        val w = frequencyHz / cutoffHz
        var re = 1.0
        var im = 0.0
        for (k in 0 until order) {
            // factor = jw / (jw - p) = jw · conj(jw - p) / |jw - p|²
            val dr = -poleRe[k]
            val di = w - poleIm[k]
            val mag = dr * dr + di * di
            val fr = (w * di) / mag
            val fi = (w * dr) / mag
            val nr = re * fr - im * fi
            val ni = re * fi + im * fr
            re = nr
            im = ni
        }
        return re to im
    }
}
