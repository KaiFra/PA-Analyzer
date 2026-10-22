package de.flkaiser.paanalyzer.ui

import android.content.Intent
import android.net.Uri
import de.flkaiser.paanalyzer.audio.DeviceOption
import de.flkaiser.paanalyzer.audio.MicSource
import de.flkaiser.paanalyzer.dsp.AnalysisSettings
import de.flkaiser.paanalyzer.dsp.EqSettings
import de.flkaiser.paanalyzer.dsp.LiveSettings
import de.flkaiser.paanalyzer.live.LiveFrame
import de.flkaiser.paanalyzer.live.LiveSource
import kotlinx.coroutines.flow.StateFlow
import de.flkaiser.paanalyzer.settings.SettingsForm

/** Everything the screens can ask for. Implemented by [MeasurementViewModel]; screenshot tests use a no-op. */
interface AppActions {
    fun selectPage(page: Page)
    fun selectOutput(option: DeviceOption)
    fun selectInput(option: DeviceOption)
    fun updateForm(form: SettingsForm)
    fun resetSettings()
    fun start()
    fun cancel()
    fun dismissError()
    fun toggleCurve(id: String)
    fun messageShown()
    fun saveCurrent(name: String)
    fun loadSaved(id: String)
    fun renameSaved(id: String, name: String)
    fun deleteSaved(id: String)

    /** Averages saved measurements from several positions into a new saved measurement and opens it. */
    fun averageSaved(ids: List<String>)

    /** Draws saved measurements on top of the current one (replaces the previous comparison). */
    fun compareSaved(ids: List<String>)
    fun removeComparison(id: String)

    /** Shifts compared measurements to the current one's mid-band level. */
    fun alignComparisons(align: Boolean)
    /** Imports a measurement CSV, optionally together with its WAV recording. */
    fun importFiles(uris: List<Uri>)

    /** Evaluates the current measurement's recording again with other analysis settings. */
    fun reanalyze(analysis: AnalysisSettings)

    /** Loads a microphone calibration file; it is applied to new measurements and re-analyses. */
    fun loadCalibration(uri: Uri)
    fun clearCalibration()

    /** Changes the EQ suggestion settings; the suggestion is recomputed automatically. */
    fun updateEqSettings(settings: EqSettings)

    /** The current measurement as files for sharing, or null if none is open. */
    fun exportCurrent(): ExportData?

    // ---- Live analyzer ----

    /** Spectra of the live analyzer; a flow of its own, so 20 updates per second don't redraw the whole app. */
    val liveFrames: StateFlow<LiveFrame?>

    /** The live page is on screen and the app in the foreground: the analyzer runs while this is true. */
    fun setLiveVisible(visible: Boolean)
    fun pauseLive(paused: Boolean)
    fun resetLivePeaks()
    fun updateLiveSettings(settings: LiveSettings)

    /** Android microphone source (processing) for the live analyzer. */
    fun selectMicSource(source: MicSource)

    /** Switches to the microphone (phone audio starts with [startPhoneCapture] after the system dialog). */
    fun selectLiveSource(source: LiveSource)

    /** The user allowed capturing the phone's audio in the system dialog. */
    fun startPhoneCapture(resultCode: Int, data: Intent)
    fun phoneCaptureDeclined()
}

/** A measurement ready for sharing: the CSV and, if available, the recording as WAV. */
class ExportData(val name: String, val csv: String, val wav: ByteArray?)
