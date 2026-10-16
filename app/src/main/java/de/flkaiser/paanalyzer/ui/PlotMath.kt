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

    /** For narrow (zoomed) ranges: 1..9 × decade ticks, thinned to at most [maxTicks]. */
    fun denseFrequencyTicks(min: Double, max: Double, maxTicks: Int = 10): List<Double> {
        val all = mutableListOf<Double>()
        var decade = 10.0.pow(floor(log10(min)))
        while (decade <= max) {
            for (m in 1..9) {
                val f = m * decade
                if (f >= min * 0.999 && f <= max * 1.001) all += f
            }
            decade *= 10
        }
        if (all.size <= maxTicks) return all
        val step = (all.size + maxTicks - 1) / maxTicks
        return all.filterIndexed { i, _ -> i % step == 0 }
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

    /**
     * Automatic value range for the given curves: the top lies just above the largest
     * value, the bottom just below the smallest one, with the span kept within
     * [minSpan]..[maxSpan]. Values far below the top (e.g. -300 dB for "silence") are ignored.
     */
    fun autoRange(values: List<DoubleArray>, minSpan: Double = 40.0, maxSpan: Double = 110.0, step: Double = 10.0): ClosedFloatingPointRange<Double> {
        val finite = values.flatMap { it.asList() }.filter { it.isFinite() }
        if (finite.isEmpty()) return -minSpan..0.0
        val top = ceil((finite.max() + 3.0) / step) * step + 0.0
        val usable = finite.filter { it >= top - maxSpan }
        val bottom = floor((usable.min() - 3.0) / step) * step + 0.0
        val span = (top - bottom).coerceIn(minSpan, maxSpan)
        return (top - span)..top
    }

    /**
     * Zooms a logarithmic range by [factor] (> 1 zooms in) around the point at
     * [anchor] (0 = left edge, 1 = right edge), staying within [limit] and never
     * narrower than [minRatio] (max / min).
     */
    fun zoomLog(
        range: ClosedFloatingPointRange<Double>,
        factor: Double,
        anchor: Double,
        limit: ClosedFloatingPointRange<Double>,
        minRatio: Double = 1.5,
    ): ClosedFloatingPointRange<Double> {
        val span = ln(range.endInclusive / range.start)
        val newSpan = (span / factor).coerceIn(ln(minRatio), ln(limit.endInclusive / limit.start))
        val anchorValue = range.start * kotlin.math.exp(anchor * span)
        return fitLog(anchorValue * kotlin.math.exp(-anchor * newSpan), newSpan, limit)
    }

    /** Moves a logarithmic range by [fraction] of its width (positive = towards lower frequencies). */
    fun panLog(range: ClosedFloatingPointRange<Double>, fraction: Double, limit: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double> {
        val span = ln(range.endInclusive / range.start)
        return fitLog(range.start * kotlin.math.exp(-fraction * span), span, limit)
    }

    private fun fitLog(start: Double, span: Double, limit: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double> {
        var lo = max(start, limit.start)
        var hi = lo * kotlin.math.exp(span)
        if (hi > limit.endInclusive) {
            hi = limit.endInclusive
            lo = max(limit.start, hi / kotlin.math.exp(span))
        }
        return lo..hi
    }

    /** Linear counterpart of [zoomLog]; [anchor] 0 = bottom, 1 = top. */
    fun zoomLinear(
        range: ClosedFloatingPointRange<Double>,
        factor: Double,
        anchor: Double,
        limit: ClosedFloatingPointRange<Double>,
        minSpan: Double = 6.0,
    ): ClosedFloatingPointRange<Double> {
        val span = range.endInclusive - range.start
        val newSpan = (span / factor).coerceIn(minSpan, limit.endInclusive - limit.start)
        val anchorValue = range.start + anchor * span
        return fitLinear(anchorValue - anchor * newSpan, newSpan, limit)
    }

    /** Moves a linear range by [fraction] of its span (positive = towards higher values). */
    fun panLinear(range: ClosedFloatingPointRange<Double>, fraction: Double, limit: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double> {
        val span = range.endInclusive - range.start
        return fitLinear(range.start + fraction * span, span, limit)
    }

    private fun fitLinear(start: Double, span: Double, limit: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double> {
        val lo = max(limit.start, min(start, limit.endInclusive - span))
        return lo..(lo + span)
    }
}
