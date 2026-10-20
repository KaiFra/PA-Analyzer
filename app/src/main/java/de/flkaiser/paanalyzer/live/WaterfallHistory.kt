package de.flkaiser.paanalyzer.live

import de.flkaiser.paanalyzer.dsp.LiveSettings
import de.flkaiser.paanalyzer.dsp.LiveSpectrum
import de.flkaiser.paanalyzer.dsp.SpectrumColumns
import kotlin.math.roundToInt

/**
 * The last [rows] spectra for the waterfall, each reduced to the log-spaced [columns].
 * Written by the analyzer thread, read by the UI thread.
 */
class WaterfallHistory(val columns: SpectrumColumns, val rows: Int, val intervalMs: Int) {
    private val data = FloatArray(columns.count * rows) { Float.NaN }
    private val row = DoubleArray(columns.count)
    private var newest = -1

    /** Number of rows filled so far (up to [rows]). */
    var size = 0
        private set

    val seconds: Double get() = rows * intervalMs / 1000.0

    @Synchronized
    fun add(spectrum: LiveSpectrum) {
        columns.reduce(spectrum.frequencies, spectrum.levelDb, row)
        newest = (newest + 1) % rows
        val base = newest * columns.count
        for (c in 0 until columns.count) data[base + c] = row[c].toFloat()
        if (size < rows) size++
    }

    @Synchronized
    fun clear() {
        data.fill(Float.NaN)
        newest = -1
        size = 0
    }

    /**
     * Colours all rows into [pixels] (row-major, newest row at the top): levels from [minDb] to
     * [maxDb] span [colors]; rows not measured yet and missing values get [empty].
     */
    @Synchronized
    fun render(pixels: IntArray, colors: IntArray, minDb: Double, maxDb: Double, empty: Int) {
        val cols = columns.count
        require(pixels.size >= cols * rows)
        val top = colors.size - 1
        val scale = top / (maxDb - minDb).coerceAtLeast(1e-6)
        for (r in 0 until rows) {
            val out = r * cols
            if (r >= size) {
                pixels.fill(empty, out, out + cols)
                continue
            }
            val src = Math.floorMod(newest - r, rows) * cols
            for (c in 0 until cols) {
                val v = data[src + c]
                pixels[out + c] = if (v.isNaN()) empty else colors[((v - minDb) * scale).toInt().coerceIn(0, top)]
            }
        }
    }

    /** Level of the newest row at column [c] (NaN if none). */
    @Synchronized
    fun newestAt(c: Int): Float = if (size == 0) Float.NaN else data[newest * columns.count + c]

    /** True when this history fits spectra of [settings] (same frequency axis and row interval). */
    fun fits(settings: LiveSettings): Boolean =
        columns.maxHz == settings.sampleRate / 2.0 && intervalMs == settings.intervalMs

    companion object {
        const val MIN_HZ = 20.0
        const val COLUMNS = 512
        private const val SECONDS = 15.0

        fun create(settings: LiveSettings): WaterfallHistory = WaterfallHistory(
            SpectrumColumns(MIN_HZ, settings.sampleRate / 2.0, COLUMNS),
            rows = (SECONDS * 1000 / settings.intervalMs).roundToInt().coerceIn(60, 600),
            intervalMs = settings.intervalMs,
        )
    }
}

/** Colour scale of the waterfall: "inferno" (matplotlib), perceptually even from black over purple and orange to pale yellow. */
object ColorMap {
    val STOPS: List<Int> = listOf(
        0xFF000004, 0xFF160B39, 0xFF420A68, 0xFF6A176E, 0xFF932667, 0xFFBC3754,
        0xFFDD513A, 0xFFF37819, 0xFFFCA50A, 0xFFF6D746, 0xFFFCFFA4,
    ).map { it.toInt() }

    /** 256 colours from quiet to loud. */
    val LUT: IntArray = IntArray(256) { at(it / 255.0) }

    /** Colour at [t] (0..1) as ARGB. */
    fun at(t: Double): Int {
        val x = t.coerceIn(0.0, 1.0) * (STOPS.size - 1)
        val i = x.toInt().coerceAtMost(STOPS.size - 2)
        val f = x - i
        val a = STOPS[i]
        val b = STOPS[i + 1]
        fun channel(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * f).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
