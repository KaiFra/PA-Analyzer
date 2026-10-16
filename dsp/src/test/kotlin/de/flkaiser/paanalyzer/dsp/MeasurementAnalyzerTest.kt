package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MeasurementAnalyzerTest {

    /** Short sweep so the suite stays fast; -6 dBFS gives amplitude 0.5. */
    private val settings = SweepSettings(
        durationSec = 2.0,
        levelDbfs = -6.0206,
        preSilenceSec = 0.5,
        postSilenceSec = 1.0,
        smoothing = Smoothing.NONE,
    )

    private fun analyze(
        s: SweepSettings = settings,
        delay: (Int) -> Int = { 4_800 },
        noiseDbfs: Double = -110.0,
        system: (DoubleArray) -> DoubleArray,
    ): MeasurementResult {
        val recording = SimulatedSystem.record(s, delaySamples = delay, noiseDbfs = noiseDbfs, system = system)
        return MeasurementAnalyzer(s).analyze(recording)
    }

    private fun MeasurementResult.indicesIn(lo: Double, hi: Double) = frequencies.indices.filter { frequencies[it] in lo..hi }

    @Test
    fun linearSystemHasFlatResponseAndNoDistortion() {
        val result = analyze(system = SimulatedSystem.memoryless { 0.5 * it })
        for (i in result.indicesIn(40.0, 16_000.0)) {
            assertEquals(-6.02, result.fundamentalDb[i], 0.3, "fundamental at ${result.frequencies[i]} Hz")
        }
        for (i in result.indicesIn(40.0, 8_000.0)) {
            assertTrue(result.thdDb[i] < -60.0, "THD ${result.thdDb[i]} dB at ${result.frequencies[i]} Hz")
        }
        assertEquals(100.0, result.delayMs, 1.0)
        assertTrue(result.irSnrDb > 60.0, "IR SNR ${result.irSnrDb}")
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun quadraticNonlinearityGivesSecondHarmonic() {
        // y = x + a x² at amplitude A: second harmonic is a·A/2 relative to the fundamental.
        val a = 0.2
        val result = analyze(system = SimulatedSystem.memoryless { it + a * it * it })
        val expected = 20 * log10(a * 0.5 / 2)
        val h2 = result.harmonics.first { it.order == 2 }
        val h3 = result.harmonics.first { it.order == 3 }
        for (i in result.indicesIn(50.0, 5_000.0)) {
            assertEquals(expected, h2.levelDb[i], 0.5, "H2 at ${result.frequencies[i]} Hz")
            assertEquals(expected, result.thdDb[i], 0.5, "THD at ${result.frequencies[i]} Hz")
            assertTrue(h3.levelDb[i] < expected - 30, "H3 ${h3.levelDb[i]} dB at ${result.frequencies[i]} Hz")
        }
        // ~5 % distortion.
        val mid = result.indicesIn(900.0, 1_100.0).first()
        assertEquals(5.0, dbToPercent(result.thdDb[mid]), 0.3)
    }

    @Test
    fun cubicNonlinearityGivesThirdHarmonic() {
        // y = x + b x³: fundamental gain 1 + 3/4·b·A², third harmonic b·A²/4.
        val b = 0.4
        val amplitude = 0.5
        val result = analyze(system = SimulatedSystem.memoryless { it + b * it * it * it })
        val gain = 1 + 0.75 * b * amplitude * amplitude
        val expectedH3 = 20 * log10(b * amplitude * amplitude / 4 / gain)
        val h2 = result.harmonics.first { it.order == 2 }
        val h3 = result.harmonics.first { it.order == 3 }
        for (i in result.indicesIn(50.0, 5_000.0)) {
            assertEquals(20 * log10(gain), result.fundamentalDb[i], 0.3, "fundamental at ${result.frequencies[i]} Hz")
            assertEquals(expectedH3, h3.levelDb[i], 0.5, "H3 at ${result.frequencies[i]} Hz")
            assertTrue(h2.levelDb[i] < expectedH3 - 30, "H2 ${h2.levelDb[i]} dB at ${result.frequencies[i]} Hz")
        }
    }

    @Test
    fun harmonicsAboveTheSweepRangeAreNotReported() {
        val result = analyze(system = SimulatedSystem.memoryless { it })
        val h3 = result.harmonics.first { it.order == 3 }
        for (i in result.frequencies.indices) {
            val f = result.frequencies[i]
            if (2 * f > settings.endHz) assertTrue(result.thdDb[i].isNaN(), "THD at $f Hz should be NaN")
            if (3 * f > settings.endHz) assertTrue(h3.levelDb[i].isNaN(), "H3 at $f Hz should be NaN")
        }
    }

    @Test
    fun lowPassFilterResponseMatchesTheory() {
        val fs = settings.sampleRate.toDouble()
        val alpha = 1 - exp(-2 * PI * 1_000.0 / fs)
        val result = analyze(system = SimulatedSystem.onePoleLowPass(alpha))
        for (i in result.indicesIn(40.0, 15_000.0)) {
            val w = 2 * PI * result.frequencies[i] / fs
            val magnitude = alpha / hypot(1 - (1 - alpha) * cos(w), (1 - alpha) * sin(w))
            assertEquals(20 * log10(magnitude), result.fundamentalDb[i], 0.3, "at ${result.frequencies[i]} Hz")
        }
    }

    @Test
    fun averagingImprovesSignalToNoise() {
        val system = SimulatedSystem.memoryless { 0.5 * it }
        val single = analyze(noiseDbfs = -50.0, system = system)
        val averaged = analyze(s = settings.copy(averages = 4), noiseDbfs = -50.0, system = system)
        assertTrue(averaged.irSnrDb > single.irSnrDb + 4.0, "single ${single.irSnrDb} dB, averaged ${averaged.irSnrDb} dB")
        assertTrue(single.warnings.none { "No sweep" in it }, "a noisy but valid sweep must still be detected")
    }

    @Test
    fun worksAtOtherSampleRates() {
        for (rate in listOf(44_100, 96_000)) {
            val s = settings.copy(sampleRate = rate)
            val result = analyze(s = s, delay = { rate / 10 }, system = SimulatedSystem.memoryless { 0.5 * it })
            for (i in result.indicesIn(40.0, 16_000.0)) {
                assertEquals(-6.02, result.fundamentalDb[i], 0.3, "at ${result.frequencies[i]} Hz, $rate Hz")
            }
            assertEquals(100.0, result.delayMs, 1.0)
        }
    }

    @Test
    fun averagingAlignsRepetitionsWithDifferentLatency() {
        // Bluetooth latency may differ slightly between repetitions; misaligned averaging would cut the treble.
        val delays = intArrayOf(4_800, 4_803, 4_797, 4_801)
        val result = analyze(s = settings.copy(averages = 4), delay = { delays[it] }, system = SimulatedSystem.memoryless { 0.5 * it })
        for (i in result.indicesIn(40.0, 16_000.0)) {
            assertEquals(-6.02, result.fundamentalDb[i], 0.3, "at ${result.frequencies[i]} Hz")
        }
    }

    @Test
    fun smoothingKeepsAFlatResponseFlat() {
        val result = analyze(s = settings.copy(smoothing = Smoothing.OCT_3), system = SimulatedSystem.memoryless { 0.5 * it })
        for (i in result.indicesIn(50.0, 12_000.0)) assertEquals(-6.02, result.fundamentalDb[i], 0.3)
    }

    @Test
    fun warnsWhenNoSweepIsRecorded() {
        val result = analyze(noiseDbfs = -40.0, system = { x -> DoubleArray(x.size) })
        assertTrue(result.warnings.any { "No sweep detected" in it }, result.warnings.toString())
    }

    @Test
    fun warnsWhenInputClips() {
        val result = analyze(system = SimulatedSystem.memoryless { 2.5 * it })
        assertTrue(result.warnings.any { "clipped" in it }, result.warnings.toString())
    }

    @Test
    fun rejectsShortRecordingAndInvalidSettings() {
        assertFailsWith<MeasurementException> { MeasurementAnalyzer(settings).analyze(FloatArray(1_000)) }
        assertFailsWith<MeasurementException> {
            MeasurementAnalyzer(settings.copy(levelDbfs = 6.0)).analyze(FloatArray(10_000_000))
        }
    }
}
