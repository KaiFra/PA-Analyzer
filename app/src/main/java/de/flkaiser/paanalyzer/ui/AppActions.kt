package de.flkaiser.paanalyzer.ui

import android.net.Uri
import de.flkaiser.paanalyzer.audio.DeviceOption
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
    fun importFile(uri: Uri)

    /** Name and CSV text of the current measurement for sharing, or null if none is open. */
    fun exportCurrent(): Pair<String, String>?
}
