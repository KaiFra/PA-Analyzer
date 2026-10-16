package de.flkaiser.paanalyzer.ui

import android.net.Uri
import de.flkaiser.paanalyzer.audio.DeviceOption
import de.flkaiser.paanalyzer.dsp.AnalysisSettings
import de.flkaiser.paanalyzer.dsp.EqSettings
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

    /** Changes the EQ suggestion settings; the suggestion is recomputed automatically. */
    fun updateEqSettings(settings: EqSettings)

    /** The current measurement as files for sharing, or null if none is open. */
    fun exportCurrent(): ExportData?
}

/** A measurement ready for sharing: the CSV and, if available, the recording as WAV. */
class ExportData(val name: String, val csv: String, val wav: ByteArray?)
