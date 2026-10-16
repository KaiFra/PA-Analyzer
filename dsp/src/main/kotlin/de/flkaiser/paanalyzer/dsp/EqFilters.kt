package de.flkaiser.paanalyzer.dsp

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Sample rate used to evaluate the suggested filters; most PA DSPs and digital mixers run at 48 kHz. */
const val EQ_SAMPLE_RATE = 48_000.0

enum class FilterType(val label: String, val apoCode: String) {
    PEAK("Peak (bell)", "PK"),
    LOW_SHELF("Low shelf", "LSC"),
    HIGH_SHELF("High shelf", "HSC"),
}

/**
 * One parametric EQ band as found on mixers and speaker processors, using the
 * common RBJ "Audio EQ Cookbook" definitions. Shelves use a slope of S = 1 (Q 0.71).
 */
data class EqFilter(val type: FilterType, val frequencyHz: Double, val gainDb: Double, val q: Double = SHELF_Q) {

    /** Bandwidth in octaves between the half-gain points, as some mixers show it instead of Q. */
    val bandwidthOctaves: Double get() = 2.0 / ln(2.0) * asinh(1.0 / (2.0 * q))

    fun responseDb(frequencyHz: Double, sampleRate: Double = EQ_SAMPLE_RATE): Double =
        Biquad.of(this, sampleRate).magnitudeDb(frequencyHz, sampleRate)

    companion object {
        val SHELF_Q = 1.0 / sqrt(2.0)
    }
}

/** Suggested high-pass (Linkwitz-Riley, two cascaded 2nd-order Butterworth sections). */
data class HighPassSuggestion(val frequencyHz: Double, val slopeDbPerOctave: Int = 24) {
    fun responseDb(frequencyHz: Double, sampleRate: Double = EQ_SAMPLE_RATE): Double =
        Biquad.highPass(this.frequencyHz, sampleRate).magnitudeDb(frequencyHz, sampleRate) * (slopeDbPerOctave / 12)
}

/** Normalized biquad coefficients (a0 = 1). */
internal class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    /** |H(e^jw)|² from the coefficients; [cosW] and [cos2W] are cos(w) and cos(2w). */
    fun magnitudeSquared(cosW: Double, cos2W: Double): Double {
        val num = b0 * b0 + b1 * b1 + b2 * b2 + 2.0 * (b0 * b1 + b1 * b2) * cosW + 2.0 * b0 * b2 * cos2W
        val den = 1.0 + a1 * a1 + a2 * a2 + 2.0 * (a1 + a1 * a2) * cosW + 2.0 * a2 * cos2W
        return num / den
    }

    fun magnitudeDb(frequencyHz: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        return 10.0 * log10(max(magnitudeSquared(cos(w), cos(2.0 * w)), 1e-30))
    }

    companion object {
        private fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun of(filter: EqFilter, sampleRate: Double): Biquad {
            val a = 10.0.pow(filter.gainDb / 40.0)
            val w0 = 2.0 * PI * filter.frequencyHz / sampleRate
            val c = cos(w0)
            val alpha = sin(w0) / (2.0 * filter.q)
            return when (filter.type) {
                FilterType.PEAK -> normalized(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
                FilterType.LOW_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    normalized(
                        a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - k),
                        (a + 1) + (a - 1) * c + k, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k,
                    )
                }
                FilterType.HIGH_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    normalized(
                        a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - k),
                        (a + 1) - (a - 1) * c + k, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k,
                    )
                }
            }
        }

        fun highPass(frequencyHz: Double, sampleRate: Double): Biquad {
            val w0 = 2.0 * PI * frequencyHz / sampleRate
            val c = cos(w0)
            val alpha = sin(w0) / (2.0 * EqFilter.SHELF_Q)
            return normalized((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }
    }
}
