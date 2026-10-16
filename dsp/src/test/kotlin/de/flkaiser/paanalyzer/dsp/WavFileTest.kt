package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavFileTest {

    private val samples = FloatArray(1_000) { (0.8 * sin(it * 0.05)).toFloat() }

    @Test
    fun floatRoundTripIsExact() {
        val bytes = WavFile.write(samples, 48_000)
        assertEquals(44 + 12 + 4 * samples.size, bytes.size)
        val read = WavFile.read(bytes)
        assertEquals(48_000, read.sampleRate)
        assertContentEquals(samples, read.samples)
    }

    @Test
    fun headerIsStandard() {
        val bytes = WavFile.write(FloatArray(10), 44_100)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(3, bytes[20].toInt()) // IEEE float
    }

    @Test
    fun readsPcm16AndTakesTheFirstChannel() {
        val read = WavFile.read(WavFile.writePcm16(samples, 44_100, channels = 2))
        assertEquals(44_100, read.sampleRate)
        assertEquals(samples.size, read.samples.size)
        for (i in samples.indices) assertEquals(samples[i], read.samples[i], 1e-4f)
    }

    @Test
    fun rejectsOtherData() {
        assertFailsWith<WavFileException> { WavFile.read("hello world, not a wav".toByteArray()) }
        val truncated = WavFile.write(samples, 48_000).copyOf(30)
        assertFailsWith<WavFileException> { WavFile.read(truncated) }
    }

    @Test
    fun aStoredRecordingCanBeAnalysedAgain() {
        val s = SweepSettings(durationSec = 2.0, preSilenceSec = 0.5, postSilenceSec = 1.0)
        val recording = SimulatedSystem.record(s, system = SimulatedSystem.memoryless { 0.5 * it })
        val stored = WavFile.read(WavFile.write(recording, s.sampleRate)).samples
        val a = MeasurementAnalyzer(s).analyze(recording)
        val b = MeasurementAnalyzer(s).analyze(stored)
        assertContentEquals(a.fundamentalDb, b.fundamentalDb)
    }
}
