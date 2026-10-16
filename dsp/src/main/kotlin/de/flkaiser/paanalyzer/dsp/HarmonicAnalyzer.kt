package de.flkaiser.paanalyzer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cuts the linear and harmonic impulse responses out of a deconvolved sweep
 * response and turns each into a power spectrum.
 *
 * With a synchronized exponential sweep the spectrum of harmonic k, evaluated
 * at k·f, is the amplitude of the k-th harmonic produced by an excitation at f
 * (relative to the excitation amplitude), so distortion relative to the
 * fundamental is |H_k(k·f)| / |H_1(f)|.
 */
class HarmonicAnalyzer(
    private val sweep: ExpSweep,
    private val irWindowMs: Double,
    private val maxHarmonic: Int,
) {
    private val fs = sweep.sampleRate.toDouble()

    /** Samples between harmonic [order] and the next lower order (order - 1). */
    private fun gapSamples(order: Int): Double = sweep.rate * ln(order / (order - 1.0)) * fs

    private data class Window(val pre: Int, val post: Int)

    /*
     * Harmonic responses start abruptly at k·f1 (there is no k-th harmonic below
     * that), which makes them ring on both sides of their arrival time. So the
     * space between neighbouring harmonics is shared: order k gets 40 % of the
     * gap to order k+1 (which arrives earlier) as pre-window, and the rest of the
     * gap to order k-1 as post-window.
     * The linear response only needs a short pre-window because its band edges
     * are divided out exactly (low edge) or are short (high edge).
     */
    private val windows: List<Window> = buildList {
        val irWindow = max(8, (irWindowMs / 1000.0 * fs).roundToInt())
        val linearPre = min((LINEAR_PRE_SEC * fs).roundToInt(), (0.25 * gapSamples(2)).toInt())
        for (k in 1..maxHarmonic) {
            val pre = if (k == 1) linearPre else (PRE_SHARE * gapSamples(k + 1)).toInt()
            val post = if (k == 1) irWindow else {
                val previousPre = this[k - 2].pre
                min(irWindow, (0.95 * (gapSamples(k) - previousPre)).toInt())
            }
            add(Window(max(2, pre), max(4, post)))
        }
    }

    private val spectrumSize = Fft.nextPowerOfTwo(max(MIN_SPECTRUM_SIZE, windows.maxOf { it.pre + it.post }))
    private val fft = Fft(spectrumSize)

    /**
     * @param ir circular impulse response from [Deconvolver]
     * @param peak position of the linear impulse response peak in [ir]
     * @return power spectra for orders 1..maxHarmonic (index 0 = fundamental)
     */
    fun analyze(ir: DoubleArray, peak: Double): List<PowerSpectrum> {
        val n = ir.size
        val binHz = fs / spectrumSize
        return (1..maxHarmonic).map { k ->
            val window = windows[k - 1]
            val center = (peak - sweep.harmonicOffsetSec(k) * fs).roundToInt()
            val re = DoubleArray(spectrumSize)
            val im = DoubleArray(spectrumSize)
            for (o in -window.pre until window.post) {
                val src = Math.floorMod(center + o, n)
                // Tukey window: flat, with raised-cosine tapers over the outer 20 % of each side.
                val gain = if (o < 0) {
                    val taper = max(1, window.pre / 5)
                    val fromStart = o + window.pre
                    if (fromStart < taper) 0.5 * (1.0 - cos(PI * fromStart / taper)) else 1.0
                } else {
                    val taper = max(2, window.post / 5)
                    val fromEnd = window.post - 1 - o
                    if (fromEnd < taper) 0.5 * (1.0 - cos(PI * fromEnd / taper)) else 1.0
                }
                re[Math.floorMod(o, spectrumSize)] = ir[src] * gain
            }
            fft.forward(re, im)
            val power = DoubleArray(spectrumSize / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
            PowerSpectrum(power, binHz)
        }
    }

    companion object {
        private const val LINEAR_PRE_SEC = 0.003
        private const val PRE_SHARE = 0.4
        private const val MIN_SPECTRUM_SIZE = 32_768
    }
}
