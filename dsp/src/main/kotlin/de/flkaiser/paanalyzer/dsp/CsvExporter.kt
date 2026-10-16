package de.flkaiser.paanalyzer.dsp

import java.math.BigDecimal
import java.math.MathContext

/** Writes a measurement as CSV (comma separated, '.' decimal separator, '#' comment lines). */
object CsvExporter {

    fun export(result: MeasurementResult): String = buildString {
        val s = result.settings
        appendLine("# PA Analyzer measurement")
        appendLine("# sweep ${num(s.startHz)}-${num(s.endHz)} Hz, ${num(s.durationSec)} s, ${num(s.levelDbfs)} dBFS, ${s.sampleRate} Hz, ${s.averages} average(s)")
        appendLine("# smoothing ${s.smoothing.label}, IR window ${num(s.irWindowMs)} ms, rub & buzz from order ${s.rubBuzzStartOrder}")
        appendLine("# delay ${num(result.delayMs)} ms, input peak ${num(result.inputPeakDbfs)} dBFS, IR SNR ${num(result.irSnrDb)} dB")
        for (w in result.warnings) appendLine("# warning: $w")
        appendLine()

        append("frequency_hz,fundamental_db,thd_db,thd_percent")
        for (h in result.harmonics) append(",h${h.order}_db")
        appendLine()
        for (i in result.frequencies.indices) {
            append(num(result.frequencies[i])).append(',')
            append(num(result.fundamentalDb[i])).append(',')
            val thd = result.thdDb[i]
            append(num(thd)).append(',')
            append(if (thd.isNaN()) "" else num(dbToPercent(thd)))
            for (h in result.harmonics) append(',').append(num(h.levelDb[i]))
            appendLine()
        }

        appendLine()
        appendLine("frequency_hz,rub_buzz_rms_db,rub_buzz_peak_db,crest_factor_db,noise_floor_db")
        val rb = result.rubBuzz
        for (i in 0 until rb.size) {
            appendLine(
                listOf(rb.frequencies[i], rb.rmsDb[i], rb.peakDb[i], rb.crestDb[i], rb.noiseFloorDb[i])
                    .joinToString(",") { num(it) },
            )
        }
    }

    /** Five significant digits in plain notation, empty for NaN. */
    fun num(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return ""
        if (v == 0.0) return "0"
        return BigDecimal(v).round(MathContext(5)).stripTrailingZeros().toPlainString()
    }
}
