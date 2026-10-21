@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.flkaiser.paanalyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.flkaiser.paanalyzer.BuildConfig
import de.flkaiser.paanalyzer.audio.DeviceOption
import de.flkaiser.paanalyzer.audio.OutputChannel
import de.flkaiser.paanalyzer.dsp.MicCalibration
import de.flkaiser.paanalyzer.dsp.Smoothing
import de.flkaiser.paanalyzer.settings.SettingsForm
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import de.flkaiser.paanalyzer.dsp.SweepSettings

@Composable
fun SettingsScreen(state: UiState, viewModel: AppActions) {
    val form = state.form
    val enabled = !state.busy
    val update = viewModel::updateForm
    val calibrationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::loadCalibration)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (error in state.formErrors) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        Section("Devices")
        DevicePicker("Output", state.outputs, state.selectedOutput, enabled, viewModel::selectOutput)
        if (state.selectedOutput?.isBluetooth != true) {
            Text(
                "No Bluetooth output selected. Connect the PA's Bluetooth receiver, or measure through the chosen output.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        DevicePicker("Input", state.inputs, state.selectedInput, enabled, viewModel::selectInput)
        Hint(
            "Microphone processing: ${state.micSource}. Phone microphones are not calibrated; compare measurements " +
                "made with the same phone and position, or use a USB measurement microphone.",
        )
        Calibration(state.calibration, enabled, onLoad = { calibrationLauncher.launch(arrayOf("*/*")) }, onClear = viewModel::clearCalibration)
        Choice("Bluetooth channel", OutputChannel.entries, form.channel, { it.label }, enabled) { update(form.copy(channel = it)) }

        Section("Sweep")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Start", form.startHz, "Hz", enabled, Modifier.weight(1f)) { update(form.copy(startHz = it)) }
            NumberField("End", form.endHz, "Hz", enabled, Modifier.weight(1f)) { update(form.copy(endHz = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Duration", form.durationSec, "s", enabled, Modifier.weight(1f)) { update(form.copy(durationSec = it)) }
            NumberField("Level", form.levelDbfs, "dBFS", enabled, Modifier.weight(1f)) { update(form.copy(levelDbfs = it)) }
        }
        Choice("Sample rate", SweepSettings.SAMPLE_RATES, form.sampleRate, { "${it / 1000.0} kHz".replace(".0 ", " ") }, enabled) {
            update(form.copy(sampleRate = it))
        }
        Hint("Longer sweeps improve the signal-to-noise ratio and separate harmonics better. The duration is adjusted slightly so all harmonics stay in phase.")

        Section("Repetitions")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Averages", form.averages, "", enabled, Modifier.weight(1f), KeyboardType.Number) { update(form.copy(averages = it)) }
            NumberField("Silence before", form.preSilenceSec, "s", enabled, Modifier.weight(1f)) { update(form.copy(preSilenceSec = it)) }
            NumberField("Silence after", form.postSilenceSec, "s", enabled, Modifier.weight(1f)) { update(form.copy(postSilenceSec = it)) }
        }
        Hint("The silence before each sweep is used to estimate background noise; the silence after it must cover the room's reverberation.")

        Section("Analysis")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("IR window", form.irWindowMs, "ms", enabled, Modifier.weight(1f)) { update(form.copy(irWindowMs = it)) }
            NumberField("Harmonics up to", form.maxHarmonic, "", enabled, Modifier.weight(1f), KeyboardType.Number) { update(form.copy(maxHarmonic = it)) }
            NumberField("R&B from order", form.rubBuzzStartOrder, "", enabled, Modifier.weight(1f), KeyboardType.Number) {
                update(form.copy(rubBuzzStartOrder = it))
            }
        }
        Choice("Smoothing", Smoothing.entries, form.smoothing, { it.label }, enabled) { update(form.copy(smoothing = it)) }
        Hint("A short IR window (e.g. 10 ms) removes room reflections but loses resolution below about 1 / window length.")

        OutlinedButton(onClick = viewModel::resetSettings, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Reset to defaults") }

        Section("About")
        About()
    }
}

@Composable
private fun Calibration(calibration: MicCalibration?, enabled: Boolean, onLoad: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Microphone calibration", style = MaterialTheme.typography.labelLarge)
        if (calibration == null) {
            Hint("None. Load the calibration file of a measurement microphone (e.g. UMIK-1: the 90° file when the mic points up).")
        } else {
            Text(calibration.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Hint(
                "${calibration.size} points, ${PlotMath.formatFrequency(calibration.frequencies.first())} Hz – " +
                    "${PlotMath.formatFrequency(calibration.frequencies.last())} Hz" +
                    (calibration.sensitivityDb?.let { ", sensitivity ${SettingsForm.format(it)} dB" } ?: "") +
                    ". Applied to new measurements and when analysing again; only use it with that microphone.",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onLoad, enabled = enabled) { Text(if (calibration == null) "Load file" else "Replace") }
            if (calibration != null) TextButton(onClick = onClear, enabled = enabled) { Text("Remove") }
        }
    }
}

@Composable
private fun About() {
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PA Analyzer ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Measures a PA system over Bluetooth with the phone's microphone: frequency response, harmonic distortion " +
                    "and rub & buzz from an exponential sweep, plus EQ suggestions for your mixer or speaker processor.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Methods: synchronized exponential sweep (Farina; Novak et al.), RBJ Audio EQ Cookbook filters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Measurements stay on this phone; the app has no internet access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { uriHandler.openUri(SOURCE_URL) }) { Text("Source code") }
        }
    }
}

private const val SOURCE_URL = "https://github.com/KaiFra/PA-Analyzer"

@Composable
private fun DevicePicker(
    label: String,
    options: List<DeviceOption>,
    selected: DeviceOption?,
    enabled: Boolean,
    onSelect: (DeviceOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(selected?.label ?: "System default", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
}

@Composable
internal fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun NumberField(
    label: String,
    value: String,
    suffix: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Decimal,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        suffix = if (suffix.isEmpty()) null else ({ Text(suffix) }),
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}

@Composable
internal fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T,
    text: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) {
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(text(option)) },
                    enabled = enabled,
                )
            }
        }
    }
}
