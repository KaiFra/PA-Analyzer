package de.flkaiser.paanalyzer

import de.flkaiser.paanalyzer.dsp.EqSettings
import de.flkaiser.paanalyzer.dsp.EqTarget
import de.flkaiser.paanalyzer.dsp.FilterType
import de.flkaiser.paanalyzer.settings.EqSettingsCodec
import org.junit.Test
import kotlin.test.assertEquals

class EqSettingsCodecTest {

    @Test
    fun roundTrip() {
        val s = EqSettings(EqTarget.LIVE, filterCount = 8, minHz = 60.0, maxHz = 10_000.0, maxBoostDb = 7.5, maxCutDb = 20.5, suggestHighPass = false,
            filterTypes = setOf(FilterType.PEAK, FilterType.HIGH_SHELF))
        assertEquals(s, EqSettingsCodec.decode(EqSettingsCodec.encode(s)))
    }

    @Test
    fun fallsBackToDefaults() {
        assertEquals(EqSettings(), EqSettingsCodec.decode(emptyMap()))
        assertEquals(EqSettings(), EqSettingsCodec.decode(mapOf("eq.filterCount" to "99")))
        assertEquals(EqSettings(), EqSettingsCodec.decode(mapOf("eq.filterTypes" to "")))
        assertEquals(setOf(FilterType.PEAK), EqSettingsCodec.decode(mapOf("eq.filterTypes" to "PEAK,UNKNOWN")).filterTypes)
        assertEquals(EqTarget.TILT, EqSettingsCodec.decode(mapOf("eq.target" to "TILT", "eq.minHz" to "abc")).target)
    }

    @Test
    fun keysDoNotClashWithSweepSettings() {
        assertEquals(true, EqSettingsCodec.encode(EqSettings()).keys.all { it.startsWith("eq.") })
    }
}
