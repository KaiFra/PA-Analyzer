package de.flkaiser.paanalyzer.dsp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Logarithmically spaced frequency points. */
object LogGrid {
    fun create(startHz: Double, endHz: Double, pointsPerOctave: Int): DoubleArray {
        require(startHz > 0 && endHz > startHz && pointsPerOctave > 0)
        val octaves = ln(endHz / startHz) / ln(2.0)
        val n = max(2, (octaves * pointsPerOctave).roundToInt() + 1)
        return DoubleArray(n) { startHz * (endHz / startHz).pow(it.toDouble() / (n - 1)) }
    }
}

/**
 * One-sided power spectrum |H(f)|² sampled at bins k·[binHz], k = 0..N/2,
 * with fractional-octave averaging.
 */
class PowerSpectrum(val power: DoubleArray, val binHz: Double) {

    private val prefix = DoubleArray(power.size + 1).also {
        for (i in power.indices) it[i + 1] = it[i] + power[i]
    }

    val maxFrequency: Double get() = (power.size - 1) * binHz

    /**
     * Power at [frequencyHz]. With [fraction] = 0 the bins are linearly interpolated,
     * otherwise all bins within a 1/[fraction]-octave band centred on the frequency
     * are averaged (falling back to interpolation where the band holds fewer than two bins).
     */
    fun powerAt(frequencyHz: Double, fraction: Int = 0): Double {
        if (fraction > 0) {
            val half = 2.0.pow(1.0 / (2.0 * fraction))
            val lo = max(0, ceil(frequencyHz / half / binHz).toInt())
            val hi = min(power.size - 1, floor(frequencyHz * half / binHz).toInt())
            if (hi - lo + 1 >= 2) return (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
        }
        val pos = frequencyHz / binHz
        if (pos <= 0.0) return power[0]
        if (pos >= power.size - 1) return power[power.size - 1]
        val i = floor(pos).toInt()
        val frac = pos - i
        return power[i] * (1.0 - frac) + power[i + 1] * frac
    }

    fun dbAt(frequencyHz: Double, fraction: Int = 0): Double = powerToDb(powerAt(frequencyHz, fraction))
}

internal const val POWER_FLOOR = 1e-30

fun powerToDb(power: Double): Double = 10.0 * log10(max(power, POWER_FLOOR))

fun dbToPercent(db: Double): Double = 100.0 * 10.0.pow(db / 20.0)
