package de.flkaiser.paanalyzer.screenshots

import de.flkaiser.paanalyzer.dsp.MeasurementAnalyzer
import de.flkaiser.paanalyzer.dsp.MeasurementResult
import de.flkaiser.paanalyzer.dsp.PlaybackPlan
import de.flkaiser.paanalyzer.dsp.Smoothing
import de.flkaiser.paanalyzer.dsp.SweepSettings
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A plausible loudspeaker for the README screenshots: bass roll-off, a presence
 * bump, treble roll-off, excursion distortion that rises towards the bass, and
 * a loose part that rattles between 90 and 140 Hz.
 */
object DemoMeasurement {

    val settings = SweepSettings(
        durationSec = 3.0,
        levelDbfs = -12.0,
        preSilenceSec = 0.5,
        postSilenceSec = 1.0,
        smoothing = Smoothing.OCT_12,
    )

    val result: MeasurementResult by lazy { MeasurementAnalyzer(settings).analyze(record()) }

    private fun record(): FloatArray {
        val plan = PlaybackPlan(settings)
        val fs = settings.sampleRate.toDouble()
        val x = plan.render().map { it.toDouble() }.toDoubleArray()

        // Excursion distortion: only the bass part goes through the nonlinearity.
        val bass = Biquad.lowPass(fs, 180.0, 0.707).process(x)
        val y = DoubleArray(x.size) { i ->
            val b = bass[i] * 4.0
            x[i] + (0.06 * b * b + 0.05 * b * b * b) / 4.0
        }
        // Rattle: a tick at each positive peak while the sweep is between 90 and 140 Hz.
        val sweep = plan.sweep
        for (i in 1 until x.size) {
            val t = (i - plan.preSamples) / fs
            if (t < 0 || t > sweep.durationSec) continue
            if (sweep.frequencyAt(t) in 90.0..140.0 && x[i - 1] < 0.22 && x[i] >= 0.22) y[i] += 0.012
        }
        var out = Biquad.highPass(fs, 55.0, 1.1).process(y)
        out = Biquad.peaking(fs, 2_500.0, 1.4, 4.0).process(out)
        out = Biquad.peaking(fs, 380.0, 2.0, -3.0).process(out)
        out = Biquad.lowPass(fs, 16_000.0, 0.8).process(out)

        val delay = (0.21 * fs).toInt()
        val random = Random(42)
        val noise = 10.0.pow(-92.0 / 20.0)
        return FloatArray(plan.recordSamples) { i ->
            val src = i - delay
            val v = if (src in out.indices) 0.5 * out[src] else 0.0
            (v + noise * random.nextGaussian()).toFloat()
        }
    }

    /** RBJ cookbook biquads. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun process(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size)
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in x.indices) {
                val v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x[i]; y2 = y1; y1 = v
                y[i] = v
            }
            return y
        }

        companion object {
            private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

            fun lowPass(fs: Double, f: Double, q: Double): Biquad {
                val w = 2 * PI * f / fs
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun highPass(fs: Double, f: Double, q: Double): Biquad {
                val w = 2 * PI * f / fs
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun peaking(fs: Double, f: Double, q: Double, gainDb: Double): Biquad {
                val a = sqrt(10.0.pow(gainDb / 20.0))
                val w = 2 * PI * f / fs
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                return make(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
            }
        }
    }
}
