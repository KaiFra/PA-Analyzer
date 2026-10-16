@file:OptIn(ExperimentalMaterial3Api::class)

package de.flkaiser.paanalyzer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.flkaiser.paanalyzer.audio.DeviceOption
import de.flkaiser.paanalyzer.dsp.MeasurementResult
import de.flkaiser.paanalyzer.dsp.dbToPercent
import kotlin.math.max

@Composable
fun MeasureScreen(state: UiState, viewModel: MeasurementViewModel) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) viewModel.start()
    }

    // Keep the display on while the sweep plays; some phones throttle audio when the screen turns off.
    val view = LocalView.current
    DisposableEffect(state.busy) {
        view.keepScreenOn = state.busy
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DevicesCard(state, viewModel)

        val sweep = state.form
        Text(
            "Sweep ${sweep.startHz}–${sweep.endHz} Hz, ${sweep.durationSec} s at ${sweep.levelDbfs} dBFS" +
                (if (sweep.averages != "1") ", ${sweep.averages}× averaged" else "") + ". Start with the PA turned down; " +
                "a sweep puts full-range energy into the tweeters.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.busy) {
            val (label, progress) = when (val p = state.phase) {
                is Phase.Measuring -> "Playing sweep and recording…" to p.progress
                is Phase.Analyzing -> "Analyzing…" to p.progress
                else -> "" to 0.0
            }
            Text(label, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        } else {
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = state.formErrors.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.result == null) "Start measurement" else "Measure again") }
            if (state.formErrors.isNotEmpty()) {
                Text("Fix the settings first: ${state.formErrors.first()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (permissionDenied) {
            ErrorCard("The microphone permission is needed to record the sweep.") { permissionDenied = false }
        }
        (state.phase as? Phase.Failed)?.let { ErrorCard(it.message, viewModel::dismissError) }

        state.result?.let { ResultSection(it, state.micSource) }
    }
}

@Composable
private fun DevicesCard(state: UiState, viewModel: MeasurementViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DevicePicker("Output", state.outputs, state.selectedOutput, "System default", enabled = !state.busy, onSelect = viewModel::selectOutput)
            val output = state.selectedOutput
            if (output == null || !output.isBluetooth) {
                Text(
                    "No Bluetooth output selected. Connect the PA's Bluetooth receiver, or measure through the chosen output.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            DevicePicker("Input", state.inputs, state.selectedInput, "System default", enabled = !state.busy, onSelect = viewModel::selectInput)
            Text(
                "Microphone processing: ${state.micSource}. Phone microphones are not calibrated; " +
                    "compare measurements made with the same phone and position.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DevicePicker(
    label: String,
    options: List<DeviceOption>,
    selected: DeviceOption?,
    placeholder: String,
    enabled: Boolean,
    onSelect: (DeviceOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(selected?.label ?: placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

private enum class Chart(val label: String) { FUNDAMENTAL("Fundamental"), DISTORTION("Distortion"), RUB_BUZZ("Rub & buzz") }

@Composable
private fun ResultSection(result: MeasurementResult, micSource: String) {
    val context = LocalContext.current
    var chartIndex by rememberSaveable { mutableIntStateOf(0) }
    val chart = Chart.entries[chartIndex]

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in Chart.entries) {
            FilterChip(selected = c == chart, onClick = { chartIndex = c.ordinal }, label = { Text(c.label) })
        }
    }
    when (chart) {
        Chart.FUNDAMENTAL -> FundamentalChart(result)
        Chart.DISTORTION -> DistortionChart(result)
        Chart.RUB_BUZZ -> RubBuzzChart(result)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Measurement", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Delay: %.0f ms (playback start to sound arrival, includes Bluetooth latency)".format(result.delayMs))
            Text("Input peak: %.1f dBFS".format(result.inputPeakDbfs))
            if (!result.irSnrDb.isNaN()) Text("Impulse response signal-to-noise: %.0f dB".format(result.irSnrDb))
            Text("Microphone processing: $micSource")
            for (w in result.warnings) Text("⚠ $w", color = MaterialTheme.colorScheme.error)
        }
    }
    OutlinedButton(onClick = { shareCsv(context, result) }, modifier = Modifier.fillMaxWidth()) { Text("Share as CSV") }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun FundamentalChart(result: MeasurementResult) {
    val s = result.settings
    FrequencyPlot(
        series = listOf(PlotSeries("Fundamental", result.frequencies, result.fundamentalDb, ChartColors.fundamental)),
        minHz = s.startHz,
        maxHz = s.endHz,
        range = PlotMath.rangeBelowMax(listOf(result.fundamentalDb), span = 60.0),
        unit = "dB",
    )
    Explanation(
        "Frequency response of the whole chain (Bluetooth, PA, room, microphone) in dB relative to the digital level. " +
            "${s.smoothing.label} smoothing, ${SettingsFormatter.number(s.irWindowMs)} ms impulse response window.",
    )
}

@Composable
private fun DistortionChart(result: MeasurementResult) {
    val s = result.settings
    val series = buildList {
        add(PlotSeries("THD", result.frequencies, result.thdDb, ChartColors.thd, width = 2.5f))
        for (h in result.harmonics) {
            add(PlotSeries("H${h.order}", result.frequencies, h.levelDb, ChartColors.harmonics[(h.order - 2) % ChartColors.harmonics.size], width = 1.5f))
        }
    }
    val auto = PlotMath.rangeBelowMax(series.map { it.values }, span = 80.0, headroom = 5.0)
    val upper = minOf(auto.endInclusive, 10.0)
    FrequencyPlot(
        series = series,
        minHz = s.startHz,
        maxHz = s.endHz / 2,
        range = (upper - 80.0)..upper,
        unit = "dB",
        cursorValue = { "%.1f dB (%s %%)".format(it, SettingsFormatter.percent(dbToPercent(it))) },
    )
    Explanation(
        "Harmonic distortion relative to the fundamental, plotted at the excitation frequency. " +
            "THD sums harmonics 2–${s.maxHarmonic}. −20 dB = 10 %, −40 dB = 1 %, −60 dB = 0.1 %.",
    )
}

@Composable
private fun RubBuzzChart(result: MeasurementResult) {
    val s = result.settings
    val rb = result.rubBuzz
    if (rb.size == 0) {
        Explanation("The sweep range is too narrow for rub & buzz analysis (order ${s.rubBuzzStartOrder} must stay below 20 kHz).")
        return
    }
    val series = listOf(
        PlotSeries("RMS", rb.frequencies, rb.rmsDb, ChartColors.rubBuzzRms, width = 2.5f),
        PlotSeries("Peak", rb.frequencies, rb.peakDb, ChartColors.rubBuzzPeak, width = 1.5f),
        PlotSeries("Noise floor", rb.frequencies, rb.noiseFloorDb, ChartColors.noise, dashed = true, width = 1.5f),
    )
    FrequencyPlot(
        series = series,
        minHz = s.startHz,
        maxHz = max(rb.frequencies.last() * 1.2, s.startHz * 4),
        range = PlotMath.rangeBelowMax(series.map { it.values }, span = 80.0, headroom = 5.0),
        unit = "dB",
    )
    // Point out the frequency where rub & buzz rises furthest above the background noise.
    val aboveNoise = rb.rmsDb.indices
        .filter { rb.rmsDb[it].isFinite() && rb.noiseFloorDb[it].isFinite() }
        .maxByOrNull { rb.rmsDb[it] - rb.noiseFloorDb[it] }
        ?.takeIf { rb.rmsDb[it] - rb.noiseFloorDb[it] > NOTABLE_RUB_BUZZ_DB }
    Explanation(
        "Energy above ${s.rubBuzzStartOrder}× the excitation frequency, relative to the fundamental. " +
            "Rattles, rubbing voice coils and loose parts show up well above the noise floor; " +
            "a peak far above the RMS curve means impulsive buzz." +
            (aboveNoise?.let { " Largest rise above noise near %s Hz.".format(PlotMath.formatFrequency(rb.frequencies[it])) } ?: ""),
    )
}

private const val NOTABLE_RUB_BUZZ_DB = 10.0

@Composable
private fun Explanation(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Small number formatting helpers for the UI. */
object SettingsFormatter {
    fun number(v: Double): String = de.flkaiser.paanalyzer.settings.SettingsForm.format(v)

    fun percent(p: Double): String = when {
        p >= 10 -> "%.0f".format(p)
        p >= 1 -> "%.1f".format(p)
        p >= 0.1 -> "%.2f".format(p)
        else -> "%.3f".format(p)
    }
}
