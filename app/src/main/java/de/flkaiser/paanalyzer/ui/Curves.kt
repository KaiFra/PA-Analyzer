package de.flkaiser.paanalyzer.ui

import de.flkaiser.paanalyzer.dsp.MeasurementResult
import de.flkaiser.paanalyzer.dsp.dbToPercent
import java.util.Locale
import kotlin.math.ln

enum class CurveKind { FUNDAMENTAL, DISTORTION, RUB_BUZZ }

/**
 * One curve of the combined results chart. All curves share one dB axis:
 * distortion and rub & buzz are drawn at their absolute level (fundamental +
 * relative level), the way they would appear in a spectrum. [relativeDb] keeps
 * the level relative to the fundamental for the readout.
 */
class Curve(
    val id: String,
    val label: String,
    val kind: CurveKind,
    val frequencies: DoubleArray,
    val absoluteDb: DoubleArray,
    val relativeDb: DoubleArray?,
)

object Curves {
    const val FUNDAMENTAL = "fundamental"
    const val THD = "thd"
    const val RUB_BUZZ_RMS = "rb_rms"
    const val RUB_BUZZ_PEAK = "rb_peak"
    const val RUB_BUZZ_NOISE = "rb_noise"

    fun harmonic(order: Int) = "h$order"

    val DEFAULT_VISIBLE: Set<String> = setOf(FUNDAMENTAL, THD)

    fun build(result: MeasurementResult): List<Curve> = buildList {
        val f = result.frequencies
        val fundamental = result.fundamentalDb
        add(Curve(FUNDAMENTAL, "Fundamental", CurveKind.FUNDAMENTAL, f, fundamental, null))
        add(Curve(THD, "THD", CurveKind.DISTORTION, f, add(fundamental, result.thdDb), result.thdDb))
        for (h in result.harmonics) {
            add(Curve(harmonic(h.order), "H${h.order}", CurveKind.DISTORTION, f, add(fundamental, h.levelDb), h.levelDb))
        }
        val rb = result.rubBuzz
        if (rb.size > 0) {
            val reference = DoubleArray(rb.size) { interpolateLog(f, fundamental, rb.frequencies[it]) }
            add(Curve(RUB_BUZZ_RMS, "R&B RMS", CurveKind.RUB_BUZZ, rb.frequencies, add(reference, rb.rmsDb), rb.rmsDb))
            add(Curve(RUB_BUZZ_PEAK, "R&B peak", CurveKind.RUB_BUZZ, rb.frequencies, add(reference, rb.peakDb), rb.peakDb))
            add(Curve(RUB_BUZZ_NOISE, "R&B noise", CurveKind.RUB_BUZZ, rb.frequencies, add(reference, rb.noiseFloorDb), rb.noiseFloorDb))
        }
    }

    /** Value at [frequency] by linear interpolation over log frequency; NaN outside the data. */
    fun interpolateLog(frequencies: DoubleArray, values: DoubleArray, frequency: Double): Double {
        if (frequencies.isEmpty() || frequency < frequencies.first() || frequency > frequencies.last()) return Double.NaN
        val i = frequencies.indexOfFirst { it >= frequency }
        if (i <= 0) return values[0]
        val f0 = frequencies[i - 1]
        val f1 = frequencies[i]
        val t = ln(frequency / f0) / ln(f1 / f0)
        return values[i - 1] * (1 - t) + values[i] * t
    }

    /** Cursor readout: absolute level for the fundamental, level re fundamental (and %) for the rest. */
    fun readout(curve: Curve, frequency: Double, locale: Locale = Locale.getDefault()): String? {
        if (curve.frequencies.isEmpty() || frequency < curve.frequencies.first() / 1.05 || frequency > curve.frequencies.last() * 1.05) return null
        val i = PlotMath.nearestIndex(curve.frequencies, frequency)
        return when (curve.kind) {
            CurveKind.FUNDAMENTAL -> curve.absoluteDb[i].let { if (it.isFinite()) String.format(locale, "%.1f dB", it) else "–" }
            CurveKind.DISTORTION -> curve.relativeDb!![i].let {
                if (it.isFinite()) String.format(locale, "%.1f dB · %s %%", it, percent(dbToPercent(it), locale)) else "–"
            }
            CurveKind.RUB_BUZZ -> curve.relativeDb!![i].let { if (it.isFinite()) String.format(locale, "%.1f dB re fund.", it) else "–" }
        }
    }

    fun percent(p: Double, locale: Locale = Locale.getDefault()): String = when {
        p >= 10 -> String.format(locale, "%.0f", p)
        p >= 1 -> String.format(locale, "%.1f", p)
        p >= 0.1 -> String.format(locale, "%.2f", p)
        else -> String.format(locale, "%.3f", p)
    }

    private fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] + b[it] }
}
