package de.flkaiser.paanalyzer.settings

import de.flkaiser.paanalyzer.dsp.LiveSettings
import de.flkaiser.paanalyzer.dsp.PeakHold
import de.flkaiser.paanalyzer.dsp.WindowFunction
import de.flkaiser.paanalyzer.live.LiveSource

/** Stores the live analyzer's settings as key/value pairs ("live." keys); invalid values fall back to the defaults. */
object LiveSettingsCodec {

    fun encode(s: LiveSettings, source: LiveSource): Map<String, String> = mapOf(
        "live.sampleRate" to s.sampleRate.toString(),
        "live.fftSize" to s.fftSize.toString(),
        "live.decimations" to s.decimations.toString(),
        "live.window" to s.window.name,
        "live.intervalMs" to s.intervalMs.toString(),
        "live.averaging" to s.averaging.toString(),
        "live.peakHold" to s.peakHold.name,
        "live.source" to source.name,
    )

    fun decodeSettings(values: Map<String, String>): LiveSettings {
        val d = LiveSettings()
        val s = LiveSettings(
            sampleRate = values["live.sampleRate"]?.toIntOrNull() ?: d.sampleRate,
            fftSize = values["live.fftSize"]?.toIntOrNull() ?: d.fftSize,
            decimations = values["live.decimations"]?.toIntOrNull() ?: d.decimations,
            window = WindowFunction.entries.firstOrNull { it.name == values["live.window"] } ?: d.window,
            intervalMs = values["live.intervalMs"]?.toIntOrNull() ?: d.intervalMs,
            averaging = values["live.averaging"]?.toDoubleOrNull() ?: d.averaging,
            peakHold = PeakHold.entries.firstOrNull { it.name == values["live.peakHold"] } ?: d.peakHold,
        )
        return if (s.validate().isEmpty()) s else d
    }

    fun decodeSource(values: Map<String, String>): LiveSource =
        LiveSource.entries.firstOrNull { it.name == values["live.source"] } ?: LiveSource.MICROPHONE
}
