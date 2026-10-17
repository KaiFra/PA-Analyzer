package de.flkaiser.paanalyzer.ui

import de.flkaiser.paanalyzer.dsp.AnalysisSettings
import de.flkaiser.paanalyzer.dsp.Smoothing
import de.flkaiser.paanalyzer.dsp.SweepSettings

/** Smoothing slider positions, from no smoothing to the strongest. */
val SMOOTHING_STEPS: List<Smoothing> = listOf(Smoothing.NONE, Smoothing.OCT_24, Smoothing.OCT_12, Smoothing.OCT_6, Smoothing.OCT_3)

/** Text input of the analysis dialog. */
data class AnalysisInput(val irWindowMs: String, val maxHarmonic: String, val rubBuzzStartOrder: String, val smoothing: Smoothing) {

    /** Parses and validates against the measurement's [sweep] settings; returns the settings or the problems. */
    fun parse(sweep: SweepSettings): Pair<AnalysisSettings?, List<String>> {
        val window = irWindowMs.trim().replace(',', '.').toDoubleOrNull()
        val harmonic = maxHarmonic.trim().toIntOrNull()
        val order = rubBuzzStartOrder.trim().toIntOrNull()
        val errors = buildList {
            if (window == null) add("IR window is not a number.")
            if (harmonic == null) add("Highest harmonic must be a whole number.")
            if (order == null) add("Rub & buzz order must be a whole number.")
        }
        if (errors.isNotEmpty()) return null to errors
        val analysis = AnalysisSettings(window!!, harmonic!!, order!!, smoothing)
        val problems = sweep.withAnalysis(analysis).validate()
        return if (problems.isEmpty()) analysis to emptyList() else null to problems
    }
}
