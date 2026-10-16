package de.flkaiser.paanalyzer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Distortion of one harmonic order relative to the fundamental, per excitation frequency. */
class HarmonicCurve(val order: Int, val levelDb: DoubleArray)

class MeasurementResult(
    val settings: SweepSettings,
    /** Excitation frequencies (log grid) shared by the fundamental and distortion curves. */
    val frequencies: DoubleArray,
    /** Magnitude of the transfer function, dB re full-scale out / full-scale in. */
    val fundamentalDb: DoubleArray,
    /** Total harmonic distortion (orders 2..maxHarmonic) in dB re fundamental; NaN where not measurable. */
    val thdDb: DoubleArray,
    val harmonics: List<HarmonicCurve>,
    val rubBuzz: RubBuzzResult,
    /** Arrival time of the sweep relative to the start of playback (includes Bluetooth latency). */
    val delayMs: Double,
    val inputPeakDbfs: Double,
    /** Peak of the impulse response relative to the noise after it. */
    val irSnrDb: Double,
    val warnings: List<String>,
)

class MeasurementException(message: String) : Exception(message)

/**
 * Turns a recording of the [PlaybackPlan] into frequency response, harmonic
 * distortion and rub & buzz.
 */
class MeasurementAnalyzer(val settings: SweepSettings) {

    val plan = PlaybackPlan(settings)
    private val sweep = plan.sweep
    private val fs = settings.sampleRate.toDouble()

    fun analyze(recording: FloatArray, progress: (Double) -> Unit = {}): MeasurementResult {
        val errors = settings.validate()
        if (errors.isNotEmpty()) throw MeasurementException(errors.first())
        if (recording.size < plan.totalSamples) {
            throw MeasurementException("Recording is too short (${recording.size} of ${plan.totalSamples} samples).")
        }
        val warnings = mutableListOf<String>()

        var inputPeak = 0f
        for (v in recording) inputPeak = max(inputPeak, abs(v))
        val inputPeakDbfs = 20.0 * log10(max(inputPeak.toDouble(), 1e-10))

        // 1. Coarse alignment: where does the first sweep arrive in the recording?
        val coarseLength = min(recording.size, plan.periodSamples + plan.recordTailSamples)
        val coarseSize = Fft.nextPowerOfTwo(coarseLength)
        val coarse = Deconvolver(sweep, coarseSize)
        var firstArrival = Deconvolver.findPeak(coarse.impulseResponse(recording, 0, coarseLength))
        if (firstArrival > coarseLength) firstArrival -= coarseSize
        val delaySamples = firstArrival - plan.preSamples
        progress(0.15)

        // 2. Per repetition: deconvolve, align to the first repetition, average complex spectra.
        val size = Fft.nextPowerOfTwo(plan.periodSamples)
        val deconvolver = Deconvolver(sweep, size)
        val sumRe = DoubleArray(size)
        val sumIm = DoubleArray(size)
        val rubBuzz = RubBuzzAnalyzer(sweep, settings.rubBuzzStartOrder).Accumulator()
        var referencePeak = Double.NaN
        val repetitions = settings.averages
        for (r in 0 until repetitions) {
            val start = firstArrival.roundToInt() - plan.preSamples + r * plan.periodSamples
            val (re, im) = deconvolver.transferSpectrum(recording, start, plan.periodSamples)
            val peak = Deconvolver.findPeak(deconvolver.toImpulseResponse(re.copyOf(), im.copyOf()))
            if (r == 0) referencePeak = peak
            shift(re, im, peak - referencePeak)
            for (k in 0 until size) {
                sumRe[k] += re[k]
                sumIm[k] += im[k]
            }
            val segment = DoubleArray(plan.periodSamples) {
                val src = start + it
                if (src in recording.indices) recording[src].toDouble() else 0.0
            }
            rubBuzz.add(segment, peak, noiseEnd = (peak - NOISE_GUARD_SEC * fs).toInt())
            progress(0.15 + 0.65 * (r + 1) / repetitions)
        }
        for (k in 0 until size) {
            sumRe[k] /= repetitions
            sumIm[k] /= repetitions
        }
        val ir = deconvolver.toImpulseResponse(sumRe, sumIm)
        val peak = Deconvolver.findPeak(ir)

        // 3. Harmonic analysis.
        val spectra = HarmonicAnalyzer(sweep, settings.irWindowMs, settings.maxHarmonic).analyze(ir, peak)
        val fraction = settings.smoothing.fraction
        val frequencies = LogGrid.create(settings.startHz, settings.endHz, POINTS_PER_OCTAVE)
        val fundamentalPower = DoubleArray(frequencies.size) { spectra[0].powerAt(frequencies[it], fraction) }
        // Harmonics are only valid below the sweep's fade-out: there the deconvolution divides by the
        // faded sweep, but the harmonic was excited at full level by a lower frequency.
        val limitHz = min(settings.endHz / HARMONIC_TOP_MARGIN, MAX_HARMONIC_FRACTION * fs)
        val harmonics = (2..settings.maxHarmonic).map { k ->
            HarmonicCurve(k, DoubleArray(frequencies.size) { i ->
                val f = k * frequencies[i]
                if (f > limitHz) Double.NaN
                else powerToDb(spectra[k - 1].powerAt(f, fraction) / max(fundamentalPower[i], POWER_FLOOR))
            })
        }
        val thdDb = DoubleArray(frequencies.size) { i ->
            if (2 * frequencies[i] > limitHz) return@DoubleArray Double.NaN
            var sum = 0.0
            for (curve in harmonics) {
                val db = curve.levelDb[i]
                if (!db.isNaN()) sum += Math.pow(10.0, db / 10.0)
            }
            powerToDb(sum)
        }
        progress(0.95)

        // 4. Diagnostics.
        val noise = impulseNoise(ir, peak)
        val peakValue = abs(ir[Math.floorMod(peak.roundToInt(), ir.size)])
        val irSnrDb = if (noise == null) Double.NaN else ratioDb(peakValue, noise.rms)
        // With pure noise the "peak" is just the largest noise sample, barely above the rest.
        val peakProminenceDb = if (noise == null) Double.NaN else ratioDb(peakValue, noise.max)
        if (inputPeak >= CLIP_LEVEL) {
            warnings += "Input clipped (peak %.1f dBFS). Lower the volume or move the microphone further away.".format(inputPeakDbfs)
        } else if (inputPeakDbfs < LOW_INPUT_DBFS) {
            warnings += "Very low input level (peak %.1f dBFS). Increase the volume or move the microphone closer.".format(inputPeakDbfs)
        }
        if (!irSnrDb.isNaN()) {
            if (peakProminenceDb < NO_SIGNAL_PROMINENCE_DB) {
                warnings += "No sweep detected in the recording. Check that the Bluetooth speaker is connected and audible."
            } else if (irSnrDb < LOW_SNR_DB) {
                warnings += "Low signal-to-noise ratio (%.0f dB); results may be unreliable.".format(irSnrDb)
            }
        }
        val delaySec = delaySamples / fs
        if (delaySec > PlaybackPlan.RECORD_TAIL_SEC - 0.2) {
            warnings += "Sound arrived %.0f ms after playback started; the end of the recording may be cut off.".format(delaySec * 1000)
        }
        if (delaySec < -0.5 * settings.preSilenceSec) {
            warnings += "Recording started after playback; the background noise estimate is incomplete."
        }
        progress(1.0)

        return MeasurementResult(
            settings = settings,
            frequencies = frequencies,
            fundamentalDb = DoubleArray(frequencies.size) { powerToDb(fundamentalPower[it]) },
            thdDb = thdDb,
            harmonics = harmonics,
            rubBuzz = rubBuzz.result(),
            delayMs = delaySec * 1000.0,
            inputPeakDbfs = inputPeakDbfs,
            irSnrDb = irSnrDb,
            warnings = warnings,
        )
    }

    /** Advances a full complex spectrum by [samples] (fractional) samples. */
    private fun shift(re: DoubleArray, im: DoubleArray, samples: Double) {
        if (samples == 0.0) return
        val n = re.size
        for (k in 0 until n) {
            val signed = if (k <= n / 2) k else k - n
            val phase = 2.0 * PI * signed * samples / n
            val c = cos(phase)
            val s = sin(phase)
            val r = re[k] * c - im[k] * s
            val i = re[k] * s + im[k] * c
            re[k] = r
            im[k] = i
        }
        // Keep the Nyquist bin real.
        im[n / 2] = 0.0
    }

    private class ImpulseNoise(val rms: Double, val max: Double)

    /** Noise statistics of the impulse response between the linear response's tail and the wrapped harmonics. */
    private fun impulseNoise(ir: DoubleArray, peak: Double): ImpulseNoise? {
        val n = ir.size
        val from = (peak + (settings.irWindowMs / 1000.0 + 0.05) * fs).toInt()
        val to = (peak + n - (sweep.durationSec + 0.05) * fs).toInt()
        if (to - from < 0.02 * fs) return null
        var sum = 0.0
        var largest = 0.0
        for (i in from until to) {
            val v = ir[Math.floorMod(i, n)]
            sum += v * v
            largest = max(largest, abs(v))
        }
        return ImpulseNoise(sqrt(sum / (to - from)), largest)
    }

    private fun ratioDb(a: Double, b: Double) = 20.0 * log10(max(a, 1e-15) / max(b, 1e-15))

    companion object {
        const val POINTS_PER_OCTAVE = 48
        private const val MAX_HARMONIC_FRACTION = 0.45
        /** 1/6 octave: the 1/12-octave fade-out plus room for smoothing. */
        private val HARMONIC_TOP_MARGIN = Math.pow(2.0, 1.0 / 6.0)
        private const val NOISE_GUARD_SEC = 0.02
        private const val CLIP_LEVEL = 0.99f
        private const val LOW_INPUT_DBFS = -60.0
        private const val NO_SIGNAL_PROMINENCE_DB = 6.0
        private const val LOW_SNR_DB = 25.0
    }
}
