package de.flkaiser.paanalyzer.dsp

import java.math.BigDecimal
import java.math.MathContext

/** A measurement with the metadata needed to store and list it. */
class MeasurementDocument(
    val name: String,
    val createdAtMillis: Long,
    val result: MeasurementResult,
)

class MeasurementFileException(message: String) : Exception(message)

/**
 * Measurement files are CSV, readable by spreadsheets and by this app:
 *
 * ```
 * # PA Analyzer measurement
 * # format=1
 * # name=…, created=…, setting.*=…, delay_ms=…, warning=…
 *
 * frequency_hz,fundamental_db,thd_db,thd_percent,h2_db,…
 * …
 *
 * frequency_hz,rub_buzz_rms_db,rub_buzz_peak_db,crest_factor_db,noise_floor_db
 * …
 * ```
 * Numbers use '.' and five significant digits; an empty cell is "not measured".
 */
object MeasurementFile {

    const val FORMAT_VERSION = 1
    private const val MAGIC = "# PA Analyzer measurement"
    private const val DISTORTION_HEADER = "frequency_hz,fundamental_db,thd_db,thd_percent"
    private const val RUB_BUZZ_HEADER = "frequency_hz,rub_buzz_rms_db,rub_buzz_peak_db,crest_factor_db,noise_floor_db"

    fun write(doc: MeasurementDocument): String = buildString {
        val r = doc.result
        appendLine(MAGIC)
        appendLine("# format=$FORMAT_VERSION")
        appendLine("# name=${singleLine(doc.name)}")
        appendLine("# created=${doc.createdAtMillis}")
        for ((k, v) in SweepSettingsCodec.encode(r.settings)) appendLine("# setting.$k=$v")
        appendLine("# delay_ms=${r.delayMs}")
        appendLine("# input_peak_dbfs=${r.inputPeakDbfs}")
        appendLine("# ir_snr_db=${r.irSnrDb}")
        for (w in r.warnings) appendLine("# warning=${singleLine(w)}")
        appendLine()

        append(DISTORTION_HEADER)
        for (h in r.harmonics) append(",h${h.order}_db")
        appendLine()
        for (i in r.frequencies.indices) {
            append(num(r.frequencies[i])).append(',')
            append(num(r.fundamentalDb[i])).append(',')
            val thd = r.thdDb[i]
            append(num(thd)).append(',')
            append(if (thd.isNaN()) "" else num(dbToPercent(thd)))
            for (h in r.harmonics) append(',').append(num(h.levelDb[i]))
            appendLine()
        }

        appendLine()
        appendLine(RUB_BUZZ_HEADER)
        val rb = r.rubBuzz
        for (i in 0 until rb.size) {
            appendLine(
                listOf(rb.frequencies[i], rb.rmsDb[i], rb.peakDb[i], rb.crestDb[i], rb.noiseFloorDb[i]).joinToString(",") { num(it) },
            )
        }
    }

    /** Reads only the metadata (name, creation time) from the comment block. */
    fun readHeader(text: String): Pair<String, Long> {
        val meta = metadata(text.lineSequence())
        return (meta.single["name"] ?: "") to (meta.single["created"]?.toLongOrNull() ?: 0L)
    }

    fun read(text: String): MeasurementDocument {
        val lines = text.lines().map { it.trimEnd('\r') }
        val meta = metadata(lines.asSequence())
        val format = meta.single["format"]?.toIntOrNull()
            ?: throw MeasurementFileException("Not a PA Analyzer measurement file.")
        if (format > FORMAT_VERSION) throw MeasurementFileException("The file was written by a newer version of the app.")

        val settings = SweepSettingsCodec.decode(
            meta.single.filterKeys { it.startsWith("setting.") }.mapKeys { it.key.removePrefix("setting.") },
        )

        val distortion = table(lines, DISTORTION_HEADER) ?: throw MeasurementFileException("The file has no frequency response table.")
        val columns = distortion.header
        val harmonicColumns = columns.withIndex()
            .mapNotNull { (i, c) -> Regex("h(\\d+)_db").matchEntire(c)?.let { it.groupValues[1].toInt() to i } }
        val frequencies = distortion.column(0)
        val fundamental = distortion.column(1)
        val thd = distortion.column(2)
        val harmonics = harmonicColumns.map { (order, i) -> HarmonicCurve(order, distortion.column(i)) }
        if (frequencies.isEmpty()) throw MeasurementFileException("The frequency response table is empty.")

        val rb = table(lines, RUB_BUZZ_HEADER)
        val rubBuzz = if (rb == null) RubBuzzResult.EMPTY else RubBuzzResult(rb.column(0), rb.column(1), rb.column(2), rb.column(3), rb.column(4))

        val result = MeasurementResult(
            settings = settings,
            frequencies = frequencies,
            fundamentalDb = fundamental,
            thdDb = thd,
            harmonics = harmonics,
            rubBuzz = rubBuzz,
            delayMs = meta.single["delay_ms"]?.toDoubleOrNull() ?: Double.NaN,
            inputPeakDbfs = meta.single["input_peak_dbfs"]?.toDoubleOrNull() ?: Double.NaN,
            irSnrDb = meta.single["ir_snr_db"]?.toDoubleOrNull() ?: Double.NaN,
            warnings = meta.multi["warning"].orEmpty(),
        )
        return MeasurementDocument(
            name = meta.single["name"] ?: "",
            createdAtMillis = meta.single["created"]?.toLongOrNull() ?: 0L,
            result = result,
        )
    }

    private class Metadata(val single: Map<String, String>, val multi: Map<String, List<String>>)

    private fun metadata(lines: Sequence<String>): Metadata {
        val first = lines.firstOrNull()?.trim()
        if (first != MAGIC) return Metadata(emptyMap(), emptyMap())
        val single = mutableMapOf<String, String>()
        val multi = mutableMapOf<String, MutableList<String>>()
        for (raw in lines.drop(1)) {
            val line = raw.trimEnd('\r')
            if (!line.startsWith("#")) break
            val body = line.removePrefix("#").trim()
            val eq = body.indexOf('=')
            if (eq <= 0) continue
            val key = body.substring(0, eq).trim()
            val value = body.substring(eq + 1).trim()
            single[key] = value
            multi.getOrPut(key) { mutableListOf() } += value
        }
        return Metadata(single, multi)
    }

    private class Table(val header: List<String>, val rows: List<List<String>>) {
        fun column(i: Int) = DoubleArray(rows.size) { r -> rows[r].getOrNull(i)?.trim()?.toDoubleOrNull() ?: Double.NaN }
    }

    private fun table(lines: List<String>, headerPrefix: String): Table? {
        val start = lines.indexOfFirst { it.trim().startsWith(headerPrefix) }
        if (start < 0) return null
        val header = lines[start].trim().split(',')
        val rows = lines.drop(start + 1).takeWhile { it.isNotBlank() }.map { it.split(',') }
        return Table(header, rows)
    }

    private fun singleLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()

    /** Five significant digits in plain notation, empty for NaN. */
    fun num(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return ""
        if (v == 0.0) return "0"
        return BigDecimal(v).round(MathContext(5)).stripTrailingZeros().toPlainString()
    }
}
