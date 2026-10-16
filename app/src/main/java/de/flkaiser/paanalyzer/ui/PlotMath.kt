package de.flkaiser.paanalyzer.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Axis math for the log-frequency charts. No Android dependencies. */
object PlotMath {

    /** 1-2-5 frequencies within [min, max], e.g. 20, 50, 100, 200, ... */
    fun frequencyTicks(min: Double, max: Double): List<Double> {
        val ticks = mutableListOf<Double>()
        var decade = 10.0.pow(floor(log10(min)))
        while (decade <= max) {
            for (m in listOf(1.0, 2.0, 5.0)) {
                val f = m * decade
                if (f >= min * 0.999 && f <= max * 1.001) ticks += f
            }
            decade *= 10
        }
        return ticks
    }

    fun formatFrequency(f: Double): String = when {
        f >= 1_000 -> {
            val k = f / 1_000
            if (abs(k - Math.round(k)) < 0.05) "${Math.round(k)}k" else "%.1fk".format(java.util.Locale.ROOT, k)
        }
        f >= 100 || abs(f - Math.round(f)) < 0.05 -> "${Math.round(f)}"
        else -> "%.1f".format(java.util.Locale.ROOT, f)
    }

    /** Readable value ticks with a 1/2/5 step and about [target] divisions. */
    fun valueTicks(min: Double, max: Double, target: Int = 6): List<Double> {
        if (!(max > min)) return listOf(min)
        val raw = (max - min) / target
        val magnitude = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw * 0.999 }
        val first = ceil(min / step - 1e-9) * step
        return generateSequence(first) { it + step }.takeWhile { it <= max + step * 1e-9 }.map { if (abs(it) < step * 1e-9) 0.0 else it }.toList()
    }

    fun frequencyToX(f: Double, min: Double, max: Double, width: Float): Float =
        (ln(f / min) / ln(max / min) * width).toFloat()

    fun xToFrequency(x: Float, min: Double, max: Double, width: Float): Double =
        min * (max / min).pow((x / width).toDouble().coerceIn(0.0, 1.0))

    fun valueToY(v: Double, min: Double, max: Double, height: Float): Float =
        ((max - v) / (max - min) * height).toFloat()

    /**
     * A value range of [span] covering the largest finite value with some headroom,
     * rounded to multiples of [step].
     */
    fun rangeBelowMax(values: List<DoubleArray>, span: Double, step: Double = 10.0, headroom: Double = 3.0): ClosedFloatingPointRange<Double> {
        val top = values.flatMap { it.asList() }.filter { it.isFinite() }.maxOrNull() ?: 0.0
        val upper = ceil((top + headroom) / step) * step + 0.0 // + 0.0 turns -0.0 into 0.0
        return (upper - span)..upper
    }

    /** Index of the grid point closest (on a log scale) to [f], or -1 for an empty grid. */
    fun nearestIndex(frequencies: DoubleArray, f: Double): Int {
        if (frequencies.isEmpty()) return -1
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in frequencies.indices) {
            val d = abs(ln(frequencies[i] / f))
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    fun clamp(v: Double, range: ClosedFloatingPointRange<Double>): Double = min(max(v, range.start), range.endInclusive)
}
