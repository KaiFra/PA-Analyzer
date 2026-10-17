package de.flkaiser.paanalyzer.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Frequency response of a measurement microphone from its calibration file
 * (e.g. miniDSP UMIK-1/UMIK-2, Dayton EMM-6, or REW's format): one line per point
 * with frequency in Hz and the microphone's deviation in dB, optionally followed by
 * phase. Comment lines start with `*`, `#`, `"` or `;`; the UMIK header line
 * `"Sens Factor =-1.378dB, SERNO: 7000001"` is read for its sensitivity.
 *
 * Applying it subtracts the microphone's deviation, so the result shows the PA, not the mic.
 */
class MicCalibration(
    val name: String,
    val frequencies: DoubleArray,
    val deviationDb: DoubleArray,
    /** Sensitivity from the file header, for information only (levels are relative in this app). */
    val sensitivityDb: Double? = null,
) {
    init {
        require(frequencies.size == deviationDb.size && frequencies.size >= 2)
    }

    val size: Int get() = frequencies.size

    /** Microphone deviation at [frequencyHz]; held at the end values outside the file's range. */
    fun deviationAt(frequencyHz: Double): Double {
        if (frequencyHz <= frequencies.first()) return deviationDb.first()
        if (frequencyHz >= frequencies.last()) return deviationDb.last()
        return PositionAverage.interpolate(frequencies, deviationDb, doubleArrayOf(frequencyHz))[0]
    }

    /**
     * Corrects a measurement made with this microphone: the fundamental at f by the deviation at f;
     * the harmonic k (heard at k·f, relative to the fundamental) by the difference between f and k·f.
     * THD is summed again from the corrected harmonics. Rub & buzz is broadband and stays as measured.
     */
    fun apply(result: MeasurementResult): MeasurementResult {
        val f = result.frequencies
        val atF = DoubleArray(f.size) { deviationAt(f[it]) }
        val harmonics = result.harmonics.map { h ->
            HarmonicCurve(h.order, DoubleArray(f.size) { i -> h.levelDb[i] + atF[i] - deviationAt(h.order * f[i]) })
        }
        val thd = DoubleArray(f.size) { i ->
            if (result.thdDb[i].isNaN()) return@DoubleArray Double.NaN
            val used = harmonics.filter { it.order <= result.settings.maxHarmonic && !it.levelDb[i].isNaN() }
            if (used.isEmpty()) result.thdDb[i] else powerToDb(used.sumOf { 10.0.pow(it.levelDb[i] / 10.0) })
        }
        return MeasurementResult(
            settings = result.settings,
            frequencies = f,
            fundamentalDb = DoubleArray(f.size) { result.fundamentalDb[it] - atF[it] },
            thdDb = thd,
            harmonics = harmonics,
            rubBuzz = result.rubBuzz,
            delayMs = result.delayMs,
            inputPeakDbfs = result.inputPeakDbfs,
            irSnrDb = result.irSnrDb,
            warnings = result.warnings,
            calibration = name,
        )
    }

    companion object {
        private val SENSITIVITY = Regex("""Sens(?:itivity)?\s*Factor\s*=\s*(-?[0-9.]+)\s*dB""", RegexOption.IGNORE_CASE)
        private val SEPARATOR = Regex("""[\s,;]+""")

        /** Reads a calibration file; [name] is usually the file name. */
        fun parse(text: String, name: String): MicCalibration {
            var sensitivity: Double? = null
            val points = sortedMapOf<Double, Double>()
            for (raw in text.lineSequence()) {
                val line = raw.trim().trimStart('﻿')
                if (line.isEmpty()) continue
                SENSITIVITY.find(line)?.let { sensitivity = it.groupValues[1].toDoubleOrNull() }
                if (line[0] in "*#\";'") continue
                val parts = line.split(SEPARATOR)
                val hz = parts.getOrNull(0)?.toDoubleOrNull() ?: continue
                val db = parts.getOrNull(1)?.toDoubleOrNull() ?: continue
                if (hz <= 0 || !hz.isFinite() || !db.isFinite()) continue
                if (abs(db) > MAX_DEVIATION_DB) throw MeasurementFileException("Implausible calibration value $db dB at $hz Hz.")
                points[hz] = db
            }
            if (points.size < 2) throw MeasurementFileException("No calibration data found (expected lines with frequency and dB).")
            val span = log10(points.lastKey() / points.firstKey())
            if (span < 1.0) throw MeasurementFileException("The calibration covers less than a decade of frequencies.")
            return MicCalibration(name, points.keys.toDoubleArray(), points.values.toDoubleArray(), sensitivity)
        }

        /** Writes the points in the common two-column text format, so a stored calibration can be read back. */
        fun write(c: MicCalibration): String = buildString {
            appendLine("* ${c.name}")
            c.sensitivityDb?.let { appendLine("\"Sens Factor =${it}dB\"") }
            for (i in c.frequencies.indices) appendLine("${c.frequencies[i]}\t${c.deviationDb[i]}")
        }

        private const val MAX_DEVIATION_DB = 40.0
    }
}
