package de.flkaiser.paanalyzer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synchronized exponential sine sweep (Novak et al., 2015):
 *
 *   x(t) = A sin(2 pi f1 L (e^(t/L) - 1)),  L = round(f1 T / ln(f2/f1)) / f1
 *
 * Rounding f1·L to an integer makes all harmonics phase-synchronous with the
 * fundamental, so the impulse response of harmonic k appears exactly
 * L·ln(k) seconds before the linear impulse response after deconvolution.
 * The actual duration therefore differs slightly from the requested one.
 */
class ExpSweep(
    val startHz: Double,
    val endHz: Double,
    requestedDurationSec: Double,
    val sampleRate: Int,
    val amplitude: Double = 1.0,
) {
    /** The sweep rate L in seconds (time to sweep by a factor of e). */
    val rate: Double
    val durationSec: Double
    val samples: DoubleArray

    /** Lengths of the raised-cosine fades at both ends, in seconds. */
    val fadeInSec: Double
    val fadeOutSec: Double

    init {
        require(startHz > 0.0 && endHz > startHz) { "Invalid sweep range $startHz..$endHz Hz" }
        require(endHz < sampleRate / 2.0) { "End frequency must be below Nyquist" }
        require(requestedDurationSec > 0.0) { "Duration must be positive" }
        val logRange = ln(endHz / startHz)
        val cycles = max(1.0, round(startHz * requestedDurationSec / logRange))
        rate = cycles / startHz
        durationSec = rate * logRange
        val n = (durationSec * sampleRate).roundToInt()
        // Fade-in over the first 1/3 octave, fade-out over the last 1/12 octave.
        fadeInSec = rate * ln(2.0.pow(1.0 / 3.0))
        fadeOutSec = rate * ln(2.0.pow(1.0 / 12.0))
        val fadeInSamples = max(1, (fadeInSec * sampleRate).roundToInt())
        val fadeOutSamples = max(1, (fadeOutSec * sampleRate).roundToInt())
        samples = DoubleArray(n) { i ->
            val t = i.toDouble() / sampleRate
            var gain = amplitude
            if (i < fadeInSamples) gain *= 0.5 * (1.0 - cos(PI * i / fadeInSamples))
            val fromEnd = n - 1 - i
            if (fromEnd < fadeOutSamples) gain *= 0.5 * (1.0 - cos(PI * fromEnd / fadeOutSamples))
            gain * sin(2.0 * PI * startHz * rate * (exp(t / rate) - 1.0))
        }
    }

    val length: Int get() = samples.size

    /** Instantaneous frequency at time [t] seconds after the sweep start. */
    fun frequencyAt(t: Double): Double = startHz * exp(t / rate)

    /** Time in seconds after the sweep start at which [frequencyHz] is played. */
    fun timeAt(frequencyHz: Double): Double = rate * ln(frequencyHz / startHz)

    /** How far (seconds) the impulse response of harmonic [order] precedes the linear one. */
    fun harmonicOffsetSec(order: Int): Double = rate * ln(order.toDouble())

    companion object {
        fun from(settings: SweepSettings) = ExpSweep(
            startHz = settings.startHz,
            endHz = settings.endHz,
            requestedDurationSec = settings.durationSec,
            sampleRate = settings.sampleRate,
            amplitude = settings.amplitude,
        )
    }
}

/** The complete mono playback signal: repetitions of silence, sweep, silence. */
class PlaybackPlan(val settings: SweepSettings, val sweep: ExpSweep = ExpSweep.from(settings)) {
    val preSamples: Int = (settings.preSilenceSec * settings.sampleRate).roundToInt()
    val postSamples: Int = (settings.postSilenceSec * settings.sampleRate).roundToInt()
    val periodSamples: Int = preSamples + sweep.length + postSamples
    val totalSamples: Int = periodSamples * settings.averages

    /** Extra recording time after playback ends, covering Bluetooth and system latency. */
    val recordTailSamples: Int = (RECORD_TAIL_SEC * settings.sampleRate).roundToInt()
    val recordSamples: Int = totalSamples + recordTailSamples

    fun render(): FloatArray {
        val out = FloatArray(totalSamples)
        for (r in 0 until settings.averages) {
            val offset = r * periodSamples + preSamples
            for (i in 0 until sweep.length) out[offset + i] = sweep.samples[i].toFloat()
        }
        return out
    }

    companion object {
        const val RECORD_TAIL_SEC = 2.5
    }
}
