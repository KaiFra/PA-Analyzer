package de.flkaiser.paanalyzer.dsp

import java.util.Locale

/** Plain-text versions of an EQ suggestion: a table to type into a mixer, and Equalizer APO / REW lines. */
object EqExport {

    fun toText(suggestion: EqSuggestion, measurementName: String): String = buildString {
        val s = suggestion.settings
        appendLine("PA Analyzer – EQ suggestion for “$measurementName”")
        appendLine(
            "Target: ${s.target.label}, corrected ${EqDesigner.formatHz(suggestion.correctedFromHz)} – " +
                "${EqDesigner.formatHz(suggestion.correctedToHz)}, deviation ±${fmt(suggestion.rmsBeforeDb, 1)} dB → " +
                "±${fmt(suggestion.rmsAfterDb, 1)} dB (RMS)",
        )
        appendLine()
        suggestion.highPass?.let {
            appendLine("High-pass: ${EqDesigner.formatHz(it.frequencyHz)}, ${it.slopeDbPerOctave} dB/oct (Linkwitz-Riley)")
        }
        if (suggestion.filters.isEmpty()) appendLine("No EQ bands needed.")
        for ((i, f) in suggestion.filters.withIndex()) appendLine(line(i + 1, f))
        appendLine()
        appendLine("Equalizer APO / REW:")
        appendLine("Preamp: ${fmt(-(suggestion.filters.maxOfOrNull { it.gainDb }?.coerceAtLeast(0.0) ?: 0.0), 1)} dB")
        suggestion.highPass?.let {
            // A 24 dB/oct Linkwitz-Riley high-pass is two 2nd-order Butterworth sections.
            repeat(it.slopeDbPerOctave / 12) { _ -> appendLine("Filter: ON HPQ Fc ${fmt(it.frequencyHz, 0)} Hz Q 0.707") }
        }
        for ((i, f) in suggestion.filters.withIndex()) appendLine(apoLine(i + 1, f))
        if (suggestion.notes.isNotEmpty()) {
            appendLine()
            for (note in suggestion.notes) appendLine("Note: $note")
        }
    }

    fun line(number: Int, f: EqFilter): String {
        val gain = (if (f.gainDb > 0) "+" else "") + fmt(f.gainDb, 1)
        val shape = if (f.type == FilterType.PEAK) "Q ${fmt(f.q, 2)} (${fmt(f.bandwidthOctaves, 2)} oct)" else "slope 12 dB/oct"
        return String.format(Locale.ROOT, "%2d  %-11s %9s  %7s dB  %s", number, f.type.label.substringBefore(" ("), EqDesigner.formatHz(f.frequencyHz), gain, shape)
    }

    fun apoLine(number: Int, f: EqFilter): String {
        val q = if (f.type == FilterType.PEAK) " Q ${fmt(f.q, 2)}" else ""
        return "Filter $number: ON ${f.type.apoCode} Fc ${fmt(f.frequencyHz, 0)} Hz Gain ${fmt(f.gainDb, 1)} dB$q"
    }

    private fun fmt(v: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", v)
}
