package de.flkaiser.paanalyzer.dsp

/** Converts [SweepSettings] to and from string key/value pairs (for preferences and measurement files). */
object SweepSettingsCodec {

    fun encode(s: SweepSettings): Map<String, String> = linkedMapOf(
        "startHz" to s.startHz.toString(),
        "endHz" to s.endHz.toString(),
        "durationSec" to s.durationSec.toString(),
        "levelDbfs" to s.levelDbfs.toString(),
        "sampleRate" to s.sampleRate.toString(),
        "averages" to s.averages.toString(),
        "preSilenceSec" to s.preSilenceSec.toString(),
        "postSilenceSec" to s.postSilenceSec.toString(),
        "irWindowMs" to s.irWindowMs.toString(),
        "maxHarmonic" to s.maxHarmonic.toString(),
        "rubBuzzStartOrder" to s.rubBuzzStartOrder.toString(),
        "smoothing" to s.smoothing.name,
    )

    /** Missing or unreadable values fall back to the defaults. The result is not validated. */
    fun decode(values: Map<String, String>): SweepSettings {
        val d = SweepSettings()
        fun double(key: String, default: Double) = values[key]?.trim()?.toDoubleOrNull() ?: default
        fun int(key: String, default: Int) = values[key]?.trim()?.toIntOrNull() ?: default
        return SweepSettings(
            startHz = double("startHz", d.startHz),
            endHz = double("endHz", d.endHz),
            durationSec = double("durationSec", d.durationSec),
            levelDbfs = double("levelDbfs", d.levelDbfs),
            sampleRate = int("sampleRate", d.sampleRate),
            averages = int("averages", d.averages),
            preSilenceSec = double("preSilenceSec", d.preSilenceSec),
            postSilenceSec = double("postSilenceSec", d.postSilenceSec),
            irWindowMs = double("irWindowMs", d.irWindowMs),
            maxHarmonic = int("maxHarmonic", d.maxHarmonic),
            rubBuzzStartOrder = int("rubBuzzStartOrder", d.rubBuzzStartOrder),
            smoothing = Smoothing.entries.firstOrNull { it.name == values["smoothing"]?.trim() } ?: d.smoothing,
        )
    }
}
