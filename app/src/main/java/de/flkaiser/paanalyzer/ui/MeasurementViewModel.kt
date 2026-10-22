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
import de.flkaiser.paanalyzer.audio.MicSource
import de.flkaiser.paanalyzer.audio.DeviceOption
import de.flkaiser.paanalyzer.audio.DeviceSelection
import de.flkaiser.paanalyzer.data.CalibrationStore
import de.flkaiser.paanalyzer.data.MeasurementRepository
import de.flkaiser.paanalyzer.data.SavedEntry
import de.flkaiser.paanalyzer.dsp.AnalysisSettings
import de.flkaiser.paanalyzer.dsp.EqDesigner
import de.flkaiser.paanalyzer.dsp.EqSettings
import de.flkaiser.paanalyzer.dsp.EqSuggestion
import de.flkaiser.paanalyzer.dsp.MeasurementAnalyzer
import de.flkaiser.paanalyzer.dsp.MeasurementDocument
import de.flkaiser.paanalyzer.dsp.MeasurementFileException
import de.flkaiser.paanalyzer.dsp.MeasurementResult
import de.flkaiser.paanalyzer.dsp.MicCalibration
import de.flkaiser.paanalyzer.dsp.PositionAverage
import de.flkaiser.paanalyzer.dsp.LiveSettings
import de.flkaiser.paanalyzer.dsp.LiveSpectrumAnalyzer
import de.flkaiser.paanalyzer.audio.AudioException
import de.flkaiser.paanalyzer.live.LiveFrame
import de.flkaiser.paanalyzer.live.LiveSource
import de.flkaiser.paanalyzer.live.PlaybackCapture
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import de.flkaiser.paanalyzer.live.WaterfallHistory
import java.util.concurrent.atomic.AtomicBoolean
import de.flkaiser.paanalyzer.dsp.PreparedMeasurement
import de.flkaiser.paanalyzer.dsp.WavFile
import de.flkaiser.paanalyzer.settings.AppSettings
import de.flkaiser.paanalyzer.settings.SettingsForm
import de.flkaiser.paanalyzer.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

enum class Page { MEASURE, RESULTS, EQ, LIVE, SETTINGS }

sealed interface Phase {
    data object Idle : Phase
    data class Measuring(val progress: Double) : Phase
    data class Analyzing(val progress: Double) : Phase
    data class Failed(val message: String) : Phase
}

/**
 * The measurement shown on the results page; [savedId] is null until it is saved.
 * [recording] is the raw microphone signal, needed to change analysis settings later
 * (null for measurements saved by older versions or imported without their WAV file).
 */
data class CurrentMeasurement(
    val result: MeasurementResult,
    val name: String,
    val createdAtMillis: Long,
    val savedId: String?,
    val recording: FloatArray? = null,
) {
    val curves: List<Curve> by lazy { Curves.build(result) }
}

/** State of the live analyzer page (the spectra themselves come through [AppActions.liveFrames]). */
data class LiveUiState(
    val settings: LiveSettings = LiveSettings(),
    val source: LiveSource = LiveSource.MICROPHONE,
    /** Android microphone source (processing) for the live analyzer. */
    val micSource: MicSource = MicSource.AUTO,
    /** Sources the phone offers. */
    val micSources: List<MicSource> = MicSource.entries,
    val paused: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
    /** The phone-audio capture is allowed and running (the system asks again for every new capture). */
    val phoneCaptureActive: Boolean = false,
)

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
    /** Saved measurements drawn on top of the current one. */
    val comparisons: List<Comparison> = emptyList(),
    val alignComparisons: Boolean = false,
    /** Microphone calibration applied to new measurements, null if none is loaded. */
    val calibration: MicCalibration? = null,
    val live: LiveUiState = LiveUiState(),
    val eqSettings: EqSettings = EqSettings(),
    /** EQ suggestion for the current measurement, recomputed when it or [eqSettings] change. */
    val eqSuggestion: EqSuggestion? = null,
    val eqError: String? = null,
    /** True while the current measurement is being evaluated again with new analysis settings. */
    val reanalyzing: Boolean = false,
    /** One-off message for a snackbar. */
    val message: String? = null,
) {
    val busy: Boolean get() = phase is Phase.Measuring || phase is Phase.Analyzing
}

class MeasurementViewModel(application: Application) : AndroidViewModel(application), AppActions {

    private val engine = AudioEngine(application)
    private val store = SettingsStore(application)
    private val repository = MeasurementRepository(application)
    private val calibrationStore = CalibrationStore(application)
    private var job: Job? = null

    /** Deconvolved form of the current recording, so analysis changes don't redo the slow part. */
    private var prepared: PreparedMeasurement? = null

    private val _state = MutableStateFlow(
        UiState(
            form = SettingsForm.from(store.load()),
            micSource = engine.micSourceLabel,
            visibleCurves = store.loadCurves() ?: Curves.DEFAULT_VISIBLE,
            eqSettings = store.loadEq(),
            calibration = calibrationStore.load(),
            live = LiveUiState(
                settings = store.loadLiveSettings(),
                source = store.loadLiveSource(),
                micSource = store.loadLiveMicSource().takeIf { it in engine.micSources } ?: MicSource.AUTO,
                micSources = engine.micSources,
            ),
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
        // Keep the EQ suggestion in step with the current measurement and the EQ settings.
        viewModelScope.launch {
            _state.map { it.current?.result to it.eqSettings }
                .distinctUntilChanged { a, b -> a.first === b.first && a.second == b.second }
                .collectLatest { (result, settings) ->
                    if (result == null) {
                        _state.update { it.copy(eqSuggestion = null, eqError = null) }
                        return@collectLatest
                    }
                    val outcome = withContext(Dispatchers.Default) {
                        runCatching { EqDesigner.design(result.frequencies, result.fundamentalDb, settings) }
                    }
                    _state.update {
                        it.copy(eqSuggestion = outcome.getOrNull(), eqError = outcome.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName })
                    }
                }
        }
    }

    override fun updateEqSettings(settings: EqSettings) {
        if (settings.validate().isNotEmpty()) return
        _state.update { it.copy(eqSettings = settings) }
        store.saveEq(settings)
    }

    override fun onCleared() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopPhoneCapture()
    }

    override fun selectPage(page: Page) {
        _state.update { it.copy(page = page) }
        if (page != Page.LIVE) {
            stopLive()
            // Don't keep capturing the phone's audio (and showing the capture notice) away from the live page.
            stopPhoneCapture()
        }
    }

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
        stopLive()
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
                val (preparedMeasurement, result) = withContext(Dispatchers.Default) {
                    val p = analyzer.prepare(recording) { f -> _state.update { it.copy(phase = Phase.Analyzing(0.85 * f)) } }
                    p to calibrated(p.evaluate { f -> _state.update { it.copy(phase = Phase.Analyzing(0.85 + 0.15 * f)) } })
                }
                prepared = preparedMeasurement
                val now = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        phase = Phase.Idle,
                        current = CurrentMeasurement(result, defaultName(now), now, savedId = null, recording = recording),
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
                repository.save(current.result, cleanName, current.createdAtMillis, current.recording)
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
        val recording = runCatching { repository.loadRecording(id) }.getOrNull()
        show(doc, id, recording)
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
        _state.update { s ->
            s.copy(
                current = s.current?.let { if (it.savedId == id) it.copy(savedId = null) else it },
                comparisons = s.comparisons.filter { it.id != id },
            )
        }
        refreshLibrary()
    }

    override fun averageSaved(ids: List<String>) = launchIo("Could not average the measurements") {
        if (ids.size < PositionAverage.MIN_POSITIONS) return@launchIo
        val docs = withContext(Dispatchers.IO) { ids.map { repository.load(it) } }
        val result = withContext(Dispatchers.Default) { PositionAverage.average(docs.map { it.result }) }
        val now = System.currentTimeMillis()
        val name = "Average of ${docs.size} positions"
        val entry = repository.save(result, name, now, null)
        show(MeasurementDocument(entry.name, now, result), entry.id, null)
        _state.update {
            it.copy(message = "Averaged ${docs.size} positions (levels aligned at 200 Hz – 2 kHz): " + docs.joinToString(", ") { d -> d.name })
        }
        refreshLibrary()
    }

    override fun compareSaved(ids: List<String>) = launchIo("Could not open the measurements to compare") {
        if (ids.isEmpty()) return@launchIo
        // Without an open measurement, the first ticked one is opened and the others are compared with it.
        if (_state.value.current == null) {
            val first = ids.first()
            val doc = withContext(Dispatchers.IO) { repository.load(first) }
            val recording = runCatching { repository.loadRecording(first) }.getOrNull()
            show(doc, first, recording)
        }
        val currentId = _state.value.current?.savedId
        val chosen = ids.filter { it != currentId }.take(Compare.MAX)
        val docs = withContext(Dispatchers.IO) { chosen.map { it to repository.load(it) } }
        val comparisons = docs.mapIndexed { i, (id, doc) -> Comparison(id, doc.name.ifBlank { defaultName(doc.createdAtMillis) }, doc.result, i) }
        _state.update { it.copy(comparisons = comparisons, page = Page.RESULTS) }
        if (ids.size > chosen.size + (if (currentId in ids) 1 else 0)) {
            _state.update { it.copy(message = "At most ${Compare.MAX} measurements can be compared at once.") }
        }
    }

    override fun removeComparison(id: String) = _state.update { s -> s.copy(comparisons = s.comparisons.filter { it.id != id }) }

    override fun alignComparisons(align: Boolean) = _state.update { it.copy(alignComparisons = align) }

    override fun importFiles(uris: List<Uri>) = launchIo("Could not import the file") {
        val app = getApplication<Application>()
        val files = withContext(Dispatchers.IO) {
            uris.map { uri ->
                val name = displayName(uri)
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw MeasurementFileException("The file could not be read.")
                (name ?: "") to bytes
            }
        }
        fun isWav(bytes: ByteArray) = bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF"
        val csv = files.firstOrNull { !isWav(it.second) }
            ?: throw MeasurementFileException("Choose the measurement's CSV file (optionally together with its WAV file).")
        val wav = files.firstOrNull { isWav(it.second) }?.second
        val fallbackName = csv.first.substringBeforeLast('.').ifBlank { "Imported measurement" }
        val (entry, doc) = repository.import(csv.second.toString(Charsets.UTF_8), fallbackName, wav)
        show(doc, entry.id, wav?.let { WavFile.read(it).samples })
        _state.update { it.copy(message = "Imported “${entry.name}”" + if (wav == null) " (without recording)" else "") }
        refreshLibrary()
    }

    override fun reanalyze(analysis: AnalysisSettings) {
        val current = _state.value.current ?: return
        val recording = current.recording
        if (recording == null) {
            _state.update { it.copy(message = "This measurement has no stored recording, so it can't be analysed again.") }
            return
        }
        if (_state.value.reanalyzing) return
        _state.update { it.copy(reanalyzing = true) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val p = prepared?.takeIf { it.recording === recording }
                        ?: MeasurementAnalyzer(current.result.settings).prepare(recording).also { prepared = it }
                    calibrated(p.evaluate(analysis))
                }
                val updated = current.copy(result = result)
                _state.update { s -> s.copy(current = if (s.current?.recording === recording) updated else s.current, reanalyzing = false) }
                // A saved measurement keeps the new analysis; its recording is unchanged.
                current.savedId?.let { repository.update(it, MeasurementDocument(updated.name, updated.createdAtMillis, result)) }
            } catch (e: CancellationException) {
                _state.update { it.copy(reanalyzing = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(reanalyzing = false, message = "Could not analyse again: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    override fun loadCalibration(uri: Uri) = launchIo("Could not load the calibration file") {
        val app = getApplication<Application>()
        val name = displayNameWithExtension(uri) ?: "Calibration"
        val calibration = withContext(Dispatchers.IO) {
            val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw MeasurementFileException("The file could not be read.")
            MicCalibration.parse(text, name).also { calibrationStore.save(it) }
        }
        _state.update {
            it.copy(
                calibration = calibration,
                message = "Loaded “${calibration.name}” (${calibration.size} points). It applies to new measurements and to “analyse again”.",
            )
        }
    }

    override fun clearCalibration() {
        calibrationStore.clear()
        _state.update { it.copy(calibration = null, message = "Microphone calibration removed.") }
    }

    /** Applies the loaded microphone calibration to a freshly evaluated recording. */
    private fun calibrated(result: MeasurementResult): MeasurementResult = _state.value.calibration?.apply(result) ?: result

    override fun exportCurrent(): ExportData? {
        val current = _state.value.current ?: return null
        val csv = repository.export(MeasurementDocument(current.name, current.createdAtMillis, current.result))
        val wav = current.recording?.let { WavFile.write(it, current.result.settings.sampleRate) }
        return ExportData(current.name, csv, wav)
    }

    // ---- Live analyzer ----

    private val _liveFrames = MutableStateFlow<LiveFrame?>(null)
    override val liveFrames: StateFlow<LiveFrame?> = _liveFrames.asStateFlow()
    private var liveVisible = false
    private var liveJob: Job? = null
    private var liveStop: Job? = null
    private var waterfall: WaterfallHistory? = null
    private val resetPeaksRequested = AtomicBoolean(false)

    override fun setLiveVisible(visible: Boolean) {
        liveVisible = visible
        liveStop?.cancel()
        if (visible) {
            startLive()
        } else {
            // Turning the phone recreates the screen; the short delay keeps the input running through that.
            liveStop = viewModelScope.launch {
                delay(LIVE_STOP_DELAY_MS)
                stopLive()
            }
        }
    }

    override fun pauseLive(paused: Boolean) {
        _state.update { it.copy(live = it.live.copy(paused = paused)) }
        if (paused) stopLive() else startLive()
    }

    override fun resetLivePeaks() = resetPeaksRequested.set(true)

    override fun updateLiveSettings(settings: LiveSettings) {
        if (settings.validate().isNotEmpty()) return
        val old = _state.value.live.settings
        _state.update { it.copy(live = it.live.copy(settings = settings)) }
        store.saveLive(settings, _state.value.live.source, _state.value.live.micSource)
        // Averaging and peak hold apply on the fly; everything else needs a new analyzer.
        if (old.copy(averaging = settings.averaging, peakHold = settings.peakHold) != settings) restartLive()
    }

    private fun restartLive() {
        val job = liveJob
        liveJob = null
        viewModelScope.launch {
            job?.cancelAndJoin()
            startLive()
        }
    }

    /** True while this app ends the capture itself (leaving the page, switching to the microphone). */
    private var stoppingCapture = false

    @Suppress("unused") // Runs for the ViewModel's lifetime; declared after the state it uses.
    private val captureFollower = viewModelScope.launch { followPlaybackCapture() }

    override fun selectMicSource(source: MicSource) {
        if (source == _state.value.live.micSource) return
        _state.update { it.copy(live = it.live.copy(micSource = source)) }
        val live = _state.value.live
        store.saveLive(live.settings, live.source, source)
        if (live.source == LiveSource.MICROPHONE) restartLive()
    }

    override fun selectLiveSource(source: LiveSource) {
        if (source == LiveSource.PHONE || _state.value.live.source == source) return
        setLiveSource(source)
        stopPhoneCapture()
        restartLive()
    }

    override fun startPhoneCapture(resultCode: Int, data: Intent) {
        stopLive()
        setLiveSource(LiveSource.PHONE)
        PlaybackCapture.start(getApplication(), resultCode, data)
        // The analyzer starts when the service hands over the capture (see init).
    }

    override fun phoneCaptureDeclined() =
        _state.update { it.copy(message = "Phone audio needs the permission to capture what the phone plays.") }

    private fun setLiveSource(source: LiveSource) {
        _state.update { it.copy(live = it.live.copy(source = source, error = null)) }
        store.saveLive(_state.value.live.settings, source, _state.value.live.micSource)
    }

    private fun stopPhoneCapture() {
        if (PlaybackCapture.projection.value == null) return
        stoppingCapture = true
        PlaybackCapture.stop(getApplication())
    }

    /** Follows the phone-audio capture: start analysing when it is granted, stop when it ends. */
    private suspend fun followPlaybackCapture() {
        var previous: MediaProjection? = null
        PlaybackCapture.projection.collect { projection ->
            val intended = stoppingCapture
            if (projection == null) stoppingCapture = false
            val ended = previous != null && projection == null
            previous = projection
            _state.update { it.copy(live = it.live.copy(phoneCaptureActive = projection != null)) }
            if (_state.value.live.source != LiveSource.PHONE) return@collect
            if (projection != null) {
                restartLive()
            } else {
                stopLive()
                if (ended && !intended) _state.update { it.copy(message = "Phone audio capture ended.") }
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    private fun startLive() {
        val s = _state.value
        if (!liveVisible || s.page != Page.LIVE || s.live.paused || s.busy || liveJob?.isActive == true) return
        val live = s.live
        val projection = PlaybackCapture.projection.value
        // Phone audio waits for the user to allow the capture (the page shows a button for it).
        if (live.source == LiveSource.PHONE && projection == null) return
        val calibration = if (live.source == LiveSource.MICROPHONE) s.calibration else null
        val input = s.selectedInput?.let { option -> engine.inputDevices().firstOrNull { it.id == option.id } }
        val history = waterfall?.takeIf { it.fits(live.settings) } ?: WaterfallHistory.create(live.settings).also { waterfall = it }
        liveJob = viewModelScope.launch {
            _state.update { it.copy(live = it.live.copy(running = true, error = null)) }
            try {
                withContext(Dispatchers.IO) { runLive(live.settings, live.source, input, projection, calibration, history) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(live = it.live.copy(error = e.message ?: e.javaClass.simpleName)) }
            } finally {
                _state.update { it.copy(live = it.live.copy(running = false)) }
            }
        }
    }

    private suspend fun runLive(
        settings: LiveSettings,
        source: LiveSource,
        input: AudioDeviceInfo?,
        projection: MediaProjection?,
        calibration: MicCalibration?,
        history: WaterfallHistory,
    ) {
        val analyzer = LiveSpectrumAnalyzer(settings, calibration)
        val audio = when (source) {
            LiveSource.MICROPHONE -> engine.openLiveMic(settings.sampleRate, input, _state.value.live.micSource)
            LiveSource.PHONE -> if (projection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                engine.openPlaybackCapture(projection, settings.sampleRate)
            } else {
                throw AudioException("Capturing phone audio needs Android 10 or newer.")
            }
        }
        audio.use {
            val buffer = FloatArray(settings.sampleRate / 100)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = it.read(buffer)
                val tuning = _state.value.live.settings
                analyzer.averaging = tuning.averaging
                analyzer.peakHold = tuning.peakHold
                if (resetPeaksRequested.getAndSet(false)) analyzer.resetPeaks()
                val spectrum = analyzer.process(buffer, 0, n) ?: continue
                history.add(spectrum)
                _liveFrames.value = LiveFrame(spectrum, history, settings, source, calibration?.name)
            }
        }
    }

    private fun show(doc: MeasurementDocument, id: String, recording: FloatArray?) {
        prepared = null
        _state.update {
            it.copy(
                current = CurrentMeasurement(doc.result, doc.name.ifBlank { defaultName(doc.createdAtMillis) }, doc.createdAtMillis, id, recording),
                page = Page.RESULTS,
                // The opened measurement is no longer drawn as a comparison to itself.
                comparisons = it.comparisons.filter { c -> c.id != id },
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

    private fun displayNameWithExtension(uri: Uri): String? {
        val app = getApplication<Application>()
        return app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun defaultName(millis: Long): String =
        "Measurement " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
}

private const val LIVE_STOP_DELAY_MS = 600L

private fun AudioDeviceInfo.toOption() = DeviceOption(id = id, type = type, name = productName?.toString().orEmpty().ifBlank { "Device $id" })
