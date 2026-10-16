package de.flkaiser.paanalyzer.ui

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.flkaiser.paanalyzer.audio.AudioEngine
import de.flkaiser.paanalyzer.audio.DeviceOption
import de.flkaiser.paanalyzer.audio.DeviceSelection
import de.flkaiser.paanalyzer.data.MeasurementRepository
import de.flkaiser.paanalyzer.data.SavedEntry
import de.flkaiser.paanalyzer.dsp.MeasurementAnalyzer
import de.flkaiser.paanalyzer.dsp.MeasurementDocument
import de.flkaiser.paanalyzer.dsp.MeasurementFileException
import de.flkaiser.paanalyzer.dsp.MeasurementResult
import de.flkaiser.paanalyzer.settings.AppSettings
import de.flkaiser.paanalyzer.settings.SettingsForm
import de.flkaiser.paanalyzer.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

enum class Page { MEASURE, RESULTS, SETTINGS }

sealed interface Phase {
    data object Idle : Phase
    data class Measuring(val progress: Double) : Phase
    data class Analyzing(val progress: Double) : Phase
    data class Failed(val message: String) : Phase
}

/** The measurement shown on the results page; [savedId] is null until it is saved. */
data class CurrentMeasurement(
    val result: MeasurementResult,
    val name: String,
    val createdAtMillis: Long,
    val savedId: String?,
) {
    val curves: List<Curve> by lazy { Curves.build(result) }
}

data class UiState(
    val page: Page = Page.MEASURE,
    val form: SettingsForm,
    val formErrors: List<String> = emptyList(),
    val outputs: List<DeviceOption> = emptyList(),
    val selectedOutput: DeviceOption? = null,
    val inputs: List<DeviceOption> = emptyList(),
    val selectedInput: DeviceOption? = null,
    val micSource: String = "",
    val phase: Phase = Phase.Idle,
    val current: CurrentMeasurement? = null,
    val library: List<SavedEntry> = emptyList(),
    val visibleCurves: Set<String> = Curves.DEFAULT_VISIBLE,
    /** One-off message for a snackbar. */
    val message: String? = null,
) {
    val busy: Boolean get() = phase is Phase.Measuring || phase is Phase.Analyzing
}

class MeasurementViewModel(application: Application) : AndroidViewModel(application), AppActions {

    private val engine = AudioEngine(application)
    private val store = SettingsStore(application)
    private val repository = MeasurementRepository(application)
    private var job: Job? = null

    private val _state = MutableStateFlow(
        UiState(
            form = SettingsForm.from(store.load()),
            micSource = engine.micSourceLabel,
            visibleCurves = store.loadCurves() ?: Curves.DEFAULT_VISIBLE,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val audioManager = application.getSystemService(AudioManager::class.java)
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        refreshDevices()
        refreshLibrary()
    }

    override fun onCleared() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    override fun selectPage(page: Page) = _state.update { it.copy(page = page) }

    // ---- Devices and settings ----

    fun refreshDevices() {
        val outputs = DeviceSelection.visibleOutputs(engine.outputDevices().map { it.toOption() })
        val inputs = DeviceSelection.visibleInputs(engine.inputDevices().map { it.toOption() })
        _state.update {
            it.copy(
                outputs = outputs,
                selectedOutput = DeviceSelection.chooseOutput(outputs, it.selectedOutput?.id),
                inputs = inputs,
                selectedInput = DeviceSelection.chooseInput(inputs, it.selectedInput?.id),
            )
        }
    }

    override fun selectOutput(option: DeviceOption) = _state.update { it.copy(selectedOutput = option) }

    override fun selectInput(option: DeviceOption) = _state.update { it.copy(selectedInput = option) }

    override fun updateForm(form: SettingsForm) {
        val parsed = form.parse()
        _state.update { it.copy(form = form, formErrors = parsed.errors) }
        parsed.settings?.let { store.save(it) }
    }

    override fun resetSettings() = updateForm(SettingsForm.from(AppSettings()))

    // ---- Measuring ----

    override fun start() {
        if (_state.value.busy) return
        val parsed = _state.value.form.parse()
        val appSettings = parsed.settings ?: run {
            _state.update { it.copy(phase = Phase.Failed(parsed.errors.joinToString("\n"))) }
            return
        }
        val output = _state.value.selectedOutput?.let { option -> engine.outputDevices().firstOrNull { it.id == option.id } }
        val input = _state.value.selectedInput?.let { option -> engine.inputDevices().firstOrNull { it.id == option.id } }
        _state.update { it.copy(phase = Phase.Measuring(0.0)) }
        job = viewModelScope.launch {
            try {
                val analyzer = MeasurementAnalyzer(appSettings.sweep)
                val plan = analyzer.plan
                val signal = withContext(Dispatchers.Default) { plan.render() }
                val recording = engine.playAndRecord(
                    signal = signal,
                    sampleRate = appSettings.sweep.sampleRate,
                    recordSamples = plan.recordSamples,
                    channel = appSettings.channel,
                    output = output,
                    input = input,
                ) { p -> _state.update { it.copy(phase = Phase.Measuring(p)) } }
                _state.update { it.copy(phase = Phase.Analyzing(0.0)) }
                val result = withContext(Dispatchers.Default) {
                    analyzer.analyze(recording) { p -> _state.update { it.copy(phase = Phase.Analyzing(p)) } }
                }
                val now = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        phase = Phase.Idle,
                        current = CurrentMeasurement(result, defaultName(now), now, savedId = null),
                        page = Page.RESULTS,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = Phase.Idle) }
                throw e
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(phase = Phase.Failed("Not enough memory; use a shorter sweep or fewer averages.")) }
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.Failed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    override fun cancel() {
        job?.cancel()
    }

    override fun dismissError() = _state.update { if (it.phase is Phase.Failed) it.copy(phase = Phase.Idle) else it }

    // ---- Results ----

    override fun toggleCurve(id: String) {
        val updated = _state.value.visibleCurves.let { if (id in it) it - id else it + id }
        _state.update { it.copy(visibleCurves = updated) }
        store.saveCurves(updated)
    }

    override fun messageShown() = _state.update { it.copy(message = null) }

    /** Saves the current measurement under [name], or renames it if it is already saved. */
    override fun saveCurrent(name: String) {
        val current = _state.value.current ?: return
        val cleanName = name.trim().ifEmpty { current.name }
        launchIo("Could not save the measurement") {
            val entry = if (current.savedId == null) {
                repository.save(current.result, cleanName, current.createdAtMillis)
            } else {
                repository.rename(current.savedId, cleanName)
            }
            _state.update {
                it.copy(current = current.copy(name = entry.name, savedId = entry.id), message = "Saved as “${entry.name}”")
            }
            refreshLibrary()
        }
    }

    override fun loadSaved(id: String) = launchIo("Could not open the measurement") {
        val doc = repository.load(id)
        show(doc, id)
    }

    override fun renameSaved(id: String, name: String) = launchIo("Could not rename the measurement") {
        val entry = repository.rename(id, name.trim().ifEmpty { "Measurement" })
        _state.update { s ->
            val current = s.current?.takeIf { it.savedId == id }?.copy(name = entry.name)
            s.copy(current = current ?: s.current)
        }
        refreshLibrary()
    }

    override fun deleteSaved(id: String) = launchIo("Could not delete the measurement") {
        repository.delete(id)
        // A deleted measurement stays on screen, but is no longer saved.
        _state.update { s -> s.copy(current = s.current?.let { if (it.savedId == id) it.copy(savedId = null) else it }) }
        refreshLibrary()
    }

    override fun importFile(uri: Uri) = launchIo("Could not import the file") {
        val app = getApplication<Application>()
        val text = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        } ?: throw MeasurementFileException("The file could not be read.")
        val fallbackName = withContext(Dispatchers.IO) { displayName(uri) } ?: "Imported measurement"
        val (entry, doc) = repository.import(text, fallbackName)
        show(doc, entry.id)
        _state.update { it.copy(message = "Imported “${entry.name}”") }
        refreshLibrary()
    }

    /** CSV text of the current measurement for sharing. */
    override fun exportCurrent(): Pair<String, String>? {
        val current = _state.value.current ?: return null
        return current.name to repository.export(MeasurementDocument(current.name, current.createdAtMillis, current.result))
    }

    private fun show(doc: MeasurementDocument, id: String) {
        _state.update {
            it.copy(
                current = CurrentMeasurement(doc.result, doc.name.ifBlank { defaultName(doc.createdAtMillis) }, doc.createdAtMillis, id),
                page = Page.RESULTS,
            )
        }
    }

    private fun refreshLibrary() {
        viewModelScope.launch {
            val entries = runCatching { repository.list() }.getOrDefault(emptyList())
            _state.update { it.copy(library = entries) }
        }
    }

    private fun launchIo(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "$failure: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        val app = getApplication<Application>()
        return app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }

    private fun defaultName(millis: Long): String =
        "Measurement " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
}

private fun AudioDeviceInfo.toOption() = DeviceOption(id = id, type = type, name = productName?.toString().orEmpty().ifBlank { "Device $id" })
