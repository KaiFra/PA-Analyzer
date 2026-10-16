package de.flkaiser.paanalyzer.dsp

import kotlin.math.pow

/** Fractional-octave smoothing applied to the displayed spectra. */
enum class Smoothing(val fraction: Int, val label: String) {
    NONE(0, "None"),
    OCT_3(3, "1/3 octave"),
    OCT_6(6, "1/6 octave"),
    OCT_12(12, "1/12 octave"),
    OCT_24(24, "1/24 octave"),
}

/**
 * All user-adjustable parameters of one measurement.
 *
 * The excitation is a synchronized exponential sweep (Farina / Novak). It is played
 * [averages] times; each repetition is one period of
 * [preSilenceSec] + sweep + [postSilenceSec].
 */
data class SweepSettings(
    val startHz: Double = 20.0,
    val endHz: Double = 20_000.0,
    val durationSec: Double = 5.0,
    val levelDbfs: Double = -20.0,
    val sampleRate: Int = 48_000,
    val averages: Int = 1,
    val preSilenceSec: Double = 0.5,
    val postSilenceSec: Double = 1.5,
    /** Length of the time window applied to the impulse responses (gating). */
    val irWindowMs: Double = 300.0,
    /** Highest harmonic included in THD and shown individually. */
    val maxHarmonic: Int = 5,
    /** Rub & buzz is the energy above this harmonic order of the excitation frequency. */
    val rubBuzzStartOrder: Int = 10,
    val smoothing: Smoothing = Smoothing.OCT_12,
) {
    /** Linear peak amplitude of the sweep (1.0 = full scale). */
    val amplitude: Double get() = 10.0.pow(levelDbfs / 20.0)

    /** Returns human-readable problems; empty when the settings can be measured. */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (sampleRate !in SAMPLE_RATES) errors += "Sample rate must be one of ${SAMPLE_RATES.joinToString()} Hz."
        if (startHz < MIN_START_HZ) errors += "Start frequency must be at least ${MIN_START_HZ.toInt()} Hz."
        val maxEnd = MAX_END_FRACTION * sampleRate
        if (endHz > maxEnd) errors += "End frequency must be at most ${maxEnd.toInt()} Hz at $sampleRate Hz sample rate."
        if (endHz < startHz * MIN_SPAN_RATIO) errors += "The sweep must span at least two octaves."
        if (durationSec !in MIN_DURATION_SEC..MAX_DURATION_SEC) {
            errors += "Sweep duration must be between ${MIN_DURATION_SEC.toInt()} and ${MAX_DURATION_SEC.toInt()} s."
        }
        if (levelDbfs !in MIN_LEVEL_DBFS..0.0) errors += "Level must be between ${MIN_LEVEL_DBFS.toInt()} and 0 dBFS."
        if (averages !in 1..MAX_AVERAGES) errors += "Averages must be between 1 and $MAX_AVERAGES."
        if (preSilenceSec !in 0.2..5.0) errors += "Silence before the sweep must be between 0.2 and 5 s."
        if (postSilenceSec !in 0.3..10.0) errors += "Silence after the sweep must be between 0.3 and 10 s."
        if (irWindowMs !in 5.0..1000.0) errors += "IR window must be between 5 and 1000 ms."
        if (maxHarmonic !in 2..MAX_HARMONIC) errors += "Highest harmonic must be between 2 and $MAX_HARMONIC."
        if (rubBuzzStartOrder !in 5..40) errors += "Rub & buzz start order must be between 5 and 40."
        return errors
    }

    companion object {
        val SAMPLE_RATES = listOf(44_100, 48_000, 96_000)
        const val MIN_START_HZ = 10.0
        const val MAX_END_FRACTION = 0.475
        const val MIN_SPAN_RATIO = 4.0
        const val MIN_DURATION_SEC = 1.0
        const val MAX_DURATION_SEC = 15.0
        const val MIN_LEVEL_DBFS = -60.0
        const val MAX_AVERAGES = 8
        const val MAX_HARMONIC = 10
    }
}
