package de.flkaiser.paanalyzer

import de.flkaiser.paanalyzer.dsp.LiveSettings
import de.flkaiser.paanalyzer.dsp.LiveSpectrum
import de.flkaiser.paanalyzer.dsp.PeakHold
import de.flkaiser.paanalyzer.dsp.SpectrumColumns
import de.flkaiser.paanalyzer.dsp.WindowFunction
import de.flkaiser.paanalyzer.live.ColorMap
import de.flkaiser.paanalyzer.live.LiveSource
import de.flkaiser.paanalyzer.live.WaterfallHistory
import de.flkaiser.paanalyzer.settings.LiveSettingsCodec
import de.flkaiser.paanalyzer.ui.PlotMath
import de.flkaiser.paanalyzer.ui.formatLiveHz
import de.flkaiser.paanalyzer.ui.timeStep
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTest {

    private fun spectrum(level: Double) = LiveSpectrum(
        frequencies = doubleArrayOf(20.0, 200.0, 2_000.0, 20_000.0),
        levelDb = DoubleArray(4) { level },
        peakHoldDb = null,
        peak = null,
        inputPeakDbfs = -20.0,
        timeSec = 0.0,
    )

    @Test
    fun settingsRoundTrip() {
        val s = LiveSettings(sampleRate = 96_000, fftSize = 8_192, decimations = 3, window = WindowFunction.HANN, intervalMs = 100, averaging = 0.75, peakHold = PeakHold.HOLD)
        val values = LiveSettingsCodec.encode(s, LiveSource.PHONE)
        assertEquals(s, LiveSettingsCodec.decodeSettings(values))
        assertEquals(LiveSource.PHONE, LiveSettingsCodec.decodeSource(values))
        assertTrue(values.keys.all { it.startsWith("live.") })
    }

    @Test
    fun invalidSettingsFallBackToDefaults() {
        assertEquals(LiveSettings(), LiveSettingsCodec.decodeSettings(emptyMap()))
        assertEquals(LiveSettings(), LiveSettingsCodec.decodeSettings(mapOf("live.fftSize" to "1000")))
        assertEquals(LiveSettings(window = WindowFunction.HANN), LiveSettingsCodec.decodeSettings(mapOf("live.window" to "HANN", "live.averaging" to "x")))
        assertEquals(LiveSource.MICROPHONE, LiveSettingsCodec.decodeSource(mapOf("live.source" to "RADIO")))
    }

    @Test
    fun waterfallShowsTheNewestRowOnTop() {
        val history = WaterfallHistory(SpectrumColumns(20.0, 20_000.0, 4), rows = 3, intervalMs = 50)
        val colors = IntArray(101) { it } // colour = level + 100
        val pixels = IntArray(12)
        history.render(pixels, colors, -100.0, 0.0, empty = -1)
        assertTrue(pixels.all { it == -1 }, "nothing measured yet")
        history.add(spectrum(-80.0))
        history.add(spectrum(-20.0))
        history.render(pixels, colors, -100.0, 0.0, empty = -1)
        assertEquals(listOf(80, 80, 80, 80), pixels.slice(0..3), "newest row first")
        assertEquals(listOf(20, 20, 20, 20), pixels.slice(4..7))
        assertEquals(listOf(-1, -1, -1, -1), pixels.slice(8..11))
        // The ring buffer drops the oldest row.
        history.add(spectrum(-50.0))
        history.add(spectrum(0.0))
        history.render(pixels, colors, -100.0, 0.0, empty = -1)
        assertEquals(listOf(100, 50, 80), listOf(pixels[0], pixels[4], pixels[8]))
        // Levels outside the range are clamped to the ends of the colour scale.
        history.render(pixels, colors, -40.0, -30.0, empty = -1)
        assertEquals(listOf(100, 0, 100), listOf(pixels[0], pixels[4], pixels[8]))
        assertEquals(0.15, history.seconds, 1e-9)
    }

    @Test
    fun waterfallFitsItsSettings() {
        val h = WaterfallHistory.create(LiveSettings())
        assertEquals(24_000.0, h.columns.maxHz)
        assertEquals(300, h.rows)
        assertTrue(h.fits(LiveSettings(fftSize = 8_192, averaging = 0.2)))
        assertTrue(!h.fits(LiveSettings(sampleRate = 44_100)))
        assertTrue(!h.fits(LiveSettings(intervalMs = 100)))
    }

    @Test
    fun colorMapRunsFromDarkToBright() {
        fun brightness(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
        val lut = ColorMap.LUT
        assertEquals(256, lut.size)
        assertEquals(0xFF000004.toInt(), lut.first())
        assertEquals(0xFFFCFFA4.toInt(), lut.last())
        assertTrue((1 until lut.size).all { brightness(lut[it]) >= brightness(lut[it - 1]) - 3 }, "brightness rises")
        assertTrue(lut.all { (it ushr 24) == 0xFF }, "opaque")
    }

    @Test
    fun maxEnvelopeKeepsOnePointPerPixel() {
        // 1,000 points between 1 and 2 kHz drawn into 10 pixels: one point per pixel, the highest.
        val f = DoubleArray(1_000) { 1_000.0 + it }
        val v = DoubleArray(f.size) { if (it == 501) 10.0 else -(it % 7).toDouble() }
        val (xs, values) = PlotMath.maxEnvelope(f, v, 1_000.0, 2_000.0, 10f)
        assertTrue(xs.size in 10..11, "${xs.size} points")
        assertEquals(10.0, values.max())
        assertTrue((1 until xs.size).all { xs[it] > xs[it - 1] })
        // Gaps stay gaps.
        val (_, gapped) = PlotMath.maxEnvelope(doubleArrayOf(100.0, 200.0, 300.0), doubleArrayOf(1.0, Double.NaN, 2.0), 100.0, 1_000.0, 1_000f)
        assertEquals(3, gapped.size)
        assertTrue(gapped[1].isNaN())
    }

    @Test
    fun formatsFrequenciesAndTimeSteps() {
        assertEquals("62.3 Hz", formatLiveHz(62.31))
        assertEquals("155 Hz", formatLiveHz(155.2))
        assertEquals("4.80 kHz", formatLiveHz(4_800.0))
        assertEquals("12.5 kHz", formatLiveHz(12_480.0))
        assertEquals(5.0, timeStep(15.0, maxLabels = 4))
        assertEquals(2.0, timeStep(15.0, maxLabels = 8))
    }
}
