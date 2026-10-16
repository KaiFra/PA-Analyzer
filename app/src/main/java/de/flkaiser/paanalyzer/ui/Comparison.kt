package de.flkaiser.paanalyzer.ui

import androidx.compose.ui.graphics.Color
import de.flkaiser.paanalyzer.dsp.MeasurementResult
import de.flkaiser.paanalyzer.dsp.PositionAverage
import java.util.Locale

/** A saved measurement drawn on top of the current one (before/after EQ, left/right, other venues). */
class Comparison(val id: String, val name: String, val result: MeasurementResult, val colorIndex: Int)

object Compare {
    const val MAX = 6

    /** Distinct from the curve colours of the current measurement. */
    val COLORS = listOf(
        Color(0xFF00897B),
        Color(0xFFF4511E),
        Color(0xFF5E35B1),
        Color(0xFFC0CA33),
        Color(0xFF6D4C41),
        Color(0xFF039BE5),
    )

    fun color(c: Comparison): Color = COLORS[c.colorIndex % COLORS.size]

    fun fundamentalId(c: Comparison) = "cmp:${c.id}:${Curves.FUNDAMENTAL}"
    fun thdId(c: Comparison) = "cmp:${c.id}:${Curves.THD}"

    /** Level shift that puts [other] on the mid-band level of [reference]; positions further away are quieter. */
    fun alignOffset(reference: MeasurementResult, other: MeasurementResult): Double =
        PositionAverage.midLevel(reference.frequencies, reference.fundamentalDb) -
            PositionAverage.midLevel(other.frequencies, other.fundamentalDb)

    /**
     * Fundamental and THD of [c] for the chart, shifted to the level of [reference] when [align] is set.
     * THD is drawn at its absolute level, like the current measurement's distortion curves.
     */
    fun curves(c: Comparison, reference: MeasurementResult?, align: Boolean): List<Curve> {
        val r = c.result
        val offset = if (align && reference != null) alignOffset(reference, r) else 0.0
        val fundamental = DoubleArray(r.frequencies.size) { r.fundamentalDb[it] + offset }
        return listOf(
            Curve(fundamentalId(c), c.name, CurveKind.FUNDAMENTAL, r.frequencies, fundamental, null),
            Curve(thdId(c), "${c.name} THD", CurveKind.DISTORTION, r.frequencies, DoubleArray(fundamental.size) { fundamental[it] + r.thdDb[it] }, r.thdDb),
        )
    }

    /** Cursor readout for a compared fundamental: its level and the difference to the current measurement. */
    fun readout(compared: Curve, current: Curve?, frequency: Double, locale: Locale = Locale.getDefault()): String? {
        val value = Curves.interpolateLog(compared.frequencies, compared.absoluteDb, frequency)
        if (!value.isFinite()) return null
        val reference = current?.let { Curves.interpolateLog(it.frequencies, it.absoluteDb, frequency) } ?: Double.NaN
        return if (reference.isFinite()) {
            String.format(locale, "%.1f dB (%+.1f)", value, value - reference)
        } else {
            String.format(locale, "%.1f dB", value)
        }
    }
}
