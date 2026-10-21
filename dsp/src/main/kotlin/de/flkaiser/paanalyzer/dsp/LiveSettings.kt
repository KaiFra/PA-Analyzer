package de.flkaiser.paanalyzer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

/** Window applied to each block before the FFT of the live analyzer. */
enum class WindowFunction(val label: String, val description: String, private val coefficients: DoubleArray) {
    HANN("Hann", "Good all-round window", doubleArrayOf(0.5, 0.5)),
    BLACKMAN_HARRIS("Blackman-Harris", "Low leakage: quiet tones next to loud ones stay visible", doubleArrayOf(0.35875, 0.48829, 0.14128, 0.01168)),
    FLAT_TOP("Flat top", "Accurate tone levels, wider peaks", doubleArrayOf(0.21557895, 0.41663158, 0.277263158, 0.083578947, 0.006947368)),
    ;

    /** Periodic (DFT-even) window of length [n]: a0 − a1·cos(x) + a2·cos(2x) − … with x = 2πi/n. */
    fun create(n: Int): DoubleArray = DoubleArray(n) { i ->
        var v = 0.0
        for (k in coefficients.indices) {
            val term = coefficients[k] * cos(2.0 * PI * k * i / n)
            v += if (k % 2 == 0) term else -term
        }
        v
    }
}

/** What the second curve of the live spectrum shows. */
enum class PeakHold(val label: String) {
    OFF("Off"),
    HOLD("Hold"),

    /** Holds the maximum and lets it fall slowly ([LiveSettings.FALL_DB_PER_SEC]). */
    FALL("Falling"),
}

/**
 * Settings of the live spectrum analyzer.
 *
 * Every [fftSize] block is analysed at the full [sampleRate] and, for the lower octaves,
 * after [decimations] halvings of the sample rate: each halving doubles the frequency
 * resolution of the band below it (multi-resolution FFT, as in Spectroid).
 */
data class LiveSettings(
    val sampleRate: Int = 48_000,
    val fftSize: Int = 4_096,
    val decimations: Int = 2,
    val window: WindowFunction = WindowFunction.BLACKMAN_HARRIS,
    /** Time between two spectra (and two waterfall rows). */
    val intervalMs: Int = 50,
    /** Exponential averaging of successive spectra: 0 = none, towards 1 = slower and smoother. */
    val averaging: Double = 0.5,
    val peakHold: PeakHold = PeakHold.FALL,
) {
    fun validate(): List<String> = buildList {
        if (sampleRate !in SAMPLE_RATES) add("Sample rate must be 44.1, 48 or 96 kHz.")
        if (fftSize !in FFT_SIZES) add("FFT size must be one of ${FFT_SIZES.joinToString()}.")
        if (decimations !in 0..MAX_DECIMATIONS) add("Decimations must be between 0 and $MAX_DECIMATIONS.")
        if (intervalMs !in MIN_INTERVAL_MS..MAX_INTERVAL_MS) add("The update interval must be between $MIN_INTERVAL_MS and $MAX_INTERVAL_MS ms.")
        if (averaging !in 0.0..MAX_AVERAGING) add("Averaging must be between 0 and $MAX_AVERAGING.")
    }

    /** Frequency resolution of the full-rate FFT. */
    val binWidthHz: Double get() = sampleRate.toDouble() / fftSize

    /** Frequency resolution in the lowest band (after all decimations). */
    val finestBinWidthHz: Double get() = binWidthHz / (1 shl decimations)

    /** The finest resolution applies below this frequency. */
    val finestBandTopHz: Double get() = LiveSpectrumAnalyzer.bandEdges(sampleRate, decimations).last().second

    /** Duration of one FFT block at the full sample rate. */
    val blockMs: Double get() = fftSize * 1000.0 / sampleRate

    /** Time constant of the averaging, null without averaging. */
    val averagingTimeMs: Double? get() = if (averaging <= 0.0) null else -intervalMs / ln(averaging)

    companion object {
        val SAMPLE_RATES = listOf(44_100, 48_000, 96_000)
        val FFT_SIZES = listOf(1_024, 2_048, 4_096, 8_192, 16_384)
        /** Choices offered by the UI; any value in [MIN_INTERVAL_MS]..[MAX_INTERVAL_MS] is valid. */
        val INTERVALS_MS = listOf(20, 30, 50, 100, 200)
        const val MIN_INTERVAL_MS = 10
        const val MAX_INTERVAL_MS = 1_000
        const val MAX_DECIMATIONS = 4
        const val MAX_AVERAGING = 0.95
        const val FALL_DB_PER_SEC = 12.0
    }
}
