package de.flkaiser.paanalyzer.ui

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.flkaiser.paanalyzer.audio.AudioEngine
import de.flkaiser.paanalyzer.audio.DeviceOption
import de.flkaiser.paanalyzer.audio.DeviceSelection
import de.flkaiser.paanalyzer.dsp.MeasurementAnalyzer
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

sealed interface Phase {
    data object Idle : Phase
    data class Measuring(val progress: Double) : Phase
    data class Analyzing(val progress: Double) : Phase
    data class Failed(val message: String) : Phase
}

data class UiState(
    val form: SettingsForm,
    val formErrors: List<String> = emptyList(),
    val outputs: List<DeviceOption> = emptyList(),
    val selectedOutput: DeviceOption? = null,
    val inputs: List<DeviceOption> = emptyList(),
    val selectedInput: DeviceOption? = null,
    val micSource: String = "",
    val phase: Phase = Phase.Idle,
    val result: MeasurementResult? = null,
) {
    val busy: Boolean get() = phase is Phase.Measuring || phase is Phase.Analyzing
}

class MeasurementViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = AudioEngine(application)
    private val store = SettingsStore(application)
    private var settings: AppSettings = store.load()
    private var job: Job? = null

    private val _state = MutableStateFlow(UiState(form = SettingsForm.from(settings), micSource = engine.micSourceLabel))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val audioManager = application.getSystemService(AudioManager::class.java)
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        refreshDevices()
    }

    override fun onCleared() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

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

    fun selectOutput(option: DeviceOption) = _state.update { it.copy(selectedOutput = option) }

    fun selectInput(option: DeviceOption) = _state.update { it.copy(selectedInput = option) }

    fun updateForm(form: SettingsForm) {
        val parsed = form.parse()
        _state.update { it.copy(form = form, formErrors = parsed.errors) }
        parsed.settings?.let {
            settings = it
            store.save(it)
        }
    }

    fun resetSettings() = updateForm(SettingsForm.from(AppSettings()))

    fun start() {
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
                _state.update { it.copy(phase = Phase.Idle, result = result) }
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

    fun cancel() {
        job?.cancel()
    }

    fun dismissError() = _state.update { if (it.phase is Phase.Failed) it.copy(phase = Phase.Idle) else it }
}

private fun AudioDeviceInfo.toOption() = DeviceOption(id = id, type = type, name = productName?.toString().orEmpty().ifBlank { "Device $id" })
