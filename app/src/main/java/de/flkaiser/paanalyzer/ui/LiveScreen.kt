@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.flkaiser.paanalyzer.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.flkaiser.paanalyzer.dsp.LiveSettings
import de.flkaiser.paanalyzer.dsp.MusicalNote
import de.flkaiser.paanalyzer.dsp.PeakHold
import de.flkaiser.paanalyzer.dsp.WindowFunction
import de.flkaiser.paanalyzer.live.ColorMap
import de.flkaiser.paanalyzer.live.LiveFrame
import de.flkaiser.paanalyzer.live.LiveSource
import de.flkaiser.paanalyzer.live.PlaybackCapture
import de.flkaiser.paanalyzer.live.WaterfallHistory
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val LIVE_COLOR = Color(0xFF4FC3F7)
private val HOLD_COLOR = Color(0xFFFF8A65)
private val PEAK_COLOR = Color(0xFFFFD54F)

/** Level range shown until the user zooms (and after a double tap). */
internal val LIVE_Y = -120.0..0.0

/** Digits of equal width, so readouts that change 20 times a second don't jitter. */
private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun LiveScreen(state: UiState, viewModel: AppActions, landscape: Boolean) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMicPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        PermissionPrompt { permission.launch(Manifest.permission.RECORD_AUDIO) }
        return
    }
    LifecycleStartEffect(Unit) {
        viewModel.setLiveVisible(true)
        onStopOrDispose { viewModel.setLiveVisible(false) }
    }
    val frame = viewModel.liveFrames.collectAsStateWithLifecycle()
    val live = state.live
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) viewModel.startPhoneCapture(result.resultCode, data) else viewModel.phoneCaptureDeclined()
    }
    val askForPhoneAudio = {
        runCatching { capture.launch(PlaybackCapture.consentIntent(context)) }
            .onFailure { viewModel.phoneCaptureDeclined() }
        Unit
    }
    val chart = remember(live.settings.sampleRate) {
        ChartState(WaterfallHistory.MIN_HZ..live.settings.sampleRate / 2.0, defaultY = LIVE_Y)
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (landscape) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
                ChartWithPrompt(state, frame, chart, askForPhoneAudio, Modifier.fillMaxWidth().weight(0.6f))
                ColorBar(chart.yRange ?: LIVE_Y)
                WaterfallView(frame, chart, Modifier.fillMaxWidth().weight(0.4f))
            }
            Column(
                Modifier.width(232.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LiveHeader(state, frame, viewModel, compact = true, onSettings = { showSettings = true }, onPhoneAudio = askForPhoneAudio)
                LiveReadout(frame, chart, vertical = true)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            LiveHeader(state, frame, viewModel, compact = false, onSettings = { showSettings = true }, onPhoneAudio = askForPhoneAudio)
            ChartWithPrompt(state, frame, chart, askForPhoneAudio, Modifier.fillMaxWidth().weight(0.56f))
            LiveReadout(frame, chart, vertical = false)
            ColorBar(chart.yRange ?: LIVE_Y)
            WaterfallView(frame, chart, Modifier.fillMaxWidth().weight(0.44f).padding(bottom = 4.dp))
        }
    }

    if (showSettings) {
        LiveSettingsDialog(
            initial = live.settings,
            onApply = {
                viewModel.updateLiveSettings(it)
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }
}

private fun hasMicPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionPrompt(onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Live spectrum", style = MaterialTheme.typography.titleMedium)
        Text(
            "The live analyzer shows what the microphone hears, as a spectrum and a waterfall. It needs the microphone permission.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAllow) { Text("Allow microphone") }
    }
}

/** The chart, with a button on top when phone audio is chosen but not yet allowed. */
@Composable
private fun ChartWithPrompt(state: UiState, frame: State<LiveFrame?>, chart: ChartState, onPhoneAudio: () -> Unit, modifier: Modifier) {
    Box(modifier) {
        SpectrumChart(frame, chart, Modifier.fillMaxSize())
        if (state.live.source == LiveSource.PHONE && !state.live.phoneCaptureActive) {
            Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Phone audio", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Shows the spectrum of what this phone plays, e.g. music from another app. Android asks for " +
                            "permission each time (it calls it recording or casting the screen; only the audio is used). " +
                            "Apps that block capture stay silent.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onPhoneAudio) { Text("Start capture") }
                }
            }
        }
    }
}

@Composable
private fun LiveHeader(
    state: UiState,
    frame: State<LiveFrame?>,
    viewModel: AppActions,
    compact: Boolean,
    onSettings: () -> Unit,
    onPhoneAudio: () -> Unit,
) {
    val live = state.live
    val s = live.settings
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (compact) "Live" else "Live spectrum",
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    statusText(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (compact) 3 else 2,
                )
            }
            IconButton(onClick = { viewModel.pauseLive(!live.paused) }) {
                if (live.paused) Icon(Icons.Filled.PlayArrow, contentDescription = "Resume") else Icon(PauseIcon, contentDescription = "Pause")
            }
            IconButton(onClick = viewModel::resetLivePeaks, enabled = s.peakHold != PeakHold.OFF) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reset peak hold")
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Build, contentDescription = "Analyzer settings") }
        }
        if (PlaybackCapture.supported) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                LiveSource.entries.forEachIndexed { i, source ->
                    SegmentedButton(
                        selected = live.source == source,
                        onClick = {
                            when {
                                source == LiveSource.MICROPHONE -> viewModel.selectLiveSource(source)
                                live.source != LiveSource.PHONE || !live.phoneCaptureActive -> onPhoneAudio()
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, LiveSource.entries.size),
                        label = { Text(source.label, maxLines = 1) },
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            FilterChip(
                selected = s.peakHold != PeakHold.OFF,
                onClick = {
                    val next = PeakHold.entries[(s.peakHold.ordinal + 1) % PeakHold.entries.size]
                    viewModel.updateLiveSettings(s.copy(peakHold = next))
                },
                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(HOLD_COLOR)) },
                label = { Text("Peak hold: ${s.peakHold.label}") },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            InputLevel(frame, Modifier.align(Alignment.CenterVertically))
        }
        when {
            live.error != null -> Text(live.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            state.busy -> Text("A measurement is running.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            live.paused -> Text("Paused – tap ▶ to continue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun statusText(state: UiState): String {
    val s = state.live.settings
    val parts = mutableListOf(
        "${formatKhz(s.sampleRate)} kHz",
        "FFT ${s.fftSize}",
        "${formatNumber(s.finestBinWidthHz)} Hz/bin",
    )
    if (!PlaybackCapture.supported) parts.add(0, state.live.source.label)
    if (state.live.source == LiveSource.MICROPHONE && state.calibration != null) parts += "calibrated"
    return parts.joinToString(" · ")
}

@Composable
private fun InputLevel(frame: State<LiveFrame?>, modifier: Modifier = Modifier) {
    val peak = frame.value?.spectrum?.inputPeakDbfs ?: return
    val clipping = peak > -0.5
    Text(
        if (clipping) "Input clipping!" else String.format(Locale.US, "Input %.0f dBFS", peak),
        style = MaterialTheme.typography.labelMedium.merge(TABULAR),
        color = if (clipping) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (clipping) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier,
    )
}

@Composable
private fun SpectrumChart(frame: State<LiveFrame?>, chart: ChartState, modifier: Modifier) {
    val spectrum = frame.value?.spectrum
    val series = buildList {
        if (spectrum != null) {
            spectrum.peakHoldDb?.let { add(PlotSeries(spectrum.frequencies, it, HOLD_COLOR, width = 1.2f, reduce = PlotReduce.MAX)) }
            add(PlotSeries(spectrum.frequencies, spectrum.levelDb, LIVE_COLOR, width = 1.6f, fill = true, reduce = PlotReduce.MAX))
        }
    }
    val markers = spectrum?.peak?.let { p ->
        listOf(PlotMarker(p.frequencyHz, p.levelDb, "${formatLiveHz(p.frequencyHz)} · ${formatDb(p.levelDb)}", PEAK_COLOR))
    } ?: emptyList()
    FrequencyPlot(
        series = series,
        xRange = chart.xRange,
        yRange = chart.yRange ?: LIVE_Y,
        cursorHz = chart.cursorHz,
        gestures = chart.gestures { LIVE_Y },
        modifier = modifier,
        markers = markers,
    )
}

@Composable
private fun LiveReadout(frame: State<LiveFrame?>, chart: ChartState, vertical: Boolean) {
    val spectrum = frame.value?.spectrum
    val cursorColor = MaterialTheme.colorScheme.primary
    val peak = spectrum?.peak
    val items = buildList {
        add(
            Triple(
                PEAK_COLOR,
                "Peak",
                peak?.let { p -> listOfNotNull(formatLiveHz(p.frequencyHz), MusicalNote.of(p.frequencyHz)?.toString(), formatDb(p.levelDb)).joinToString(" · ") } ?: "–",
            ),
        )
        val cursor = chart.cursorHz
        if (cursor != null && spectrum != null) {
            val hold = spectrum.peakHoldDb?.let { " (hold ${formatDb(spectrum.levelAt(cursor, it))})" } ?: ""
            add(Triple(cursorColor, "Cursor", "${formatLiveHz(cursor)} · ${formatDb(spectrum.levelAt(cursor))}$hold"))
        }
    }
    val content: @Composable () -> Unit = {
        for ((color, label, value) in items) ReadoutItem(color, label, value)
    }
    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    } else {
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun ReadoutItem(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            "  $label  ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.labelMedium.merge(TABULAR))
    }
}

/** Legend of the waterfall colours: the chart's level range from quiet to loud. */
@Composable
private fun ColorBar(range: ClosedFloatingPointRange<Double>) {
    val style = MaterialTheme.typography.labelSmall
    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatNumber(range.start),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(PLOT_LEFT_MARGIN).padding(end = 4.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(ColorMap.STOPS.map { Color(it) })),
        )
        Text("${formatNumber(range.endInclusive)} dB", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Bitmap the waterfall is coloured into; reallocated only when the history's size changes. */
private class WaterfallBuffers {
    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private var pixels = IntArray(0)

    fun render(history: WaterfallHistory, range: ClosedFloatingPointRange<Double>): ImageBitmap {
        val cols = history.columns.count
        val rows = history.rows
        var b = bitmap
        if (b == null || b.width != cols || b.height != rows) {
            b = createBitmap(cols, rows)
            bitmap = b
            image = b.asImageBitmap()
            pixels = IntArray(cols * rows)
        }
        history.render(pixels, ColorMap.LUT, range.start, range.endInclusive, ColorMap.LUT[0])
        b.setPixels(pixels, 0, cols, 0, 0, cols, rows)
        return image!!
    }
}

/**
 * The spectra of the last seconds, newest at the top, coloured by level with the chart's level
 * range. Shares the frequency axis (zoom, pan, cursor) with the chart above.
 */
@Composable
private fun WaterfallView(frame: State<LiveFrame?>, chart: ChartState, modifier: Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val cursorColor = MaterialTheme.colorScheme.primary
    val background = Color(ColorMap.LUT[0])
    val buffers = remember { WaterfallBuffers() }
    val xRange by rememberUpdatedState(chart.xRange)
    val gestures by rememberUpdatedState(chart.gestures { chart.yRange ?: LIVE_Y }.xOnly())

    Canvas(modifier.clipToBounds().plotGestures({ xRange }, { gestures }, 0.dp)) {
        val left = PLOT_LEFT_MARGIN.toPx()
        val w = size.width - left
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        drawRect(background, topLeft = Offset(left, 0f), size = Size(w, h))
        val history = frame.value?.waterfall ?: return@Canvas
        val x = chart.xRange
        val image = buffers.render(history, chart.yRange ?: LIVE_Y)

        // The part of the history in the visible frequency range, placed exactly on the chart's axis.
        val columns = history.columns
        val c0 = floor(columns.position(x.start)).toInt().coerceIn(0, columns.count - 1)
        val c1 = ceil(columns.position(x.endInclusive)).toInt().coerceIn(c0 + 1, columns.count)
        val x0 = left + PlotMath.frequencyToX(columns.edgeOf(c0), x.start, x.endInclusive, w)
        val x1 = left + PlotMath.frequencyToX(columns.edgeOf(c1), x.start, x.endInclusive, w)
        clipRect(left = left) {
            drawImage(
                image,
                srcOffset = IntOffset(c0, 0),
                srcSize = IntSize(c1 - c0, history.rows),
                dstOffset = IntOffset(x0.roundToInt(), 0),
                dstSize = IntSize((x1 - x0).roundToInt().coerceAtLeast(1), h.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        }

        // Time axis: seconds ago.
        val seconds = history.seconds
        val step = timeStep(seconds, maxLabels = (h / 28.dp.toPx()).toInt().coerceAtLeast(1))
        var t = 0.0
        while (t <= seconds + 1e-9) {
            val y = (t / seconds * h).toFloat()
            val layout = textMeasurer.measure(if (t == 0.0) "0 s" else "−${formatNumber(t)} s", labelStyle)
            drawText(layout, topLeft = Offset(left - layout.size.width - 4.dp.toPx(), (y - layout.size.height / 2f).coerceIn(0f, (h - layout.size.height).coerceAtLeast(0f))))
            t += step
        }
        chart.cursorHz?.takeIf { it in x }?.let {
            val cx = left + PlotMath.frequencyToX(it, x.start, x.endInclusive, w)
            drawLine(cursorColor, Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)
        }
    }
}

/** The waterfall only zooms and pans the frequency axis; its vertical axis is time. */
private fun PlotGestures.xOnly(): PlotGestures {
    val base = this
    return object : PlotGestures {
        override fun cursor(frequencyHz: Double) = base.cursor(frequencyHz)
        override fun zoom(factorX: Double, factorY: Double, anchorX: Double, anchorY: Double) = base.zoom(factorX, 1.0, anchorX, anchorY)
        override fun pan(fractionX: Double, fractionY: Double) = base.pan(fractionX, 0.0)
        override fun reset() = base.reset()
    }
}

internal fun timeStep(seconds: Double, maxLabels: Int): Double =
    listOf(1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 60.0).firstOrNull { seconds / it <= maxLabels } ?: 60.0

@Composable
private fun LiveSettingsDialog(initial: LiveSettings, onApply: (LiveSettings) -> Unit, onDismiss: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Analyzer settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Sample rate", LiveSettings.SAMPLE_RATES, s.sampleRate, { "${formatKhz(it)} kHz" }, enabled = true) { s = s.copy(sampleRate = it) }
                StepSlider(
                    "FFT size: ${s.fftSize} · ${formatNumber(s.binWidthHz)} Hz/bin · ${s.blockMs.roundToInt()} ms",
                    LiveSettings.FFT_SIZES,
                    s.fftSize,
                ) { s = s.copy(fftSize = it) }
                StepSlider(
                    "Decimations: ${s.decimations}" +
                        if (s.decimations == 0) "" else " · ${formatNumber(s.finestBinWidthHz)} Hz/bin below ${formatLiveHz(s.finestBandTopHz)}",
                    (0..LiveSettings.MAX_DECIMATIONS).toList(),
                    s.decimations,
                ) { s = s.copy(decimations = it) }
                Hint("Each decimation halves the sample rate for the lower octaves: twice the frequency resolution there, but a slower response.")
                Choice("Window", WindowFunction.entries, s.window, { it.label }, enabled = true) { s = s.copy(window = it) }
                Hint(s.window.description)
                StepSlider(
                    "Update every ${s.intervalMs} ms (${1000 / s.intervalMs} per second)",
                    LiveSettings.INTERVALS_MS,
                    s.intervalMs,
                ) { s = s.copy(intervalMs = it) }
                StepSlider(
                    "Averaging: ${String.format(Locale.US, "%.2f", s.averaging)}" +
                        (s.averagingTimeMs?.let { " (≈ ${it.roundToInt()} ms)" } ?: " (off)"),
                    AVERAGING_STEPS,
                    AVERAGING_STEPS.minBy { kotlin.math.abs(it - s.averaging) },
                ) { s = s.copy(averaging = it) }
                Hint("Exponential smoothing of successive spectra: 0 follows every change, higher values give a calmer curve.")
                Choice("Peak hold", PeakHold.entries, s.peakHold, { it.label }, enabled = true) { s = s.copy(peakHold = it) }
                Hint("Falling: held peaks drop by ${LiveSettings.FALL_DB_PER_SEC.roundToInt()} dB per second. The reset button clears them.")
                TextButton(onClick = { s = LiveSettings() }) { Text("Reset to defaults") }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(s) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val AVERAGING_STEPS: List<Double> =
    (0..19).map { BigDecimal.valueOf(it * 0.05).setScale(2, RoundingMode.HALF_UP).toDouble() }

/** A slider over a few fixed choices, with the current value in the title. */
@Composable
private fun <T> StepSlider(title: String, options: List<T>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge.merge(TABULAR))
        Slider(
            value = options.indexOf(selected).coerceAtLeast(0).toFloat(),
            onValueChange = { onSelect(options[it.roundToInt().coerceIn(0, options.lastIndex)]) },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
        )
    }
}

internal fun formatLiveHz(f: Double): String = when {
    !f.isFinite() -> "–"
    f < 100 -> String.format(Locale.US, "%.1f Hz", f)
    f < 1_000 -> String.format(Locale.US, "%.0f Hz", f)
    f < 10_000 -> String.format(Locale.US, "%.2f kHz", f / 1_000)
    else -> String.format(Locale.US, "%.1f kHz", f / 1_000)
}

private fun formatDb(v: Double): String = if (v.isFinite()) String.format(Locale.US, "%.1f dB", v) else "–"

private fun formatKhz(rate: Int): String = BigDecimal.valueOf(rate / 1_000.0).stripTrailingZeros().toPlainString()

private fun formatNumber(v: Double): String = BigDecimal.valueOf(v).round(java.math.MathContext(3)).stripTrailingZeros().toPlainString()
