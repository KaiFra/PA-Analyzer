package de.flkaiser.paanalyzer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The strongest tone of a spectrum, refined between the FFT bins. */
class SpectrumPeak(val frequencyHz: Double, val levelDb: Double)

/**
 * One spectrum of the live analyzer. Levels are dBFS for tones: a full-scale sine reads 0 dB
 * (minus the microphone calibration, if one is applied). Points are ascending in frequency and
 * get closer together in the lower bands (see [LiveSettings.decimations]).
 */
class LiveSpectrum(
    val frequencies: DoubleArray,
    val levelDb: DoubleArray,
    /** Peak hold curve, null when switched off. */
    val peakHoldDb: DoubleArray?,
    val peak: SpectrumPeak?,
    /** Largest sample since the previous spectrum, dBFS. */
    val inputPeakDbfs: Double,
    /** Position in the input stream. */
    val timeSec: Double,
) {
    /** Level at the point nearest to [frequencyHz] (NaN outside the spectrum). */
    fun levelAt(frequencyHz: Double, curve: DoubleArray = levelDb): Double {
        if (frequencies.isEmpty() || frequencyHz < frequencies.first() / 1.02 || frequencyHz > frequencies.last() * 1.02) return Double.NaN
        var i = frequencies.binarySearch(frequencyHz)
        if (i < 0) {
            i = -i - 1
            if (i == frequencies.size || (i > 0 && frequencyHz / frequencies[i - 1] < frequencies[i] / frequencyHz)) i--
        }
        return curve[i.coerceIn(0, frequencies.lastIndex)]
    }
}

/**
 * Real-time spectrum of an audio stream with a multi-resolution FFT: the full-rate FFT covers
 * the top band; each decimation (half-band low-pass and every second sample) gives the next
 * lower band twice the resolution with the same FFT size. Feed it with [process]; every
 * [LiveSettings.intervalMs] it returns a new spectrum.
 *
 * Not thread-safe: call it from one thread.
 */
class LiveSpectrumAnalyzer(val settings: LiveSettings, calibration: MicCalibration? = null) {

    init {
        val errors = settings.validate()
        require(errors.isEmpty()) { errors.first() }
    }

    private val n = settings.fftSize
    private val mask = n - 1
    private val fs = settings.sampleRate.toDouble()
    private val window = settings.window.create(n)

    /** Turns |X|² into the squared amplitude of a sine at the bin's centre. */
    private val scale = (2.0 / window.sum()).let { it * it }
    private val fft = Fft(n)
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)

    private class Level(size: Int, val firstBin: Int, val lastBin: Int, val offset: Int) {
        val ring = DoubleArray(size)
        var pos = 0
    }

    private val levels: List<Level>
    private val decimators = List(settings.decimations) { HalfBandDecimator() }

    /** Frequencies of the spectrum points, ascending. */
    val frequencies: DoubleArray

    /** Which band (0 = full rate) each point comes from. */
    private val bandOf: IntArray

    init {
        val edges = bandEdges(settings.sampleRate, settings.decimations)
        // Lowest band first in the output, so frequencies ascend.
        val built = arrayOfNulls<Level>(edges.size)
        var offset = 0
        val freqs = ArrayList<Double>()
        val bands = ArrayList<Int>()
        for (d in edges.indices.reversed()) {
            val (lo, hi) = edges[d]
            val binWidth = fs / (1 shl d) / n
            val first = max(1, ceil(lo / binWidth - 1e-9).toInt())
            // The top band includes Nyquist; the others end just below the next band.
            val last = if (d == 0) n / 2 else ceil(hi / binWidth - 1e-9).toInt() - 1
            built[d] = Level(n, first, last, offset)
            for (k in first..last) {
                freqs += k * binWidth
                bands += d
            }
            offset += last - first + 1
        }
        levels = built.map { it!! }
        frequencies = freqs.toDoubleArray()
        bandOf = bands.toIntArray()
    }

    private val correction: DoubleArray? = calibration?.let { c -> DoubleArray(frequencies.size) { c.deviationAt(frequencies[it]) } }
    private val average = DoubleArray(frequencies.size)
    private var hasAverage = false
    private val held = DoubleArray(frequencies.size) { Double.NEGATIVE_INFINITY }
    private val samplesPerSpectrum = max(1, (fs * settings.intervalMs / 1000.0).roundToInt())
    private var sinceSpectrum = 0
    private var inputPeak = 0.0
    private var streamSamples = 0L

    /** Can be changed while running. */
    var averaging: Double = settings.averaging
        set(value) {
            field = value.coerceIn(0.0, LiveSettings.MAX_AVERAGING)
        }

    /** Can be changed while running; switching it clears the held curve. */
    var peakHold: PeakHold = settings.peakHold
        set(value) {
            if (value != field) resetPeaks()
            field = value
        }

    fun resetPeaks() = held.fill(Double.NEGATIVE_INFINITY)

    /** Adds samples (−1..1); returns a new spectrum when an update interval has passed during them. */
    fun process(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset): LiveSpectrum? {
        var due = false
        for (i in offset until offset + count) {
            val x = samples[i].toDouble()
            val a = abs(x)
            if (a > inputPeak) inputPeak = a
            push(x)
            if (++sinceSpectrum >= samplesPerSpectrum) {
                sinceSpectrum = 0
                due = true
            }
        }
        streamSamples += count
        return if (due) spectrum() else null
    }

    private fun push(sample: Double) {
        var x = sample
        var d = 0
        while (true) {
            val level = levels[d]
            level.ring[level.pos] = x
            level.pos = (level.pos + 1) and mask
            if (d == decimators.size || !decimators[d].push(x)) return
            x = decimators[d].output
            d++
        }
    }

    private fun spectrum(): LiveSpectrum {
        val a = if (hasAverage) averaging else 0.0
        for (level in levels) {
            for (i in 0 until n) {
                re[i] = level.ring[(level.pos + i) and mask] * window[i]
                im[i] = 0.0
            }
            fft.forward(re, im)
            for (k in level.firstBin..level.lastBin) {
                val j = level.offset + k - level.firstBin
                val p = (re[k] * re[k] + im[k] * im[k]) * scale
                average[j] = a * average[j] + (1.0 - a) * p
            }
        }
        hasAverage = true

        val levelDb = DoubleArray(frequencies.size) { j ->
            10.0 * log10(max(average[j], POWER_FLOOR)) - (correction?.get(j) ?: 0.0)
        }
        val hold = when (peakHold) {
            PeakHold.OFF -> null
            PeakHold.HOLD -> {
                for (j in held.indices) held[j] = max(held[j], levelDb[j])
                held.copyOf()
            }
            PeakHold.FALL -> {
                val fall = LiveSettings.FALL_DB_PER_SEC * settings.intervalMs / 1000.0
                for (j in held.indices) held[j] = max(held[j] - fall, levelDb[j])
                held.copyOf()
            }
        }
        val result = LiveSpectrum(
            frequencies = frequencies,
            levelDb = levelDb,
            peakHoldDb = hold,
            peak = findPeak(levelDb),
            inputPeakDbfs = 20.0 * log10(max(inputPeak, 1e-10)),
            timeSec = streamSamples / fs,
        )
        inputPeak = 0.0
        return result
    }

    /** Strongest point above [MIN_PEAK_HZ], refined by a parabola through it and its neighbours (in dB). */
    private fun findPeak(db: DoubleArray): SpectrumPeak? {
        var best = -1
        for (j in db.indices) {
            if (frequencies[j] >= MIN_PEAK_HZ && (best < 0 || db[j] > db[best])) best = j
        }
        if (best < 0 || db[best] <= FLOOR_DB) return null
        val equalSpacing = best > 0 && best < db.lastIndex && bandOf[best - 1] == bandOf[best] && bandOf[best + 1] == bandOf[best]
        if (!equalSpacing) return SpectrumPeak(frequencies[best], db[best])
        val y0 = db[best - 1]
        val y1 = db[best]
        val y2 = db[best + 1]
        val denominator = y0 - 2 * y1 + y2
        val delta = if (denominator < 0) (0.5 * (y0 - y2) / denominator).coerceIn(-0.5, 0.5) else 0.0
        val spacing = frequencies[best + 1] - frequencies[best]
        // The flat-top window needs no level correction (and its flat main lobe is no parabola).
        val level = if (settings.window == WindowFunction.FLAT_TOP) y1 else y1 - 0.25 * (y0 - y2) * delta
        return SpectrumPeak(frequencies[best] + delta * spacing, level)
    }

    companion object {
        /** Share of a decimated band's Nyquist frequency that is free of aliasing (see [HalfBandDecimator]). */
        const val PASS = 0.8
        const val MIN_PEAK_HZ = 20.0
        private const val POWER_FLOOR = 1e-16 // −160 dBFS
        private const val FLOOR_DB = -150.0

        /**
         * Frequency range of each band, index = number of decimations. Band d (with Nyquist
         * frequency n_d = fs / 2^(d+1)) covers [PASS·n_d/2, PASS·n_d); the full-rate band reaches
         * up to fs/2 and the most decimated one down to its first bin.
         */
        fun bandEdges(sampleRate: Int, decimations: Int): List<Pair<Double, Double>> = (0..decimations).map { d ->
            val nyquist = sampleRate / 2.0 / (1 shl d)
            val lo = if (d == decimations) 0.0 else PASS * nyquist / 2
            val hi = if (d == 0) nyquist else PASS * nyquist
            lo to hi
        }
    }
}

/**
 * Halves the sample rate: a 65-tap half-band low-pass (Kaiser window, about 100 dB stopband)
 * followed by dropping every second sample. Below 0.8 of the new Nyquist frequency the result is
 * flat and free of aliasing.
 */
class HalfBandDecimator {
    private val history = DoubleArray(2 * TAPS)
    private var pos = 0
    private var odd = false

    /** The latest output sample, valid after [push] returned true. */
    var output = 0.0
        private set

    /** Adds one input sample; returns true when it produced an output sample (every second call). */
    fun push(x: Double): Boolean {
        history[pos] = x
        history[pos + TAPS] = x
        pos = (pos + 1) % TAPS
        odd = !odd
        if (odd) return false
        var sum = 0.0
        for (k in 0 until TAPS) sum += COEFFICIENTS[k] * history[pos + k]
        output = sum
        return true
    }

    companion object {
        const val TAPS = 65
        private const val BETA = 10.0

        /** Windowed sinc with the cut-off at half the Nyquist frequency; every second tap (except the centre) is zero. */
        val COEFFICIENTS: DoubleArray = run {
            val m = (TAPS - 1) / 2
            val h = DoubleArray(TAPS) { i ->
                val k = i - m
                val sinc = if (k == 0) 0.5 else sin(PI * k / 2) / (PI * k)
                val t = k.toDouble() / m
                sinc * besselI0(BETA * sqrt(max(0.0, 1 - t * t))) / besselI0(BETA)
            }
            val sum = h.sum()
            DoubleArray(TAPS) { h[it] / sum }
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-12 * sum) {
                val q = x / (2 * k)
                term *= q * q
                sum += term
                k++
            }
            return sum
        }
    }
}

/**
 * Maps a spectrum with many, unevenly spaced points onto [count] columns spaced evenly on a
 * logarithmic axis from [minHz] to [maxHz] (for the waterfall and for drawing): each column takes
 * the maximum of the points inside it, so narrow peaks survive; columns between sparse points
 * are interpolated.
 */
class SpectrumColumns(val minHz: Double, val maxHz: Double, val count: Int) {
    init {
        require(minHz > 0 && maxHz > minHz && count > 0)
    }

    private val logSpan = kotlin.math.ln(maxHz / minHz)

    /** Lower edge of column [c] (c = count gives the upper edge of the last column). */
    fun edgeOf(c: Int): Double = minHz * kotlin.math.exp(logSpan * c / count)

    /** Centre frequency of column [c]. */
    fun frequencyOf(c: Int): Double = minHz * kotlin.math.exp(logSpan * (c + 0.5) / count)

    /** Column position (fractional, 0..count) of [frequencyHz]. */
    fun position(frequencyHz: Double): Double = kotlin.math.ln(frequencyHz / minHz) / logSpan * count

    fun reduce(frequencies: DoubleArray, values: DoubleArray, out: DoubleArray = DoubleArray(count)): DoubleArray {
        require(out.size == count && frequencies.size == values.size)
        out.fill(Double.NaN)
        for (i in frequencies.indices) {
            val v = values[i]
            if (!v.isFinite() || frequencies[i] <= 0) continue
            val c = floor(position(frequencies[i])).toInt()
            if (c in 0 until count && (out[c].isNaN() || v > out[c])) out[c] = v
        }
        for (c in 0 until count) {
            if (out[c].isNaN()) out[c] = interpolateLog(frequencies, values, frequencyOf(c))
        }
        return out
    }

    private fun interpolateLog(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        if (xs.isEmpty() || x < xs.first() || x > xs.last()) return Double.NaN
        var i = xs.binarySearch(x)
        if (i >= 0) return ys[i]
        i = -i - 1
        val t = kotlin.math.ln(x / xs[i - 1]) / kotlin.math.ln(xs[i] / xs[i - 1])
        return ys[i - 1] + t * (ys[i] - ys[i - 1])
    }
}

/** Nearest note of the equal-tempered scale (A4 = 440 Hz), e.g. for a feedback or hum frequency. */
data class MusicalNote(val name: String, val cents: Int) {
    override fun toString(): String = when {
        cents == 0 -> name
        cents > 0 -> "$name +$cents ct"
        else -> "$name −${-cents} ct"
    }

    companion object {
        private val NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

        fun of(frequencyHz: Double): MusicalNote? {
            if (!(frequencyHz > 0) || frequencyHz.isInfinite()) return null
            val midi = 69 + 12 * kotlin.math.log2(frequencyHz / 440.0)
            val nearest = midi.roundToInt()
            val cents = ((midi - nearest) * 100).roundToInt()
            return MusicalNote(NAMES[Math.floorMod(nearest, 12)] + (Math.floorDiv(nearest, 12) - 1), cents)
        }
    }
}
