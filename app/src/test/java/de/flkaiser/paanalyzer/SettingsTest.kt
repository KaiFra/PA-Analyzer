package de.flkaiser.paanalyzer

import de.flkaiser.paanalyzer.audio.OutputChannel
import de.flkaiser.paanalyzer.dsp.Smoothing
import de.flkaiser.paanalyzer.dsp.SweepSettings
import de.flkaiser.paanalyzer.settings.AppSettings
import de.flkaiser.paanalyzer.settings.SettingsCodec
import de.flkaiser.paanalyzer.settings.SettingsForm
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {

    private val custom = AppSettings(
        sweep = SweepSettings(
            startHz = 30.0,
            endHz = 18_000.0,
            durationSec = 8.0,
            levelDbfs = -12.5,
            sampleRate = 44_100,
            averages = 3,
            preSilenceSec = 1.0,
            postSilenceSec = 2.0,
            irWindowMs = 50.0,
            maxHarmonic = 7,
            rubBuzzStartOrder = 15,
            smoothing = Smoothing.OCT_6,
        ),
        channel = OutputChannel.LEFT,
    )

    @Test
    fun formRoundTrip() {
        val form = SettingsForm.from(custom)
        assertEquals("18000", form.endHz)
        assertEquals("-12.5", form.levelDbfs)
        assertEquals(custom, form.parse().settings)
    }

    @Test
    fun formAcceptsDecimalComma() {
        val parsed = SettingsForm.from(AppSettings()).copy(durationSec = "7,5").parse()
        assertEquals(7.5, parsed.settings?.sweep?.durationSec)
    }

    @Test
    fun formReportsUnparseableAndInvalidValues() {
        val base = SettingsForm.from(AppSettings())
        val notNumbers = base.copy(startHz = "abc", averages = "2.5").parse()
        assertNull(notNumbers.settings)
        assertEquals(2, notNumbers.errors.size)

        val outOfRange = base.copy(levelDbfs = "6").parse()
        assertNull(outOfRange.settings)
        assertTrue(outOfRange.errors.single().contains("Level"))
    }

    @Test
    fun codecRoundTrip() {
        assertEquals(custom, SettingsCodec.decode(SettingsCodec.encode(custom)))
    }

    @Test
    fun codecFallsBackToDefaults() {
        assertEquals(AppSettings(), SettingsCodec.decode(emptyMap()))
        val broken = SettingsCodec.encode(custom) + mapOf("durationSec" to "oops", "smoothing" to "NOPE")
        val decoded = SettingsCodec.decode(broken)
        assertEquals(SweepSettings().durationSec, decoded.sweep.durationSec)
        assertEquals(SweepSettings().smoothing, decoded.sweep.smoothing)
        assertEquals(30.0, decoded.sweep.startHz)
    }

    @Test
    fun codecResetsInvalidCombinations() {
        val invalid = SettingsCodec.encode(custom) + mapOf("endHz" to "40000")
        val decoded = SettingsCodec.decode(invalid)
        assertNotNull(decoded)
        assertEquals(SweepSettings(), decoded.sweep)
        assertEquals(OutputChannel.LEFT, decoded.channel)
    }
}
