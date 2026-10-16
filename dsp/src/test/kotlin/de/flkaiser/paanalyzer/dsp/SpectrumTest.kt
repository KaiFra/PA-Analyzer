package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals

class SpectrumTest {

    @Test
    fun logGridSpansRangeWithRequestedDensity() {
        val grid = LogGrid.create(20.0, 20_480.0, 12)
        assertEquals(20.0, grid.first(), 1e-9)
        assertEquals(20_480.0, grid.last(), 1e-6)
        assertEquals(10 * 12 + 1, grid.size)
        assertEquals(40.0, grid[12], 1e-9)
    }

    @Test
    fun interpolatesBetweenBins() {
        val spectrum = PowerSpectrum(doubleArrayOf(0.0, 10.0, 20.0, 30.0), binHz = 100.0)
        assertEquals(15.0, spectrum.powerAt(150.0), 1e-12)
        assertEquals(30.0, spectrum.powerAt(1_000.0), 1e-12)
        assertEquals(0.0, spectrum.powerAt(-5.0), 1e-12)
    }

    @Test
    fun fractionalOctaveAveragesBins() {
        // Power equal to the bin index: the band average equals the mean index of the band.
        val spectrum = PowerSpectrum(DoubleArray(10_001) { it.toDouble() }, binHz = 1.0)
        val f = 1_000.0
        val half = Math.pow(2.0, 1.0 / 6.0)
        val lo = kotlin.math.ceil(f / half)
        val hi = kotlin.math.floor(f * half)
        assertEquals((lo + hi) / 2.0, spectrum.powerAt(f, fraction = 3), 1e-9)
        // A flat spectrum stays flat under smoothing.
        val flat = PowerSpectrum(DoubleArray(1_000) { 4.0 }, binHz = 10.0)
        for (fraction in listOf(3, 6, 12, 24)) assertEquals(4.0, flat.powerAt(sqrt(2.0) * 1_000, fraction), 1e-12)
    }

    @Test
    fun decibelHelpers() {
        assertEquals(-20.0, powerToDb(0.01), 1e-12)
        assertEquals(10.0, dbToPercent(-20.0), 1e-9)
        assertEquals(-300.0, powerToDb(0.0), 1e-9)
    }
}
