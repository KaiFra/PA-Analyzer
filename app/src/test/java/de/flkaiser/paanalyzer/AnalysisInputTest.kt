package de.flkaiser.paanalyzer

import de.flkaiser.paanalyzer.dsp.AnalysisSettings
import de.flkaiser.paanalyzer.dsp.Smoothing
import de.flkaiser.paanalyzer.dsp.SweepSettings
import de.flkaiser.paanalyzer.ui.AnalysisInput
import de.flkaiser.paanalyzer.ui.SMOOTHING_STEPS
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisInputTest {

    private val sweep = SweepSettings()

    @Test
    fun smoothingSliderCoversAllOptionsFromNoneToStrongest() {
        assertEquals(Smoothing.entries.toSet(), SMOOTHING_STEPS.toSet())
        assertEquals(Smoothing.entries.size, SMOOTHING_STEPS.size)
        assertEquals(Smoothing.NONE, SMOOTHING_STEPS.first())
        // Fewer points per octave in the smoothing window = stronger smoothing.
        assertEquals(listOf(24, 12, 6, 3), SMOOTHING_STEPS.drop(1).map { it.fraction })
    }

    @Test
    fun parsesValidInput() {
        val (analysis, errors) = AnalysisInput("12,5", "7", "15", Smoothing.OCT_6).parse(sweep)
        assertEquals(AnalysisSettings(12.5, 7, 15, Smoothing.OCT_6), analysis)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun reportsUnparseableInput() {
        val (analysis, errors) = AnalysisInput("x", "2.5", "", Smoothing.NONE).parse(sweep)
        assertNull(analysis)
        assertEquals(3, errors.size)
    }

    @Test
    fun validatesRanges() {
        val (analysis, errors) = AnalysisInput("300", "12", "10", Smoothing.NONE).parse(sweep)
        assertNull(analysis)
        assertTrue(errors.single().contains("harmonic", ignoreCase = true))
    }
}
