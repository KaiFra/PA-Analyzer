package de.flkaiser.paanalyzer.settings

import de.flkaiser.paanalyzer.dsp.EqSettings
import de.flkaiser.paanalyzer.dsp.EqTarget
import de.flkaiser.paanalyzer.dsp.FilterType

/** Converts [EqSettings] to and from key/value pairs; invalid stored values fall back to the defaults. */
object EqSettingsCodec {

    fun encode(s: EqSettings): Map<String, String> = mapOf(
        "eq.target" to s.target.name,
        "eq.filterCount" to s.filterCount.toString(),
        "eq.minHz" to s.minHz.toString(),
        "eq.maxHz" to s.maxHz.toString(),
        "eq.maxBoostDb" to s.maxBoostDb.toString(),
        "eq.maxCutDb" to s.maxCutDb.toString(),
        "eq.suggestHighPass" to s.suggestHighPass.toString(),
        "eq.filterTypes" to s.filterTypes.sortedBy { it.ordinal }.joinToString(",") { it.name },
    )

    fun decode(values: Map<String, String>): EqSettings {
        val d = EqSettings()
        val s = EqSettings(
            target = EqTarget.entries.firstOrNull { it.name == values["eq.target"] } ?: d.target,
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
        return if (s.validate().isEmpty()) s else d
    }
}
