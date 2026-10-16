package de.flkaiser.paanalyzer.dsp

import java.util.Random
import kotlin.math.pow

/**
 * Builds the "microphone recording" that a simulated PA system would produce
 * for the playback plan of [settings].
 */
object SimulatedSystem {

    /**
     * @param system maps the whole playback signal to the system output
     * @param delaySamples latency (Bluetooth + acoustics) per repetition
     * @param noiseDbfs RMS of white Gaussian background noise
     * @param clip hard-clip the recording at ±1 like a real ADC
     */
    fun record(
        settings: SweepSettings,
        delaySamples: (repetition: Int) -> Int = { 4_800 },
        noiseDbfs: Double = -110.0,
        clip: Boolean = true,
        seed: Long = 1,
        system: (DoubleArray) -> DoubleArray,
    ): FloatArray {
        val plan = PlaybackPlan(settings)
        val playback = plan.render()
        val output = system(DoubleArray(playback.size) { playback[it].toDouble() })
        val recording = DoubleArray(plan.recordSamples)
        for (r in 0 until settings.averages) {
            val from = r * plan.periodSamples
            val to = from + plan.periodSamples
            val delay = delaySamples(r)
            for (i in from until to) {
                val dst = i + delay
                if (dst in recording.indices) recording[dst] += output[i]
            }
        }
        val random = Random(seed)
        val noise = 10.0.pow(noiseDbfs / 20.0)
        return FloatArray(recording.size) {
            var v = recording[it] + noise * random.nextGaussian()
            if (clip) v = v.coerceIn(-1.0, 1.0)
            v.toFloat()
        }
    }

    fun memoryless(f: (Double) -> Double): (DoubleArray) -> DoubleArray = { x -> DoubleArray(x.size) { f(x[it]) } }

    /** First-order low-pass y[n] = a x[n] + (1 - a) y[n-1]. */
    fun onePoleLowPass(a: Double): (DoubleArray) -> DoubleArray = { x ->
        val y = DoubleArray(x.size)
        var state = 0.0
        for (i in x.indices) {
            state = a * x[i] + (1 - a) * state
            y[i] = state
        }
        y
    }
}
