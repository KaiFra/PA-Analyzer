package de.flkaiser.paanalyzer.settings

import de.flkaiser.paanalyzer.audio.OutputChannel
import de.flkaiser.paanalyzer.dsp.Smoothing
import de.flkaiser.paanalyzer.dsp.SweepSettings
import java.math.BigDecimal

data class AppSettings(
    val sweep: SweepSettings = SweepSettings(),
    val channel: OutputChannel = OutputChannel.BOTH,
)

/** Editable text of the settings screen. Numbers stay strings until they parse. */
data class SettingsForm(
    val startHz: String,
    val endHz: String,
    val durationSec: String,
    val levelDbfs: String,
    val sampleRate: Int,
    val averages: String,
    val preSilenceSec: String,
    val postSilenceSec: String,
    val irWindowMs: String,
    val maxHarmonic: String,
    val rubBuzzStartOrder: String,
    val smoothing: Smoothing,
    val channel: OutputChannel,
) {
    /** Parses and validates; returns the settings or the list of problems. */
    fun parse(): ParseResult {
        val errors = mutableListOf<String>()
        fun double(text: String, name: String): Double? =
            text.trim().replace(',', '.').toDoubleOrNull().also { if (it == null) errors += "$name is not a number." }
        fun int(text: String, name: String): Int? =
            text.trim().toIntOrNull().also { if (it == null) errors += "$name must be a whole number." }

        val start = double(startHz, "Start frequency")
        val end = double(endHz, "End frequency")
        val duration = double(durationSec, "Duration")
        val level = double(levelDbfs, "Level")
        val avg = int(averages, "Averages")
        val pre = double(preSilenceSec, "Silence before")
        val post = double(postSilenceSec, "Silence after")
        val window = double(irWindowMs, "IR window")
        val harmonic = int(maxHarmonic, "Highest harmonic")
        val order = int(rubBuzzStartOrder, "Rub & buzz order")
        if (errors.isNotEmpty()) return ParseResult(null, errors)

        val sweep = SweepSettings(
            startHz = start!!,
            endHz = end!!,
            durationSec = duration!!,
            levelDbfs = level!!,
            sampleRate = sampleRate,
            averages = avg!!,
            preSilenceSec = pre!!,
            postSilenceSec = post!!,
            irWindowMs = window!!,
            maxHarmonic = harmonic!!,
            rubBuzzStartOrder = order!!,
            smoothing = smoothing,
        )
        val problems = sweep.validate()
        return if (problems.isEmpty()) ParseResult(AppSettings(sweep, channel), emptyList()) else ParseResult(null, problems)
    }

    companion object {
        fun from(settings: AppSettings): SettingsForm {
            val s = settings.sweep
            return SettingsForm(
                startHz = format(s.startHz),
                endHz = format(s.endHz),
                durationSec = format(s.durationSec),
                levelDbfs = format(s.levelDbfs),
                sampleRate = s.sampleRate,
                averages = s.averages.toString(),
                preSilenceSec = format(s.preSilenceSec),
                postSilenceSec = format(s.postSilenceSec),
                irWindowMs = format(s.irWindowMs),
                maxHarmonic = s.maxHarmonic.toString(),
                rubBuzzStartOrder = s.rubBuzzStartOrder.toString(),
                smoothing = s.smoothing,
                channel = settings.channel,
            )
        }

        /** Plain notation without trailing zeros: 20000.0 -> "20000", 0.5 -> "0.5". */
        fun format(v: Double): String = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    }
}

data class ParseResult(val settings: AppSettings?, val errors: List<String>)

/** Converts settings to and from string key/value pairs for persistence. */
object SettingsCodec {

    fun encode(settings: AppSettings): Map<String, String> {
        val s = settings.sweep
        return mapOf(
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
            "channel" to settings.channel.name,
        )
    }

    /** Missing or unreadable values fall back to the defaults; invalid combinations reset everything. */
    fun decode(values: Map<String, String>): AppSettings {
        val d = SweepSettings()
        fun double(key: String, default: Double) = values[key]?.toDoubleOrNull() ?: default
        fun int(key: String, default: Int) = values[key]?.toIntOrNull() ?: default
        val sweep = SweepSettings(
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
            smoothing = Smoothing.entries.firstOrNull { it.name == values["smoothing"] } ?: d.smoothing,
        )
        val channel = OutputChannel.entries.firstOrNull { it.name == values["channel"] } ?: OutputChannel.BOTH
        return if (sweep.validate().isEmpty()) AppSettings(sweep, channel) else AppSettings(channel = channel)
    }
}
