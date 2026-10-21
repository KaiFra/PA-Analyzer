package de.flkaiser.paanalyzer.screenshots

import android.Manifest
import android.content.Intent
import de.flkaiser.paanalyzer.live.LiveSource
import android.media.AudioDeviceInfo
import de.flkaiser.paanalyzer.dsp.LiveSettings
import de.flkaiser.paanalyzer.live.LiveFrame
import de.flkaiser.paanalyzer.ui.LiveUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import android.net.Uri
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import de.flkaiser.paanalyzer.audio.DeviceOption
import de.flkaiser.paanalyzer.data.SavedEntry
import de.flkaiser.paanalyzer.dsp.AnalysisSettings
import de.flkaiser.paanalyzer.dsp.EqDesigner
import de.flkaiser.paanalyzer.dsp.EqSettings
import de.flkaiser.paanalyzer.settings.AppSettings
import de.flkaiser.paanalyzer.settings.SettingsForm
import de.flkaiser.paanalyzer.ui.AppActions
import de.flkaiser.paanalyzer.ui.Comparison
import de.flkaiser.paanalyzer.dsp.MeasurementResult
import de.flkaiser.paanalyzer.ui.AppScaffold
import de.flkaiser.paanalyzer.ui.ExportData
import de.flkaiser.paanalyzer.ui.CurrentMeasurement
import de.flkaiser.paanalyzer.ui.Curves
import de.flkaiser.paanalyzer.ui.PaAnalyzerTheme
import de.flkaiser.paanalyzer.ui.Page
import de.flkaiser.paanalyzer.ui.UiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the screens with a simulated measurement into docs/screenshots (used by the README).
 * CI commits the images when they change.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {

    private val output = DeviceOption(30, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "PA receiver")
    private val speaker = DeviceOption(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Phone")
    private val mic = DeviceOption(10, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Phone")

    private fun state(page: Page, curves: Set<String> = Curves.DEFAULT_VISIBLE) = UiState(
        page = page,
        form = SettingsForm.from(AppSettings(DemoMeasurement.settings)),
        outputs = listOf(output, speaker),
        selectedOutput = output,
        inputs = listOf(mic),
        selectedInput = mic,
        micSource = "unprocessed",
        current = CurrentMeasurement(DemoMeasurement.result, "Main PA – left top", CREATED, savedId = "m-1.csv"),
        library = listOf(SavedEntry("m-1.csv", "Main PA – left top", CREATED)),
        visibleCurves = curves,
        eqSuggestion = EqDesigner.design(DemoMeasurement.result.frequencies, DemoMeasurement.result.fundamentalDb, EqSettings()),
    )

    @Test
    @Config(qualifiers = "w400dp-h820dp-xhdpi")
    fun resultsPortrait() = capture("results-portrait", dark = false) { AppScaffold(state(Page.RESULTS), NoActions) }

    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xhdpi")
    fun resultsLandscape() = capture("results-landscape", dark = true) {
        val curves = setOf(Curves.FUNDAMENTAL, Curves.THD, Curves.harmonic(2), Curves.harmonic(3), Curves.RUB_BUZZ_RMS, Curves.RUB_BUZZ_NOISE)
        AppScaffold(state(Page.RESULTS, curves), NoActions)
    }

    @Test
    @Config(qualifiers = "w400dp-h820dp-xhdpi")
    fun compare() = capture("compare", dark = false) {
        // The demo speaker before EQ (open) and after the suggested EQ (compared).
        val r = DemoMeasurement.result
        val eq = EqDesigner.design(r.frequencies, r.fundamentalDb, EqSettings())
        val eqDb = DoubleArray(r.frequencies.size) { i -> Curves.interpolateLog(eq.frequencies, eq.eqDb, r.frequencies[i]).takeIf { it.isFinite() } ?: 0.0 }
        val after = MeasurementResult(
            r.settings, r.frequencies, DoubleArray(r.frequencies.size) { r.fundamentalDb[it] + eqDb[it] },
            r.thdDb, r.harmonics, r.rubBuzz, r.delayMs, r.inputPeakDbfs, r.irSnrDb, r.warnings,
        )
        val s = state(Page.RESULTS).copy(comparisons = listOf(Comparison("m-2.csv", "Main PA – after EQ", after, 0)))
        AppScaffold(s, NoActions)
    }

    @Test
    @Config(qualifiers = "w400dp-h820dp-xhdpi")
    fun eq() = capture("eq", dark = false) { AppScaffold(state(Page.EQ), NoActions) }

    @Test
    @Config(qualifiers = "w400dp-h820dp-xhdpi")
    fun live() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        capture("live", dark = true) {
            AppScaffold(state(Page.LIVE).copy(live = LiveUiState(running = true)), NoActions)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h820dp-xhdpi")
    fun measure() = capture("measure", dark = false) { AppScaffold(state(Page.MEASURE), NoActions) }

    @Test
    @Config(qualifiers = "w400dp-h820dp-xhdpi")
    fun settings() = capture("settings", dark = false) { AppScaffold(state(Page.SETTINGS), NoActions) }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        captureRoboImage(File(dir, "$name.png").path) {
            PaAnalyzerTheme(dark = dark, dynamicColor = false) { content() }
        }
    }

    private object NoActions : AppActions {
        override fun selectPage(page: Page) {}
        override fun selectOutput(option: DeviceOption) {}
        override fun selectInput(option: DeviceOption) {}
        override fun updateForm(form: SettingsForm) {}
        override fun resetSettings() {}
        override fun start() {}
        override fun cancel() {}
        override fun dismissError() {}
        override fun toggleCurve(id: String) {}
        override fun messageShown() {}
        override fun saveCurrent(name: String) {}
        override fun loadSaved(id: String) {}
        override fun renameSaved(id: String, name: String) {}
        override fun deleteSaved(id: String) {}
        override fun averageSaved(ids: List<String>) {}
        override fun compareSaved(ids: List<String>) {}
        override fun removeComparison(id: String) {}
        override fun alignComparisons(align: Boolean) {}
        override fun loadCalibration(uri: Uri) {}
        override fun clearCalibration() {}
        override val liveFrames: StateFlow<LiveFrame?> by lazy { MutableStateFlow(DemoLive.frame) }
        override fun setLiveVisible(visible: Boolean) {}
        override fun pauseLive(paused: Boolean) {}
        override fun resetLivePeaks() {}
        override fun updateLiveSettings(settings: LiveSettings) {}
        override fun selectLiveSource(source: LiveSource) {}
        override fun startPhoneCapture(resultCode: Int, data: Intent) {}
        override fun phoneCaptureDeclined() {}
        override fun importFiles(uris: List<Uri>) {}
        override fun reanalyze(analysis: AnalysisSettings) {}
        override fun updateEqSettings(settings: EqSettings) {}
        override fun exportCurrent(): ExportData? = null
    }

    private companion object {
        const val CREATED = 1_792_152_000_000L
    }
}
