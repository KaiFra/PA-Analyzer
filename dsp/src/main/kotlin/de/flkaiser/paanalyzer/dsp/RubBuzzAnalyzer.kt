package de.flkaiser.paanalyzer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Rub & buzz per analysis frame, ordered by rising excitation frequency. */
class RubBuzzResult(
    /** Excitation (sweep) frequency at the frame centre. */
    val frequencies: DoubleArray,
    /** RMS of everything above order·f relative to the fundamental's RMS. */
    val rmsDb: DoubleArray,
    /** Peak of everything above order·f relative to the fundamental's RMS. */
    val peakDb: DoubleArray,
    /** Crest factor (peak / RMS) of the high-order residual; high values mean impulsive buzz. */
    val crestDb: DoubleArray,
    /** The same RMS measure applied to background noise recorded before the sweep. */
    val noiseFloorDb: DoubleArray,
) {
    val size: Int get() = frequencies.size

    companion object {
        val EMPTY = RubBuzzResult(DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0))
    }
}

/**
 * Rub & buzz with a tracking high-pass filter: the recording is cut into short
 * frames, and for each frame everything above [startOrder] times the
 * instantaneous sweep frequency is compared with the fundamental. Loose parts,
 * rubbing voice coils and rattles show up as high-order, often impulsive energy
 * that the harmonic analysis (orders 2..10) cannot resolve.
 */
class RubBuzzAnalyzer(
    private val sweep: ExpSweep,
    private val startOrder: Int,
) {
    private val fs = sweep.sampleRate.toDouble()
    val frameSize = Fft.nextPowerOfTwo((FRAME_SEC * fs).roundToInt())
    private val hop = frameSize / 4
    private val fft = Fft(frameSize)
    private val binHz = fs / frameSize
    private val window = DoubleArray(frameSize) { 0.5 * (1.0 - cos(2.0 * PI * it / frameSize)) }
    private val upperHz = min(MAX_ANALYSIS_HZ, MAX_ANALYSIS_FRACTION * fs)

    /** Frame start offsets relative to the sweep start, and their band limits. */
    private class Frame(val offset: Int, val lowHz: Double, val highHz: Double)

    private val frames: List<Frame> = buildList {
        val first = ceil(sweep.fadeInSec * fs).toInt()
        val last = floor((sweep.durationSec - sweep.fadeOutSec) * fs).toInt() - frameSize
        var offset = first
        while (offset <= last) {
            val low = sweep.frequencyAt(offset / fs)
            val high = sweep.frequencyAt((offset + frameSize) / fs)
            if (startOrder * high <= RESIDUAL_MIN_BAND * upperHz) add(Frame(offset, low, high))
            offset += hop
        }
    }

    val frameCount: Int get() = frames.size

    private class FrameMeasure(val fundamental: Double, val residual: Double, val peak: Double, val rms: Double)

    /**
     * Accumulates one recording segment. [sweepStart] is the (fractional) index
     * of the sweep's arrival in [segment]; [noiseEnd] bounds the region before
     * it that contains only background noise.
     */
    inner class Accumulator {
        private var count = 0
        private val fundamentalSum = DoubleArray(frames.size)
        private val residualSum = DoubleArray(frames.size)
        private val peakDbSum = DoubleArray(frames.size)
        private val crestDbSum = DoubleArray(frames.size)
        private val noiseSum = DoubleArray(frames.size)
        private var noiseCount = 0

        fun add(segment: DoubleArray, sweepStart: Double, noiseEnd: Int) {
            val start = sweepStart.roundToInt()
            val fundamentals = DoubleArray(frames.size)
            for ((i, frame) in frames.withIndex()) {
                val m = measureFrame(segment, start + frame.offset, frame)
                fundamentals[i] = m.fundamental
                fundamentalSum[i] += m.fundamental
                residualSum[i] += m.residual
                val fundamentalRms = rmsFromPower(m.fundamental)
                peakDbSum[i] += ratioDb(m.peak, fundamentalRms)
                crestDbSum[i] += ratioDb(m.peak, m.rms)
            }
            val noise = noisePower(segment, noiseEnd)
            if (noise != null) {
                for ((i, frame) in frames.withIndex()) {
                    noiseSum[i] += bandSum(noise, residualBins(frame)) / max(fundamentals[i], POWER_FLOOR)
                }
                noiseCount++
            }
            count++
        }

        fun result(): RubBuzzResult {
            if (count == 0 || frames.isEmpty()) return RubBuzzResult.EMPTY
            val freq = DoubleArray(frames.size) { sqrtGeo(frames[it].lowHz, frames[it].highHz) }
            val rms = DoubleArray(frames.size) { powerToDb(residualSum[it] / max(fundamentalSum[it], POWER_FLOOR)) }
            val peak = DoubleArray(frames.size) { peakDbSum[it] / count }
            val crest = DoubleArray(frames.size) { crestDbSum[it] / count }
            val noise = DoubleArray(frames.size) {
                if (noiseCount == 0) Double.NaN else powerToDb(noiseSum[it] / noiseCount)
            }
            return RubBuzzResult(freq, rms, peak, crest, noise)
        }
    }

    private fun sqrtGeo(a: Double, b: Double) = sqrt(a * b)

    private fun fundamentalBins(frame: Frame): IntRange {
        val lo = max(1, floor(frame.lowHz / FUNDAMENTAL_MARGIN / binHz).toInt() - 1)
        val hi = ceil(frame.highHz * FUNDAMENTAL_MARGIN / binHz).toInt() + 1
        return lo..min(hi, frameSize / 2)
    }

    private fun residualBins(frame: Frame): IntRange {
        val lo = ceil(startOrder * frame.highHz / binHz).toInt()
        val hi = floor(upperHz / binHz).toInt()
        return lo..min(hi, frameSize / 2)
    }

    /** Power spectrum (one-sided, windowed) of the frame starting at [start]; also returns the complex FFT. */
    private fun spectrum(segment: DoubleArray, start: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val re = DoubleArray(frameSize)
        val im = DoubleArray(frameSize)
        for (i in 0 until frameSize) {
            val src = start + i
            if (src >= 0 && src < segment.size) re[i] = segment[src] * window[i]
        }
        fft.forward(re, im)
        val power = DoubleArray(frameSize / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
        return Triple(power, re, im)
    }

    private fun measureFrame(segment: DoubleArray, start: Int, frame: Frame): FrameMeasure {
        val (power, re, im) = spectrum(segment, start)
        val fundamental = bandSum(power, fundamentalBins(frame))
        val residualRange = residualBins(frame)
        val residual = bandSum(power, residualRange)

        // Tracking high-pass: keep only the residual bins and transform back.
        val zr = DoubleArray(frameSize)
        val zi = DoubleArray(frameSize)
        for (k in residualRange) {
            zr[k] = re[k]; zi[k] = im[k]
            if (k in 1 until frameSize / 2) {
                zr[frameSize - k] = re[frameSize - k]; zi[frameSize - k] = im[frameSize - k]
            }
        }
        fft.inverse(zr, zi)
        // Undo the window in the central half, where it is >= 0.5.
        var peak = 0.0
        var sumSquares = 0.0
        val from = frameSize / 4
        val to = 3 * frameSize / 4
        for (i in from until to) {
            val v = zr[i] / window[i]
            peak = max(peak, abs(v))
            sumSquares += v * v
        }
        return FrameMeasure(fundamental, residual, peak, sqrt(sumSquares / (to - from)))
    }

    /** Welch-averaged noise power spectrum from [0, noiseEnd), or null if too short. */
    private fun noisePower(segment: DoubleArray, noiseEnd: Int): DoubleArray? {
        val end = min(noiseEnd, segment.size)
        if (end < frameSize) return null
        val sum = DoubleArray(frameSize / 2 + 1)
        var n = 0
        var start = 0
        while (start + frameSize <= end) {
            val (power, _, _) = spectrum(segment, start)
            for (k in sum.indices) sum[k] += power[k]
            n++
            start += frameSize / 2
        }
        for (k in sum.indices) sum[k] /= n
        return sum
    }

    private fun bandSum(power: DoubleArray, range: IntRange): Double {
        var s = 0.0
        for (k in range) s += power[k]
        return s
    }

    /** RMS (in the window's central region) of a component with the given one-sided windowed power. */
    private fun rmsFromPower(power: Double): Double = sqrt(2.0 * power / frameSize / windowEnergy)

    private val windowEnergy = window.sumOf { it * it }

    private fun ratioDb(a: Double, b: Double): Double = 20.0 * log10(max(a, 1e-15) / max(b, 1e-15))

    companion object {
        private const val FRAME_SEC = 0.085
        private const val MAX_ANALYSIS_HZ = 20_000.0
        private const val MAX_ANALYSIS_FRACTION = 0.45
        /** The residual band must keep at least 30 % of the analysis range. */
        private const val RESIDUAL_MIN_BAND = 0.7
        private const val FUNDAMENTAL_MARGIN = 1.15
    }
}
