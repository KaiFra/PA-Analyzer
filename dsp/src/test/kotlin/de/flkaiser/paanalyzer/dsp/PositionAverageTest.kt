package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.math.log10
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PositionAverageTest {

    private val grid = LogGrid.create(20.0, 20_000.0, 48)

    private fun result(
        frequencies: DoubleArray = grid,
        thd: Double = -40.0,
        level: (Double) -> Double,
    ): MeasurementResult {
        val rbF = LogGrid.create(frequencies.first(), frequencies.last(), 6)
        return MeasurementResult(
            settings = SweepSettings(),
            frequencies = frequencies,
            fundamentalDb = DoubleArray(frequencies.size) { level(frequencies[it]) },
            thdDb = DoubleArray(frequencies.size) { thd },
            harmonics = listOf(HarmonicCurve(2, DoubleArray(frequencies.size) { thd }), HarmonicCurve(3, DoubleArray(frequencies.size) { thd - 6 })),
            rubBuzz = RubBuzzResult(rbF, DoubleArray(rbF.size) { -50.0 }, DoubleArray(rbF.size) { -40.0 }, DoubleArray(rbF.size) { 10.0 }, DoubleArray(rbF.size) { -60.0 }),
            delayMs = 200.0,
            inputPeakDbfs = -10.0,
            irSnrDb = 60.0,
            warnings = emptyList(),
        )
    }

    private fun at(r: MeasurementResult, hz: Double) = r.fundamentalDb[r.frequencies.indexOfFirst { it >= hz }]

    @Test
    fun aDipAtOnePositionIsFilledByTheOthers() {
        val dip = result { if (it in 150.0..250.0) -20.0 else -10.0 }
        val flat = result { -10.0 }
        val avg = PositionAverage.average(listOf(dip, flat, flat))
        // Power average of 0, 0 and -10 dB relative: 10·log10((1 + 1 + 0.1) / 3) = -1.5 dB.
        assertEquals(-10.0 + 10 * log10(2.1 / 3), at(avg, 200.0), 0.05)
        assertEquals(-10.0, at(avg, 1_000.0), 1e-6)
    }

    @Test
    fun aResonanceOfTheSpeakerStays() {
        val peak: (Double) -> Double = { if (it in 2_000.0..3_000.0) -4.0 else -10.0 }
        val avg = PositionAverage.average(listOf(result(level = peak), result(level = peak)))
        assertEquals(-4.0, at(avg, 2_500.0), 1e-6)
    }

    @Test
    fun levelsAreAlignedInTheMidBand() {
        val near = result { -10.0 + if (it < 100) 3.0 else 0.0 }
        val far = result { -22.0 } // 12 dB quieter, no bass bump
        val avg = PositionAverage.average(listOf(near, far))
        assertEquals(-10.0, at(avg, 1_000.0), 1e-6)
        assertEquals(-10.0 + 10 * log10((10.0.pow(0.3) + 1) / 2), at(avg, 50.0), 0.05)
        // Without alignment, the far position hardly counts.
        val raw = PositionAverage.average(listOf(near, far), alignLevels = false)
        assertTrue(at(raw, 1_000.0) < -12.0)
    }

    @Test
    fun distortionIsAveragedAsAbsoluteLevel() {
        val a = result(thd = -30.0) { -10.0 }
        val b = result(thd = -50.0) { -10.0 }
        val avg = PositionAverage.average(listOf(a, b))
        val expected = 10 * log10((1e-3 + 1e-5) / 2)
        assertEquals(expected, avg.thdDb[100], 0.01)
        assertEquals(listOf(2, 3), avg.harmonics.map { it.order })
        assertEquals(expected - 6, avg.harmonics[1].levelDb[100], 0.01)
        assertEquals(-50.0, avg.rubBuzz.rmsDb[3], 1e-6)
    }

    @Test
    fun usesTheCommonFrequencyRange() {
        val wide = result { -10.0 }
        val narrow = result(frequencies = LogGrid.create(50.0, 10_000.0, 48)) { -10.0 }
        val avg = PositionAverage.average(listOf(wide, narrow))
        assertTrue(avg.frequencies.first() >= 50.0 * 0.999 && avg.frequencies.last() <= 10_000.0 * 1.001)
        assertTrue(avg.fundamentalDb.all { it.isFinite() }, "every point has data from both positions")
        assertTrue(avg.rubBuzz.frequencies.first() >= 50.0 * 0.999)
    }

    @Test
    fun needsTwoPositionsWithACommonRange() {
        assertFailsWith<IllegalArgumentException> { PositionAverage.average(listOf(result { 0.0 })) }
        val low = result(frequencies = LogGrid.create(20.0, 200.0, 48)) { 0.0 }
        val high = result(frequencies = LogGrid.create(300.0, 20_000.0, 48)) { 0.0 }
        assertFailsWith<IllegalArgumentException> { PositionAverage.average(listOf(low, high)) }
    }

    @Test
    fun interpolatesOverLogFrequency() {
        val xs = doubleArrayOf(100.0, 400.0)
        val ys = doubleArrayOf(0.0, 2.0)
        val r = PositionAverage.interpolate(xs, ys, doubleArrayOf(50.0, 100.0, 200.0, 400.0, 800.0))
        assertTrue(r[0].isNaN() && r[4].isNaN())
        assertEquals(listOf(0.0, 1.0, 2.0), listOf(r[1], r[2], r[3]).map { Math.round(it * 1e9) / 1e9 })
    }
}

private fun Double.pow(x: Double) = Math.pow(this, x)
