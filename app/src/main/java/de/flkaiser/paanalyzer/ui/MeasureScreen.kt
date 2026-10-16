package de.flkaiser.paanalyzer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

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
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Output: ${state.selectedOutput?.label ?: "system default"}")
                    Text("Input: ${state.selectedInput?.label ?: "system default"}")
                    val f = state.form
                    Text(
                        "Sweep ${f.startHz}–${f.endHz} Hz, ${f.durationSec} s at ${f.levelDbfs} dBFS" +
                            if (f.averages != "1") ", ${f.averages}× averaged" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.selectedOutput?.isBluetooth != true) {
                        Text(
                            "No Bluetooth output selected. Connect the PA's Bluetooth receiver or choose an output in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = { viewModel.selectPage(Page.SETTINGS) }, enabled = !state.busy) { Text("Change in Settings") }
                }
            }

            Text(
                "Start with the PA turned down: a sweep puts full-range energy into the tweeters.",
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
                ) { Text("Start measurement") }
                if (state.formErrors.isNotEmpty()) {
                    Text("Fix the settings first: ${state.formErrors.first()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (permissionDenied) {
                ErrorCard("The microphone permission is needed to record the sweep.") { permissionDenied = false }
            }
            (state.phase as? Phase.Failed)?.let { ErrorCard(it.message, viewModel::dismissError) }

            state.current?.let { current ->
                val r = current.result
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(current.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (!r.delayMs.isNaN()) Text("Delay: %.0f ms (includes Bluetooth latency)".format(r.delayMs))
                        if (!r.inputPeakDbfs.isNaN()) Text("Input peak: %.1f dBFS".format(r.inputPeakDbfs))
                        if (!r.irSnrDb.isNaN()) Text("Impulse response signal-to-noise: %.0f dB".format(r.irSnrDb))
                        for (w in r.warnings) Text("⚠ $w", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.selectPage(Page.RESULTS) }) { Text("Show results") }
                    }
                }
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
