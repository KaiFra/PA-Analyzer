package de.flkaiser.paanalyzer.dsp

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The response the EQ aims for, relative to the mid-band level. */
enum class EqTarget(val label: String, val description: String) {
    FLAT("Flat", "Neutral; good for monitors and speech"),
    TILT("Gentle tilt", "Falls 0.7 dB per octave; usually sounds more natural in a room"),
    LIVE("Live / bass boost", "Flat with about +6 dB below 100 Hz, for music and dance"),
    ;

    fun db(frequencyHz: Double): Double = when (this) {
        FLAT -> 0.0
        TILT -> (-0.7 * log2(frequencyHz / 1_000.0)).coerceIn(-4.0, 4.0)
        LIVE -> 6.0 / (1.0 + (frequencyHz / 100.0).pow(2))
    }
}

data class EqSettings(
    val target: EqTarget = EqTarget.FLAT,
    /** Number of bands available on the mixer or processor. */
    val filterCount: Int = 6,
    val minHz: Double = 40.0,
    /** Phone microphones are unreliable at the very top, so correction stops below 20 kHz by default. */
    val maxHz: Double = 12_000.0,
    /** Boosting dips costs headroom and rarely helps (room cancellations can't be filled), so it is limited. */
    val maxBoostDb: Double = 3.0,
    val maxCutDb: Double = 12.0,
    val suggestHighPass: Boolean = true,
) {
    fun validate(): List<String> = buildList {
        if (filterCount !in 1..MAX_FILTERS) add("Number of filters must be between 1 and $MAX_FILTERS.")
        if (minHz < 10.0 || maxHz > 22_000.0 || maxHz < minHz * 2) add("The correction range must span at least an octave within 10 Hz – 22 kHz.")
        if (maxBoostDb !in 0.0..12.0) add("Maximum boost must be between 0 and 12 dB.")
        if (maxCutDb !in 1.0..24.0) add("Maximum cut must be between 1 and 24 dB.")
    }

    companion object {
        const val MAX_FILTERS = 12
    }
}

/** The suggested EQ and what the response is expected to look like with it. */
class EqSuggestion(
    val settings: EqSettings,
    val frequencies: DoubleArray,
    /** Measured response (smoothed), shifted so that it sits on the target in the mid band. */
    val measuredDb: DoubleArray,
    val targetDb: DoubleArray,
    /** Combined response of all suggested filters including the high-pass. */
    val eqDb: DoubleArray,
    val predictedDb: DoubleArray,
    /** Sorted by frequency, rounded to values that can be dialled in. */
    val filters: List<EqFilter>,
    val highPass: HighPassSuggestion?,
    /** Range that was corrected (the requested range limited by the speaker's roll-off). */
    val correctedFromHz: Double,
    val correctedToHz: Double,
    /** RMS deviation from the target within the corrected range. */
    val rmsBeforeDb: Double,
    val rmsAfterDb: Double,
    val notes: List<String>,
)

/**
 * Suggests parametric EQ settings that bring a measured response closer to a target:
 *
 * 1. Smooth the response to 1/6 octave (narrow room effects can't be fixed with EQ),
 *    align it with the target in the mid band, and find where the speaker rolls off.
 * 2. Add filters one at a time where they help most (bell at the largest deviation,
 *    or a shelf for broad tilts); peaks count more than dips, because filling dips
 *    with boost costs headroom and room cancellations can't be filled anyway.
 * 3. Optimize all filters together (pattern search) within the gain and Q limits.
 * 4. Round to values a mixer can dial in and recompute the prediction with them.
 */
object EqDesigner {

    private const val SMOOTHING_FRACTION = 6
    private const val DIP_WEIGHT = 0.35
    private const val ROLL_OFF_DB = -6.0
    private const val MIN_Q = 0.4
    private const val MAX_Q = 8.0
    private const val MIN_USEFUL_GAIN_DB = 1.0
    private const val BOOST_LIMIT_DB = -3.0
    private val ROLL_OFF_MARGIN = sqrt(2.0)
    /** Weight of exceeding the boost/cut limits with the combined EQ (several bands at the same spot). */
    private const val LIMIT_PENALTY = 20.0

    fun design(frequencies: DoubleArray, responseDb: DoubleArray, settings: EqSettings): EqSuggestion {
        require(frequencies.size == responseDb.size && frequencies.size >= 8) { "Need a frequency response" }
        val errors = settings.validate()
        require(errors.isEmpty()) { errors.first() }
        val notes = mutableListOf<String>()

        val n = frequencies.size
        val smoothed = smooth(frequencies, responseDb)
        val target = DoubleArray(n) { settings.target.db(frequencies[it]) }

        // Align the measurement with the target over the mid band.
        val midLo = max(settings.minHz, 200.0)
        val midHi = min(settings.maxHz, 2_000.0)
        val midIdx = frequencies.indices.filter { frequencies[it] in midLo..midHi && smoothed[it].isFinite() }
            .ifEmpty { frequencies.indices.filter { frequencies[it] in settings.minHz..settings.maxHz && smoothed[it].isFinite() } }
        require(midIdx.isNotEmpty()) { "The measurement does not cover the correction range." }
        // Median rather than mean, so a single resonance doesn't shift the reference level.
        val offset = median(midIdx.map { smoothed[it] - target[it] })
        val measured = DoubleArray(n) { smoothed[it] - offset }

        // Roll-off points: where the response falls 6 dB below the target, starting from the mid band.
        val relative = DoubleArray(n) { measured[it] - target[it] }
        val lowRollOff = rollOff(frequencies, relative, startHz = midLo, downwards = true, level = ROLL_OFF_DB)
        val highRollOff = rollOff(frequencies, relative, startHz = midHi, downwards = false, level = ROLL_OFF_DB)
        // Boosting where the speaker already falls off costs excursion and headroom for little gain:
        // boosts are only allowed half an octave inside the -3 dB points.
        val boostFrom = rollOff(frequencies, relative, startHz = midLo, downwards = true, level = BOOST_LIMIT_DB)
            ?.let { it * ROLL_OFF_MARGIN } ?: 0.0
        val boostTo = rollOff(frequencies, relative, startHz = midHi, downwards = false, level = BOOST_LIMIT_DB)
            ?.let { it / ROLL_OFF_MARGIN } ?: Double.MAX_VALUE
        var from = max(settings.minHz, frequencies.first())
        var to = min(settings.maxHz, frequencies.last())
        var highPass: HighPassSuggestion? = null
        if (lowRollOff != null && lowRollOff > from) {
            // Start half an octave above the roll-off, so the EQ doesn't fight the slope.
            from = min(lowRollOff * ROLL_OFF_MARGIN, midLo)
            if (settings.suggestHighPass) {
                highPass = HighPassSuggestion(roundSignificant(lowRollOff * 0.9, 2))
                notes += "The system rolls off below about ${formatHz(lowRollOff)}. A high-pass just below that protects the drivers " +
                    "from excursion they can't turn into sound; the EQ does not try to boost below it."
            } else {
                notes += "The system rolls off below about ${formatHz(lowRollOff)}; the EQ does not try to boost below it."
            }
        }
        if (highRollOff != null && highRollOff < to) {
            to = max(highRollOff / ROLL_OFF_MARGIN, midHi)
            notes += "The response falls off above about ${formatHz(highRollOff)} (speaker or phone microphone); no correction above it."
        }
        if (to < from * 2) {
            to = min(settings.maxHz, frequencies.last())
            from = max(settings.minHz, frequencies.first())
            notes += "The usable range is very narrow; check the measurement."
        }

        val range = frequencies.indices.filter { frequencies[it] in from..to && measured[it].isFinite() }
        val model = Model(frequencies, measured, target, range, highPass, settings.maxBoostDb, settings.maxCutDb, boostFrom, boostTo)
        val rmsBefore = model.rms(emptyList())

        // Greedy: add the filter that helps most, then optimize everything together.
        var filters = mutableListOf<EqFilter>()
        val limits = Limits(from, to, settings.maxBoostDb, settings.maxCutDb)
        var cost = model.cost(filters)
        for (k in 0 until settings.filterCount) {
            val candidate = candidates(model, filters, limits)
                .map { optimize(model, filters + it, limits, onlyLast = true) }
                .minByOrNull { model.cost(it) } ?: break
            val newCost = model.cost(candidate)
            // Stop when another band would hardly help.
            if (newCost > cost * 0.92 || model.rms(filters) < 0.4) break
            filters = candidate.toMutableList()
            cost = newCost
        }
        filters = optimize(model, filters, limits, onlyLast = false).toMutableList()

        // Round to dialable values and drop bands that no longer do anything.
        val rounded = filters.map { roundFilter(it, limits) }.filter { abs(it.gainDb) >= MIN_USEFUL_GAIN_DB }.sortedBy { it.frequencyHz }
        val eq = model.eqResponse(rounded, allPoints = true)
        val predicted = DoubleArray(n) { measured[it] + eq[it] }
        val rmsAfter = model.rms(rounded)
        if (rounded.isEmpty()) notes += "The response is already close to the target; no EQ needed in the corrected range."
        if (rounded.any { it.gainDb > 0 }) notes += "Boosts reduce headroom: lower the input gain or limiter threshold by the largest boost."

        return EqSuggestion(
            settings = settings,
            frequencies = frequencies,
            measuredDb = measured,
            targetDb = target,
            eqDb = eq,
            predictedDb = predicted,
            filters = rounded,
            highPass = highPass,
            correctedFromHz = from,
            correctedToHz = to,
            rmsBeforeDb = rmsBefore,
            rmsAfterDb = rmsAfter,
            notes = notes,
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Power average over ±1/12 octave (1/6-octave smoothing) on the log grid. */
    internal fun smooth(frequencies: DoubleArray, db: DoubleArray): DoubleArray {
        val pointsPerOctave = 1.0 / log2(frequencies[1] / frequencies[0])
        val half = max(0, (pointsPerOctave / (2.0 * SMOOTHING_FRACTION)).roundToInt())
        return DoubleArray(frequencies.size) { i ->
            var sum = 0.0
            var count = 0
            for (j in max(0, i - half)..min(frequencies.size - 1, i + half)) {
                if (db[j].isFinite()) {
                    sum += 10.0.pow(db[j] / 10.0)
                    count++
                }
            }
            if (count == 0) Double.NaN else 10.0 * kotlin.math.log10(sum / count)
        }
    }

    /** Frequency where [relative] first drops below [level] when moving away from [startHz]. */
    private fun rollOff(frequencies: DoubleArray, relative: DoubleArray, startHz: Double, downwards: Boolean, level: Double): Double? {
        val start = frequencies.indices.minByOrNull { abs(ln(frequencies[it] / startHz)) } ?: return null
        val indices = if (downwards) (start downTo 0) else (start until frequencies.size)
        var previous = start
        for (i in indices) {
            val v = relative[i]
            if (v.isFinite() && v < level) {
                // Interpolate between the last point above and this one (log frequency).
                val a = relative[previous]
                val t = if (a.isFinite() && a != v) ((a - level) / (a - v)).coerceIn(0.0, 1.0) else 0.0
                return frequencies[previous] * (frequencies[i] / frequencies[previous]).pow(t)
            }
            previous = i
        }
        return null
    }

    private class Limits(val fromHz: Double, val toHz: Double, val maxBoostDb: Double, val maxCutDb: Double) {
        fun gain(g: Double) = g.coerceIn(-maxCutDb, maxBoostDb)
        fun frequency(f: Double) = f.coerceIn(fromHz, toHz)
        fun q(q: Double) = q.coerceIn(MIN_Q, MAX_Q)
    }

    /** Evaluates filter sets on the log grid. */
    private class Model(
        val frequencies: DoubleArray,
        val measured: DoubleArray,
        val target: DoubleArray,
        val range: List<Int>,
        val highPass: HighPassSuggestion?,
        val maxBoostDb: Double,
        val maxCutDb: Double,
        val boostFromHz: Double,
        val boostToHz: Double,
    ) {
        private val cosW = DoubleArray(frequencies.size) { cos(2.0 * PI * frequencies[it] / EQ_SAMPLE_RATE) }
        private val cos2W = DoubleArray(frequencies.size) { cos(4.0 * PI * frequencies[it] / EQ_SAMPLE_RATE) }
        private val highPassDb = DoubleArray(frequencies.size) { highPass?.responseDb(frequencies[it]) ?: 0.0 }

        fun eqResponse(filters: List<EqFilter>, allPoints: Boolean): DoubleArray {
            val out = highPassDb.copyOf()
            val biquads = filters.map { Biquad.of(it, EQ_SAMPLE_RATE) }
            val indices = if (allPoints) frequencies.indices.toList() else range
            for (i in indices) {
                for (b in biquads) out[i] += 10.0 * kotlin.math.log10(max(b.magnitudeSquared(cosW[i], cos2W[i]), 1e-30))
            }
            return out
        }

        /** Deviation from the target (positive = too loud) at every grid point. */
        fun error(filters: List<EqFilter>): DoubleArray {
            val eq = eqResponse(filters, allPoints = false)
            return DoubleArray(frequencies.size) { measured[it] + eq[it] - target[it] }
        }

        /**
         * Weighted mean square error; dips (response below target) count less than peaks.
         * The combined EQ (all bands together) must stay within the boost and cut limits.
         */
        fun cost(filters: List<EqFilter>): Double {
            val eq = eqResponse(filters, allPoints = false)
            var sum = 0.0
            for (i in range) {
                val v = measured[i] + eq[i] - target[i]
                sum += if (v > 0) v * v else DIP_WEIGHT * v * v
                val bands = eq[i] - highPassDb[i]
                val boostLimit = if (frequencies[i] in boostFromHz..boostToHz) maxBoostDb else 0.0
                val over = max(bands - boostLimit, -maxCutDb - bands)
                if (over > 0) sum += LIMIT_PENALTY * over * over
            }
            return sum / max(1, range.size)
        }

        fun rms(filters: List<EqFilter>): Double {
            val e = error(filters)
            return sqrt(range.sumOf { e[it] * e[it] } / max(1, range.size))
        }
    }

    /** Possible next filters: a bell at the largest weighted deviation and shelves at both ends. */
    private fun candidates(model: Model, filters: List<EqFilter>, limits: Limits): List<EqFilter> {
        val e = model.error(filters)
        val range = model.range
        val f = model.frequencies
        val result = mutableListOf<EqFilter>()

        val peak = range.maxByOrNull { val v = e[it]; if (v > 0) v else sqrt(DIP_WEIGHT) * -v }
        if (peak != null) {
            val level = e[peak]
            // Width from the points where the deviation falls to half its value.
            var lo = peak
            while (lo > range.first() && e[lo - 1] * level > 0 && abs(e[lo - 1]) > abs(level) / 2) lo--
            var hi = peak
            while (hi < range.last() && e[hi + 1] * level > 0 && abs(e[hi + 1]) > abs(level) / 2) hi++
            val q = limits.q(f[peak] / max(f[hi] - f[lo], f[peak] / 20))
            result += EqFilter(FilterType.PEAK, f[peak], limits.gain(-level), q)
        }
        for (octaves in listOf(1.0, 2.0)) {
            val lowCorner = limits.frequency(limits.fromHz * 2.0.pow(octaves))
            val below = range.filter { f[it] <= lowCorner }
            if (below.isNotEmpty()) result += EqFilter(FilterType.LOW_SHELF, lowCorner, limits.gain(-below.sumOf { e[it] } / below.size))
            val highCorner = limits.frequency(limits.toHz / 2.0.pow(octaves))
            val above = range.filter { f[it] >= highCorner }
            if (above.isNotEmpty()) result += EqFilter(FilterType.HIGH_SHELF, highCorner, limits.gain(-above.sumOf { e[it] } / above.size))
        }
        return result
    }

    /**
     * Pattern search over frequency (log), gain and Q (log) of the filters: try a step up
     * and down for every parameter, keep improvements, halve the steps when nothing helps.
     */
    private fun optimize(model: Model, start: List<EqFilter>, limits: Limits, onlyLast: Boolean): List<EqFilter> {
        val filters = start.toMutableList()
        var best = model.cost(filters)
        var fStep = ln(2.0) / 6
        var gStep = 1.0
        var qStep = ln(1.5)
        val indices = if (onlyLast) listOf(filters.lastIndex) else filters.indices.toList()
        var rounds = 0
        while (fStep > ln(2.0) / 96 && rounds < 400) {
            rounds++
            var improved = false
            for (i in indices) {
                val moves = buildList {
                    for (s in listOf(1.0, -1.0)) {
                        add(filters[i].copy(frequencyHz = limits.frequency(filters[i].frequencyHz * exp(s * fStep))))
                        add(filters[i].copy(gainDb = limits.gain(filters[i].gainDb + s * gStep)))
                        if (filters[i].type == FilterType.PEAK) add(filters[i].copy(q = limits.q(filters[i].q * exp(s * qStep))))
                    }
                }
                for (move in moves) {
                    val old = filters[i]
                    filters[i] = move
                    val c = model.cost(filters)
                    if (c < best - 1e-9) {
                        best = c
                        improved = true
                    } else {
                        filters[i] = old
                    }
                }
            }
            if (!improved) {
                fStep /= 2
                gStep /= 2
                qStep /= 2
            }
        }
        return filters
    }

    private fun roundFilter(filter: EqFilter, limits: Limits) = filter.copy(
        frequencyHz = roundSignificant(filter.frequencyHz, 3),
        gainDb = limits.gain(round(filter.gainDb * 2) / 2),
        q = if (filter.type == FilterType.PEAK) roundSignificant(filter.q, 2) else filter.q,
    )

    fun roundSignificant(v: Double, digits: Int): Double = BigDecimal(v).round(MathContext(digits)).toDouble()

    fun formatHz(f: Double): String = when {
        f >= 1_000 -> "${BigDecimal(f / 1_000).round(MathContext(3)).stripTrailingZeros().toPlainString()} kHz"
        else -> "${f.roundToInt()} Hz"
    }
}
