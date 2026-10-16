package de.flkaiser.paanalyzer.dsp

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Combines measurements made at several positions in the audience area into one
 * ("spatial average"), so an EQ based on it corrects the speaker rather than the
 * room at a single spot.
 *
 * Levels are power-averaged (RMS of the magnitudes, as REW does): a dip at one position
 * is filled by the others, while a resonance of the speaker shows at every position and
 * stays. Before averaging, each position is shifted to the level of the first one in the
 * mid band, because positions further away are simply quieter.
 */
object PositionAverage {

    const val MIN_POSITIONS = 2
    const val ALIGN_FROM_HZ = 200.0
    const val ALIGN_TO_HZ = 2_000.0

    fun average(results: List<MeasurementResult>, alignLevels: Boolean = true): MeasurementResult {
        require(results.size >= MIN_POSITIONS) { "Choose at least $MIN_POSITIONS measurements to average." }
        val first = results.first()
        val lo = results.maxOf { it.frequencies.first() }
        val hi = results.minOf { it.frequencies.last() }
        require(hi >= lo * 2) { "The measurements don't share a frequency range of at least an octave." }
        val grid = first.frequencies.filter { it in lo..hi }.toDoubleArray()

        val fundamentals = results.map { interpolate(it.frequencies, it.fundamentalDb, grid) }
        val reference = midLevel(grid, fundamentals.first())
        val offsets = fundamentals.map { if (alignLevels) reference - midLevel(grid, it) else 0.0 }
        val aligned = fundamentals.mapIndexed { k, f -> DoubleArray(grid.size) { f[it] + offsets[k] } }
        val fundamental = powerMean(aligned)

        // Distortion is averaged as absolute level (fundamental + relative level), then made relative again.
        fun relativeAverage(curve: (MeasurementResult) -> DoubleArray): DoubleArray {
            val absolute = results.mapIndexed { k, r ->
                val rel = interpolate(r.frequencies, curve(r), grid)
                DoubleArray(grid.size) { aligned[k][it] + rel[it] }
            }
            val mean = powerMean(absolute)
            return DoubleArray(grid.size) { mean[it] - fundamental[it] }
        }
        val thd = relativeAverage { it.thdDb }
        val orders = results.map { r -> r.harmonics.map { it.order }.toSet() }.reduce { a, b -> a intersect b }.sorted()
        val harmonics = orders.map { order -> HarmonicCurve(order, relativeAverage { r -> r.harmonics.first { it.order == order }.levelDb }) }

        return MeasurementResult(
            settings = first.settings,
            frequencies = grid,
            fundamentalDb = fundamental,
            thdDb = thd,
            harmonics = harmonics,
            rubBuzz = averageRubBuzz(results.map { it.rubBuzz }, lo, hi),
            delayMs = results.map { it.delayMs }.average(),
            inputPeakDbfs = results.maxOf { it.inputPeakDbfs },
            irSnrDb = results.minOf { it.irSnrDb },
            warnings = results.flatMap { it.warnings }.distinct(),
        )
    }

    /** Rub & buzz is already relative to the fundamental at each position, so it is averaged as it is. */
    private fun averageRubBuzz(all: List<RubBuzzResult>, lo: Double, hi: Double): RubBuzzResult {
        if (all.any { it.size < 2 }) return RubBuzzResult.EMPTY
        val grid = all.first().frequencies.filter { it in lo..hi }.toDoubleArray()
        if (grid.isEmpty()) return RubBuzzResult.EMPTY
        fun mean(curve: (RubBuzzResult) -> DoubleArray) = powerMean(all.map { interpolate(it.frequencies, curve(it), grid) })
        val crest = all.map { interpolate(it.frequencies, it.crestDb, grid) }
        return RubBuzzResult(
            frequencies = grid,
            rmsDb = mean { it.rmsDb },
            peakDb = mean { it.peakDb },
            crestDb = DoubleArray(grid.size) { i -> crest.map { it[i] }.filter { it.isFinite() }.let { if (it.isEmpty()) Double.NaN else it.average() } },
            noiseFloorDb = mean { it.noiseFloorDb },
        )
    }

    /** Median level in the mid band (whole range if the mid band has no data). */
    fun midLevel(frequencies: DoubleArray, db: DoubleArray): Double {
        val mid = frequencies.indices.filter { frequencies[it] in ALIGN_FROM_HZ..ALIGN_TO_HZ && db[it].isFinite() }
            .ifEmpty { frequencies.indices.filter { db[it].isFinite() } }
        if (mid.isEmpty()) return 0.0
        val sorted = mid.map { db[it] }.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /** Power mean of dB curves point by point; NaN values are skipped, NaN if none is left. */
    internal fun powerMean(curves: List<DoubleArray>): DoubleArray = DoubleArray(curves.first().size) { i ->
        var sum = 0.0
        var count = 0
        for (c in curves) {
            val v = c[i]
            if (v.isFinite()) {
                sum += 10.0.pow(v / 10.0)
                count++
            }
        }
        if (count == 0) Double.NaN else 10.0 * log10(max(sum / count, 1e-30))
    }

    /** Linear interpolation over log frequency; NaN outside [xs] or next to a NaN value. */
    fun interpolate(xs: DoubleArray, ys: DoubleArray, at: DoubleArray): DoubleArray {
        require(xs.size == ys.size)
        return DoubleArray(at.size) { k ->
            val x = at[k]
            var i = xs.binarySearch(x)
            when {
                i >= 0 -> ys[i]
                else -> {
                    i = -i - 1 // insertion point: xs[i - 1] < x < xs[i]
                    if (i == 0 || i == xs.size) {
                        // Allow tiny rounding differences at the ends.
                        val end = if (i == 0) 0 else xs.size - 1
                        if (kotlin.math.abs(ln(x / xs[end])) < 1e-9) ys[end] else Double.NaN
                    } else {
                        val t = ln(x / xs[i - 1]) / ln(xs[i] / xs[i - 1])
                        ys[i - 1] + t * (ys[i] - ys[i - 1])
                    }
                }
            }
        }
    }
}
