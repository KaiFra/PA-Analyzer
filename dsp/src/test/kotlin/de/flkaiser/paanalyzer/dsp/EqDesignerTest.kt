package de.flkaiser.paanalyzer.dsp

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqFilterTest {

    @Test
    fun peakHasItsGainAtTheCentre() {
        val f = EqFilter(FilterType.PEAK, 1_000.0, -6.0, 2.0)
        assertEquals(-6.0, f.responseDb(1_000.0), 0.01)
        assertEquals(0.0, f.responseDb(50.0), 0.05)
        assertEquals(0.0, f.responseDb(15_000.0), 0.1)
    }

    @Test
    fun shelvesReachTheirGainAtTheFarEnd() {
        val low = EqFilter(FilterType.LOW_SHELF, 200.0, 4.0)
        assertEquals(4.0, low.responseDb(20.0), 0.1)
        assertEquals(2.0, low.responseDb(200.0), 0.1)
        assertEquals(0.0, low.responseDb(5_000.0), 0.05)
        val high = EqFilter(FilterType.HIGH_SHELF, 5_000.0, -3.0)
        assertEquals(-3.0, high.responseDb(20_000.0), 0.3)
        assertEquals(0.0, high.responseDb(100.0), 0.05)
    }

    @Test
    fun qAndBandwidth() {
        assertEquals(1.0, EqFilter(FilterType.PEAK, 1_000.0, 3.0, 1.414).bandwidthOctaves, 0.01)
        assertEquals(1.0 / 3.0, EqFilter(FilterType.PEAK, 1_000.0, 3.0, 4.32).bandwidthOctaves, 0.01)
    }

    @Test
    fun linkwitzRileyHighPassIsSixDbDownAtCorner() {
        val hp = HighPassSuggestion(80.0)
        assertEquals(-6.0, hp.responseDb(80.0), 0.1)
        assertEquals(0.0, hp.responseDb(2_000.0), 0.05)
        assertTrue(hp.responseDb(40.0) < -20.0)
    }
}

class EqDesignerTest {

    private val grid = LogGrid.create(20.0, 20_000.0, 48)

    private fun response(vararg filters: EqFilter, highPass: HighPassSuggestion? = null, level: Double = -12.0) =
        DoubleArray(grid.size) { i ->
            level + filters.sumOf { it.responseDb(grid[i]) } + (highPass?.responseDb(grid[i]) ?: 0.0)
        }

    private fun design(response: DoubleArray, settings: EqSettings = EqSettings(minHz = 20.0, maxHz = 20_000.0, suggestHighPass = false)) =
        EqDesigner.design(grid, response, settings)

    @Test
    fun flatResponseNeedsNoEq() {
        val s = design(response())
        assertTrue(s.filters.isEmpty(), "filters: ${s.filters}")
        assertTrue(s.rmsAfterDb < 0.1)
        // The measurement is aligned with the target, whatever its absolute level.
        assertEquals(0.0, s.measuredDb[grid.size / 2], 1e-9)
    }

    @Test
    fun correctsASinglePeak() {
        val s = design(response(EqFilter(FilterType.PEAK, 250.0, 6.0, 3.0)))
        assertEquals(1, s.filters.size, "a single resonance needs a single band: ${s.filters}")
        val main = s.filters.maxBy { abs(it.gainDb) }
        assertEquals(FilterType.PEAK, main.type)
        assertEquals(250.0, main.frequencyHz, 25.0)
        assertEquals(-6.0, main.gainDb, 1.0)
        assertEquals(3.0, main.q, 1.0)
        assertTrue(s.rmsBeforeDb > 1.0)
        assertTrue(s.rmsAfterDb < 0.4, "after ${s.rmsAfterDb}")
    }

    @Test
    fun cutsPeaksButBoostsDipsOnlyWithinTheLimit() {
        val s = design(response(EqFilter(FilterType.PEAK, 1_000.0, -15.0, 5.0), EqFilter(FilterType.PEAK, 3_000.0, 15.0, 3.0)))
        for (f in s.filters) {
            assertTrue(f.gainDb in -12.0..3.0, "gain out of range: $f")
            assertTrue(f.q in 0.4..8.0, "Q out of range: $f")
        }
        val atPeak = s.filters.filter { it.frequencyHz in 2_500.0..3_600.0 }
        assertTrue(atPeak.any { it.gainDb <= -10.0 }, "the 3 kHz peak should be cut hard: ${s.filters}")
    }

    @Test
    fun reducesAComplexResponse() {
        val r = response(
            EqFilter(FilterType.PEAK, 90.0, 5.0, 2.0),
            EqFilter(FilterType.PEAK, 400.0, -4.0, 1.5),
            EqFilter(FilterType.PEAK, 2_500.0, 4.0, 2.5),
            EqFilter(FilterType.HIGH_SHELF, 6_000.0, -3.0),
        )
        val s = design(r)
        assertTrue(s.filters.size <= 6)
        assertTrue(s.rmsAfterDb < 0.4 * s.rmsBeforeDb, "before ${s.rmsBeforeDb}, after ${s.rmsAfterDb}")
        assertTrue(s.filters.all { abs(it.gainDb) >= 1.0 }, "no bands that do (almost) nothing: ${s.filters}")
        // The prediction is the measurement plus the EQ.
        for (i in grid.indices) assertEquals(s.measuredDb[i] + s.eqDb[i], s.predictedDb[i], 1e-9)
    }

    @Test
    fun respectsTheNumberOfFilters() {
        val r = response(
            EqFilter(FilterType.PEAK, 60.0, 5.0, 3.0),
            EqFilter(FilterType.PEAK, 200.0, 5.0, 3.0),
            EqFilter(FilterType.PEAK, 800.0, 5.0, 3.0),
            EqFilter(FilterType.PEAK, 3_000.0, 5.0, 3.0),
        )
        assertTrue(design(r, EqSettings(minHz = 20.0, maxHz = 20_000.0, filterCount = 2)).filters.size <= 2)
    }

    @Test
    fun suggestsAHighPassWhereTheSpeakerRollsOff() {
        val r = response(highPass = HighPassSuggestion(70.0, slopeDbPerOctave = 12))
        val s = design(r, EqSettings(minHz = 20.0, maxHz = 20_000.0))
        val hp = assertNotNull(s.highPass)
        assertTrue(hp.frequencyHz in 45.0..75.0, "high-pass at ${hp.frequencyHz}")
        assertTrue(s.correctedFromHz > 45.0)
        // Never boost into the roll-off, and the combined EQ stays within the boost limit.
        assertTrue(s.filters.none { it.gainDb > 0 && it.frequencyHz < s.correctedFromHz }, "${s.filters}")
        assertCombinedWithinLimits(s)
        assertTrue(s.notes.any { "high-pass" in it })
    }

    private fun assertCombinedWithinLimits(s: EqSuggestion) {
        for (i in grid.indices) {
            val bands = s.filters.sumOf { it.responseDb(grid[i]) }
            assertTrue(bands <= s.settings.maxBoostDb + 0.5, "combined boost ${bands} dB at ${grid[i]} Hz: ${s.filters}")
            assertTrue(bands >= -s.settings.maxCutDb - 0.5, "combined cut ${bands} dB at ${grid[i]} Hz: ${s.filters}")
        }
    }

    @Test
    fun combinedEqStaysWithinLimitsForDeepDips() {
        val r = response(EqFilter(FilterType.PEAK, 120.0, -14.0, 1.5), EqFilter(FilterType.PEAK, 5_000.0, 16.0, 1.0))
        assertCombinedWithinLimits(design(r))
    }

    @Test
    fun doesNotBoostWhereTheSpeakerFallsOff() {
        // 2nd-order roll-off at 50 Hz: only -3 dB at the bottom of the correction range, so no high-pass,
        // but boosting the bass slope would still cost excursion.
        val r = response(highPass = HighPassSuggestion(50.0, slopeDbPerOctave = 12))
        val s = design(r, EqSettings(minHz = 40.0, maxHz = 12_000.0, suggestHighPass = false))
        for (i in grid.indices) {
            if (grid[i] < 70.0) assertTrue(s.filters.sumOf { it.responseDb(grid[i]) } <= 0.5, "boost at ${grid[i]} Hz: ${s.filters}")
        }
    }

    @Test
    fun noHighPassWhenDisabledOrNotNeeded() {
        assertNull(design(response()).highPass)
        val r = response(highPass = HighPassSuggestion(70.0, slopeDbPerOctave = 12))
        assertNull(design(r, EqSettings(minHz = 20.0, maxHz = 20_000.0, suggestHighPass = false)).highPass)
    }

    @Test
    fun followsATiltedTarget() {
        val s = design(response(), EqSettings(target = EqTarget.TILT, minHz = 40.0, maxHz = 16_000.0, suggestHighPass = false))
        assertTrue(s.filters.isNotEmpty())
        assertTrue(s.rmsAfterDb < 0.6, "after ${s.rmsAfterDb} (before ${s.rmsBeforeDb})")
    }

    @Test
    fun filtersAreRoundedToDialableValues() {
        val s = design(response(EqFilter(FilterType.PEAK, 173.3, 5.3, 2.37), EqFilter(FilterType.PEAK, 1_234.0, 4.1, 1.7)))
        for (f in s.filters) {
            assertEquals(0.0, (f.gainDb * 2) % 1.0, 1e-9, "gain $f")
            assertEquals(f.frequencyHz, EqDesigner.roundSignificant(f.frequencyHz, 3), 1e-9)
        }
        // Sorted by frequency.
        assertEquals(s.filters.sortedBy { it.frequencyHz }, s.filters)
    }

    @Test
    fun ignoresWhatIsOutsideTheCorrectionRange() {
        val r = response(EqFilter(FilterType.PEAK, 15_000.0, 8.0, 4.0))
        val s = design(r, EqSettings(minHz = 40.0, maxHz = 10_000.0, suggestHighPass = false))
        assertTrue(s.filters.none { it.frequencyHz > 10_000.0 })
    }

    @Test
    fun exportsReadableAndApoText() {
        val s = design(response(EqFilter(FilterType.PEAK, 250.0, 6.0, 3.0)), EqSettings(minHz = 20.0, maxHz = 20_000.0))
        val text = EqExport.toText(s, "Main PA")
        assertTrue(text.startsWith("PA Analyzer – EQ suggestion for “Main PA”"))
        assertTrue(Regex("Filter 1: ON PK Fc \\d+ Hz Gain -\\d+\\.\\d dB Q \\d+\\.\\d+").containsMatchIn(text), text.replace('–', '-'))
        assertEquals(" 1  Peak           250 Hz     -6.0 dB  Q 3.00 (0.48 oct)", EqExport.line(1, EqFilter(FilterType.PEAK, 250.0, -6.0, 3.0)))
    }
}
