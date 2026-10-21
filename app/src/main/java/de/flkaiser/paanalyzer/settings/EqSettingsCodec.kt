package de.flkaiser.paanalyzer.settings

import de.flkaiser.paanalyzer.dsp.EqSettings
import de.flkaiser.paanalyzer.dsp.EqTarget
import de.flkaiser.paanalyzer.dsp.FilterType

/** Converts [EqSettings] to and from key/value pairs; invalid stored values fall back to the defaults. */
object EqSettingsCodec {

    fun encode(s: EqSettings): Map<String, String> = mapOf(
        "eq.tilt" to s.tiltDbPerOctave.toString(),
        "eq.bassBoostDb" to s.bassBoostDb.toString(),
        "eq.bassBoostHz" to s.bassBoostHz.toString(),
        "eq.filterCount" to s.filterCount.toString(),
        "eq.minHz" to s.minHz.toString(),
        "eq.maxHz" to s.maxHz.toString(),
        "eq.maxBoostDb" to s.maxBoostDb.toString(),
        "eq.maxCutDb" to s.maxCutDb.toString(),
        "eq.suggestHighPass" to s.suggestHighPass.toString(),
        "eq.filterTypes" to s.filterTypes.sortedBy { it.ordinal }.joinToString(",") { it.name },
    )

    fun decode(values: Map<String, String>): EqSettings {
        // Version 0.2 stored a preset name instead of tilt and bass boost.
        val d = EqTarget.entries.firstOrNull { it.name == values["eq.target"] }?.applyTo(EqSettings()) ?: EqSettings()
        val s = EqSettings(
            tiltDbPerOctave = values["eq.tilt"]?.toDoubleOrNull() ?: d.tiltDbPerOctave,
            bassBoostDb = values["eq.bassBoostDb"]?.toDoubleOrNull() ?: d.bassBoostDb,
            bassBoostHz = values["eq.bassBoostHz"]?.toDoubleOrNull() ?: d.bassBoostHz,
            filterCount = values["eq.filterCount"]?.toIntOrNull() ?: d.filterCount,
            minHz = values["eq.minHz"]?.toDoubleOrNull() ?: d.minHz,
            maxHz = values["eq.maxHz"]?.toDoubleOrNull() ?: d.maxHz,
            maxBoostDb = values["eq.maxBoostDb"]?.toDoubleOrNull() ?: d.maxBoostDb,
            maxCutDb = values["eq.maxCutDb"]?.toDoubleOrNull() ?: d.maxCutDb,
            suggestHighPass = values["eq.suggestHighPass"]?.toBooleanStrictOrNull() ?: d.suggestHighPass,
            filterTypes = values["eq.filterTypes"]
                ?.split(',')
                ?.mapNotNull { name -> FilterType.entries.firstOrNull { it.name == name } }
                ?.toSet()
                ?: d.filterTypes,
        )
        return if (s.validate().isEmpty()) s else EqSettings()
    }
}
