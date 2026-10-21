package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveSpectrumAnalyzerTest {

    private val fs = 48_000

    private fun tone(frequency: Double, amplitude: Double, seconds: Double, rate: Int = fs, phase: Double = 0.3) =
        FloatArray((seconds * rate).toInt()) { (amplitude * sin(2 * PI * frequency * it / rate + phase)).toFloat() }

    /** Feeds [signal] in 10 ms blocks, as the audio input does, and returns all spectra. */
    private fun run(analyzer: LiveSpectrumAnalyzer, signal: FloatArray): List<LiveSpectrum> {
        val block = analyzer.settings.sampleRate / 100
        val out = mutableListOf<LiveSpectrum>()
        var i = 0
        while (i < signal.size) {
            val count = minOf(block, signal.size - i)
            analyzer.process(signal, i, count)?.let { out += it }
            i += count
        }
        return out
    }

    private fun settled(settings: LiveSettings, signal: FloatArray) = run(LiveSpectrumAnalyzer(settings), signal).last()

    @Test
    fun windowsHaveTheirCoherentGain() {
        for ((w, gain) in listOf(WindowFunction.HANN to 0.5, WindowFunction.BLACKMAN_HARRIS to 0.35875, WindowFunction.FLAT_TOP to 0.21557895)) {
            val values = w.create(1024)
            assertEquals(gain, values.sum() / values.size, 1e-9, w.name)
            // Periodic window: symmetric around the centre, starting at the minimum.
            assertEquals(values[1], values[1023], 1e-12)
        }
    }

    @Test
    fun bandsJoinWithoutGapsOrOverlap() {
        val settings = LiveSettings(decimations = 2)
        val edges = LiveSpectrumAnalyzer.bandEdges(fs, 2)
        assertEquals(listOf(9_600.0 to 24_000.0, 4_800.0 to 9_600.0, 0.0 to 4_800.0), edges)
        val f = LiveSpectrumAnalyzer(settings).frequencies
        assertTrue((1 until f.size).all { f[it] > f[it - 1] }, "ascending")
        assertEquals(settings.finestBinWidthHz, f[0], 1e-9)
        assertEquals(24_000.0, f.last(), 1e-9)
        // Below 4.8 kHz the points are 2.93 Hz apart, above 9.6 kHz 11.7 Hz.
        assertEquals(48_000.0 / 4_096 / 4, f[1] - f[0], 1e-9)
        assertEquals(48_000.0 / 4_096, f.last() - f[f.size - 2], 1e-9)
        assertEquals(4_800.0, settings.finestBandTopHz, 1e-9)
    }

    @Test
    fun aFullScaleToneReadsZeroDbfsInEveryBand() {
        for (frequency in listOf(52.0, 1_000.0, 6_500.0, 15_000.0)) {
            val s = settled(LiveSettings(window = WindowFunction.FLAT_TOP, averaging = 0.0), tone(frequency, 1.0, 1.5))
            val peak = assertNotNull(s.peak)
            // Within a quarter of the local point spacing (2.9, 5.9 or 11.7 Hz).
            val i = s.frequencies.indexOfFirst { it >= frequency }
            val spacing = s.frequencies[i] - s.frequencies[i - 1]
            assertEquals(frequency, peak.frequencyHz, 0.25 * spacing, "frequency at $frequency Hz")
            assertEquals(0.0, peak.levelDb, 0.1, "level at $frequency Hz")
        }
    }

    @Test
    fun lowTonesAreResolvedByTheDecimatedBands() {
        // 50 and 70 Hz are less than two full-rate bins (11.7 Hz) apart; after two decimations
        // (2.9 Hz bins) there is a deep gap between them.
        val a = tone(50.0, 0.1, 2.0)
        val b = tone(70.0, 0.1, 2.0, phase = 1.0)
        val s = settled(LiveSettings(averaging = 0.0), FloatArray(a.size) { a[it] + b[it] })
        val between = s.levelAt(59.0)
        assertTrue(s.levelAt(50.0) > between + 20 && s.levelAt(70.0) > between + 20, "gap between the tones: ${s.levelAt(50.0)} / $between")
        assertEquals(-20.0, s.peak!!.levelDb, 1.0)
    }

    @Test
    fun decimatedBandsAreFreeOfAliasing() {
        // A loud 21 kHz tone must not fold into the lower bands.
        val s = settled(LiveSettings(averaging = 0.0), tone(21_000.0, 1.0, 1.5))
        val below = s.frequencies.indices.filter { s.frequencies[it] < 9_000 }.maxOf { s.levelDb[it] }
        assertTrue(below < -90.0, "strongest point below 9 kHz: $below dB")
    }

    @Test
    fun halfBandFilterPassesTheUsedBandAndBlocksAliases() {
        fun gainDb(relativeFrequency: Double): Double {
            val decimator = HalfBandDecimator()
            val x = tone(relativeFrequency * fs, 1.0, 1.0)
            val out = mutableListOf<Double>()
            for (v in x) if (decimator.push(v.toDouble())) out += decimator.output
            val tail = out.drop(200)
            val rms = sqrt(tail.sumOf { it * it } / tail.size)
            return 20 * log10(rms * sqrt(2.0))
        }
        assertEquals(0.0, gainDb(0.1), 0.01)
        assertEquals(0.0, gainDb(0.2), 0.01) // top of the used band (0.8 of the new Nyquist)
        assertTrue(gainDb(0.3) < -85.0, "first frequency that would alias into the used band")
        assertTrue(gainDb(0.45) < -85.0)
        assertEquals(0.0, HalfBandDecimator.COEFFICIENTS[HalfBandDecimator.TAPS / 2 + 2], 1e-15) // half-band zero
    }

    @Test
    fun deliversOneSpectrumPerInterval() {
        val analyzer = LiveSpectrumAnalyzer(LiveSettings(intervalMs = 50))
        val spectra = run(analyzer, FloatArray(fs))
        assertEquals(20, spectra.size)
        assertEquals(1.0, spectra.last().timeSec, 1e-9)
    }

    @Test
    fun averagingSmoothsTheRise() {
        val signal = FloatArray(fs / 2) + tone(1_000.0, 0.5, 3.0)
        val fast = run(LiveSpectrumAnalyzer(LiveSettings(averaging = 0.0)), signal)
        val slow = run(LiveSpectrumAnalyzer(LiveSettings(averaging = 0.9)), signal)
        // Two updates after the tone started, the averaged spectrum is still well below the level.
        val i = 12
        assertTrue(fast[i].levelAt(1_000.0) > slow[i].levelAt(1_000.0) + 3, "${fast[i].levelAt(1_000.0)} vs ${slow[i].levelAt(1_000.0)}")
        // Both settle at the same level.
        assertEquals(fast.last().peak!!.levelDb, slow.last().peak!!.levelDb, 0.2)
        assertEquals(72.1, LiveSettings(averaging = 0.5, intervalMs = 50).averagingTimeMs!!, 0.1)
        assertNull(LiveSettings(averaging = 0.0).averagingTimeMs)
    }

    @Test
    fun peakHoldKeepsOrLetsTheMaximumFall() {
        val signal = tone(2_000.0, 0.5, 1.0) + FloatArray(fs)
        val hold = run(LiveSpectrumAnalyzer(LiveSettings(peakHold = PeakHold.HOLD, averaging = 0.0)), signal).last()
        val fall = run(LiveSpectrumAnalyzer(LiveSettings(peakHold = PeakHold.FALL, averaging = 0.0)), signal).last()
        val off = run(LiveSpectrumAnalyzer(LiveSettings(peakHold = PeakHold.OFF, averaging = 0.0)), signal).last()
        val held = hold.levelAt(2_000.0, hold.peakHoldDb!!)
        assertEquals(-6.0, held, 1.0)
        assertTrue(hold.levelAt(2_000.0) < -120, "the tone itself is gone")
        // Up to 1 s after the tone stopped (the analysis block drains first), the falling curve is
        // up to 12 dB/s lower.
        val drop = held - fall.levelAt(2_000.0, fall.peakHoldDb!!)
        assertTrue(drop in 8.0..13.0, "fell by $drop dB")
        assertNull(off.peakHoldDb)

        val analyzer = LiveSpectrumAnalyzer(LiveSettings(peakHold = PeakHold.HOLD, averaging = 0.0))
        run(analyzer, tone(2_000.0, 0.5, 0.5))
        val before = run(analyzer, FloatArray(fs / 2)).last()
        assertTrue(before.levelAt(2_000.0, before.peakHoldDb!!) > -20)
        analyzer.resetPeaks()
        val after = run(analyzer, FloatArray(fs / 10)).last()
        assertTrue(after.levelAt(2_000.0, after.peakHoldDb!!) < -60)
    }

    @Test
    fun appliesTheMicrophoneCalibration() {
        val calibration = MicCalibration("mic.txt", doubleArrayOf(10.0, 30_000.0), doubleArrayOf(3.0, 3.0))
        val signal = tone(1_000.0, 0.5, 1.0)
        val plain = run(LiveSpectrumAnalyzer(LiveSettings(averaging = 0.0)), signal).last()
        val corrected = run(LiveSpectrumAnalyzer(LiveSettings(averaging = 0.0), calibration), signal).last()
        assertEquals(plain.peak!!.levelDb - 3.0, corrected.peak!!.levelDb, 1e-6)
    }

    @Test
    fun noiseFloorFollowsTheScaling() {
        // White noise with σ = 0.01 (−40 dBFS RMS). With the sine scaling (2/Σw)², a bin of a
        // Hann-windowed FFT gets 4σ²·Σw²/(Σw)² = 6σ²/N on average.
        val random = Random(7)
        val noise = FloatArray(2 * fs) { (random.nextGaussian() * 0.01).toFloat() }
        val s = settled(LiveSettings(window = WindowFunction.HANN, averaging = 0.9, decimations = 0), noise)
        val mid = s.frequencies.indices.filter { s.frequencies[it] in 2_000.0..8_000.0 }
        val mean = 10 * log10(mid.sumOf { Math.pow(10.0, s.levelDb[it] / 10) } / mid.size)
        assertEquals(10 * log10(6 * 1e-4 / 4_096), mean, 1.0)
    }

    @Test
    fun columnsKeepNarrowPeaksAndFillGaps() {
        val columns = SpectrumColumns(20.0, 20_000.0, 30) // 10 columns per decade
        val f = DoubleArray(2_000) { 20.0 + it * 10.0 }
        val v = DoubleArray(f.size) { if (abs(f[it] - 5_000.0) < 1) 0.0 else -80.0 }
        val out = columns.reduce(f, v)
        val c = columns.position(5_000.0).toInt()
        assertEquals(0.0, out[c], 1e-9)
        assertTrue(out.all { it.isFinite() } , "the sparse low columns are interpolated")
        assertEquals(-80.0, out[0], 1e-9)
        val outside = SpectrumColumns(10.0, 40_000.0, 40).reduce(f, v)
        assertTrue(outside.first().isNaN() && outside.last().isNaN())
        assertEquals(316.227766, SpectrumColumns(100.0, 10_000.0, 2).frequencyOf(0), 1e-3)
    }

    @Test
    fun namesNotes() {
        assertEquals(MusicalNote("A4", 0), MusicalNote.of(440.0))
        assertEquals("C4", MusicalNote.of(261.6256)!!.name)
        val hum = MusicalNote.of(62.0)!!
        assertEquals("B1", hum.name)
        assertTrue(hum.cents in 5..9)
        assertEquals("B1 +7 ct", hum.toString())
        assertNull(MusicalNote.of(0.0))
    }

    @Test
    fun validatesSettings() {
        assertTrue(LiveSettings().validate().isEmpty())
        assertTrue(LiveSettings(fftSize = 3_000).validate().isNotEmpty())
        assertTrue(LiveSettings(decimations = 5).validate().isNotEmpty())
        assertTrue(LiveSettings(averaging = 0.99).validate().isNotEmpty())
        assertTrue(LiveSettings(intervalMs = 5).validate().isNotEmpty())
        assertTrue(LiveSettings(sampleRate = 22_050).validate().isNotEmpty())
    }
}

private operator fun FloatArray.plus(other: FloatArray): FloatArray = FloatArray(size + other.size) { if (it < size) this[it] else other[it - size] }

private fun Random.nextGaussian(): Double {
    // Box–Muller.
    val u = nextDouble().coerceAtLeast(1e-12)
    val v = nextDouble()
    return sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * v)
}
