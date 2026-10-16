package de.flkaiser.paanalyzer

import de.flkaiser.paanalyzer.dsp.EqSettings
import de.flkaiser.paanalyzer.dsp.EqTarget
import de.flkaiser.paanalyzer.settings.EqSettingsCodec
import org.junit.Test
import kotlin.test.assertEquals

class EqSettingsCodecTest {

    @Test
    fun roundTrip() {
        val s = EqSettings(EqTarget.LIVE, filterCount = 8, minHz = 60.0, maxHz = 10_000.0, maxBoostDb = 0.0, maxCutDb = 18.0, suggestHighPass = false)
        assertEquals(s, EqSettingsCodec.decode(EqSettingsCodec.encode(s)))
    }

    @Test
    fun fallsBackToDefaults() {
        assertEquals(EqSettings(), EqSettingsCodec.decode(emptyMap()))
        assertEquals(EqSettings(), EqSettingsCodec.decode(mapOf("eq.filterCount" to "99")))
        assertEquals(EqTarget.TILT, EqSettingsCodec.decode(mapOf("eq.target" to "TILT", "eq.minHz" to "abc")).target)
    }

    @Test
    fun keysDoNotClashWithSweepSettings() {
        assertEquals(true, EqSettingsCodec.encode(EqSettings()).keys.all { it.startsWith("eq.") })
    }
}
