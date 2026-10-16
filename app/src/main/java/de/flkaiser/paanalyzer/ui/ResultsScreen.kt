@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.flkaiser.paanalyzer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.flkaiser.paanalyzer.data.SavedEntry
import de.flkaiser.paanalyzer.dsp.AnalysisSettings
import de.flkaiser.paanalyzer.dsp.Smoothing
import de.flkaiser.paanalyzer.settings.SettingsForm
import java.text.DateFormat
import java.util.Date

@Composable
fun ResultsScreen(state: UiState, viewModel: AppActions, landscape: Boolean) {
    var showLibrary by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showAnalysis by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            showLibrary = false
            viewModel.importFiles(uris)
        }
    }
    val actions = ResultActions(
        onSave = { showSave = true },
        onLibrary = { showLibrary = true },
        onDetails = { showDetails = true },
        onAnalysis = { showAnalysis = true },
        onImport = { importLauncher.launch(arrayOf("*/*")) },
    )

    val current = state.current
    if (current == null) {
        EmptyResults(state, viewModel, actions)
    } else if (landscape) {
        LandscapeResults(current, state, viewModel, actions)
    } else {
        PortraitResults(current, state, viewModel, actions)
    }

    if (showLibrary) {
        LibraryDialog(
            entries = state.library,
            currentId = current?.savedId,
            onOpen = { showLibrary = false; viewModel.loadSaved(it) },
            onRename = viewModel::renameSaved,
            onDelete = viewModel::deleteSaved,
            onImport = actions.onImport,
            onDismiss = { showLibrary = false },
        )
    }
    if (showSave && current != null) {
        NameDialog(
            title = if (current.savedId == null) "Save measurement" else "Rename measurement",
            initial = current.name,
            onConfirm = { showSave = false; viewModel.saveCurrent(it) },
            onDismiss = { showSave = false },
        )
    }
    if (showAnalysis && current != null) {
        AnalysisDialog(
            current = current,
            onApply = { analysis, asDefault ->
                showAnalysis = false
                viewModel.reanalyze(analysis)
                if (asDefault) {
                    viewModel.updateForm(
                        state.form.copy(
                            irWindowMs = SettingsForm.format(analysis.irWindowMs),
                            maxHarmonic = analysis.maxHarmonic.toString(),
                            rubBuzzStartOrder = analysis.rubBuzzStartOrder.toString(),
                            smoothing = analysis.smoothing,
                        ),
                    )
                }
            },
            onDismiss = { showAnalysis = false },
        )
    }
    if (showDetails && current != null) {
        DetailsDialog(current, state.micSource) { showDetails = false }
    }
}

private class ResultActions(
    val onSave: () -> Unit,
    val onLibrary: () -> Unit,
    val onDetails: () -> Unit,
    val onAnalysis: () -> Unit,
    val onImport: () -> Unit,
)

/** Zoom, pan and cursor of the chart for one measurement. */
@Composable
private fun rememberChartState(current: CurrentMeasurement): ChartState {
    val s = current.result.settings
    // Keyed by the measurement, not its result, so zoom survives changing the analysis settings.
    return remember(current.createdAtMillis, s.startHz, s.endHz) { ChartState(s.startHz..s.endHz) }
}

@Composable
private fun Chart(current: CurrentMeasurement, visible: Set<String>, chart: ChartState, modifier: Modifier) {
    val shown = current.curves.filter { it.id in visible }
    val autoY = { PlotMath.autoRange(shown.map { it.absoluteDb }) }
    FrequencyPlot(
        series = shown.map { curve ->
            val style = curveStyle(curve.id)
            PlotSeries(curve.frequencies, curve.absoluteDb, style.color, style.dashed, style.width)
        },
        xRange = chart.xRange,
        yRange = chart.yRange ?: autoY(),
        cursorHz = chart.cursorHz,
        gestures = chart.gestures(autoY),
        modifier = modifier,
    )
}

@Composable
private fun PortraitResults(current: CurrentMeasurement, state: UiState, viewModel: AppActions, actions: ResultActions) {
    val chart = rememberChartState(current)
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        ResultHeader(current, viewModel, actions, compact = false, reanalyzing = state.reanalyzing)
        Chart(current, state.visibleCurves, chart, Modifier.fillMaxWidth().weight(1f))
        CursorLine(chart)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(bottom = 4.dp),
        ) {
            for (curve in current.curves) CurveChip(curve, curve.id in state.visibleCurves, chart.cursorHz) { viewModel.toggleCurve(curve.id) }
        }
    }
}

@Composable
private fun LandscapeResults(current: CurrentMeasurement, state: UiState, viewModel: AppActions, actions: ResultActions) {
    val chart = rememberChartState(current)
    var panelOpen by rememberSaveable { mutableStateOf(true) }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Chart(current, state.visibleCurves, chart, Modifier.fillMaxSize())
            if (!panelOpen) {
                FilledTonalButton(onClick = { panelOpen = true }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    Text("Curves")
                }
            }
        }
        if (panelOpen) {
            Column(
                Modifier.width(232.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ResultHeader(current, viewModel, actions, compact = true, reanalyzing = state.reanalyzing, onHide = { panelOpen = false })
                CursorLine(chart)
                for (curve in current.curves) {
                    CurveChip(curve, curve.id in state.visibleCurves, chart.cursorHz, Modifier.fillMaxWidth()) { viewModel.toggleCurve(curve.id) }
                }
            }
        }
    }
}

@Composable
private fun ResultHeader(
    current: CurrentMeasurement,
    viewModel: AppActions,
    actions: ResultActions,
    compact: Boolean,
    reanalyzing: Boolean,
    onHide: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).clickable(onClick = actions.onSave).padding(vertical = 4.dp),
            ) {
                Text(
                    current.name,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    maxLines = if (compact) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (current.savedId == null) "Not saved – tap to save" else "Saved – tap to rename",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (current.savedId == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onHide != null) TextButton(onClick = onHide) { Text("Hide") }
        }
        FlowRow(verticalArrangement = Arrangement.Center) {
            if (current.savedId == null) {
                Button(onClick = actions.onSave, modifier = Modifier.align(Alignment.CenterVertically)) { Text("Save") }
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = actions.onAnalysis, enabled = !reanalyzing) {
                Icon(Icons.Filled.Build, contentDescription = "Analysis settings")
            }
            IconButton(onClick = { viewModel.exportCurrent()?.let { shareMeasurement(context, it) } }) {
                Icon(Icons.Filled.Share, contentDescription = "Share")
            }
            IconButton(onClick = actions.onLibrary) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Saved measurements") }
            IconButton(onClick = actions.onDetails) { Icon(Icons.Filled.Info, contentDescription = "Measurement details") }
        }
        if (reanalyzing) {
            Text("Analysing again…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 2.dp))
        }
        if (current.result.warnings.isNotEmpty()) {
            Text(
                "⚠ ${current.result.warnings.first()}" + if (current.result.warnings.size > 1) " (+${current.result.warnings.size - 1})" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = actions.onDetails),
            )
        }
    }
}

@Composable
private fun CursorLine(chart: ChartState) {
    val cursor = chart.cursorHz
    Text(
        if (cursor == null) "Tap for values · pinch to zoom · double-tap to reset" else "Cursor: ${PlotMath.formatFrequency(cursor)} Hz",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun CurveChip(curve: Curve, visible: Boolean, cursorHz: Double?, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val style = curveStyle(curve.id)
    val value = if (visible) cursorHz?.let { Curves.readout(curve, it) } else null
    FilterChip(
        selected = visible,
        onClick = onToggle,
        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(style.color)) },
        label = { Text(if (value == null) curve.label else "${curve.label}: $value", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
    )
}

@Composable
private fun EmptyResults(state: UiState, viewModel: AppActions, actions: ResultActions) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No measurement open", style = MaterialTheme.typography.titleMedium)
        Text(
            "Run a measurement, or open one you saved earlier.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { viewModel.selectPage(Page.MEASURE) }) { Text("Measure") }
        OutlinedButton(onClick = actions.onLibrary, enabled = state.library.isNotEmpty()) {
            Text("Saved measurements (${state.library.size})")
        }
        TextButton(onClick = actions.onImport) { Text("Import CSV file") }
    }
}

@Composable
private fun LibraryDialog(
    entries: List<SavedEntry>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<SavedEntry?>(null) }
    var deleting by remember { mutableStateOf<SavedEntry?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text("Saved measurements", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.size(8.dp))
                if (entries.isEmpty()) {
                    Text(
                        "Nothing saved yet. Save a measurement from the results page, or import a CSV file shared from this app.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(entry.id) }.padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    fontWeight = if (entry.id == currentId) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(formatDate(entry.createdAtMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { renaming = entry }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
                            IconButton(onClick = { deleting = entry }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                        }
                        HorizontalDivider()
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onImport) { Text("Import CSV") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
    renaming?.let { entry ->
        NameDialog("Rename measurement", entry.name, onConfirm = { onRename(entry.id, it); renaming = null }, onDismiss = { renaming = null })
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete measurement?") },
            text = { Text("“${entry.name}” will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { onDelete(entry.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DetailsDialog(current: CurrentMeasurement, micSource: String, onDismiss: () -> Unit) {
    val r = current.result
    val s = r.settings
    fun n(v: Double) = SettingsForm.format(v)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(current.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(formatDate(current.createdAtMillis), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Sweep ${n(s.startHz)}–${n(s.endHz)} Hz, ${n(s.durationSec)} s, ${n(s.levelDbfs)} dBFS, ${s.sampleRate} Hz")
                Text("${s.averages} average(s), ${n(s.preSilenceSec)} s / ${n(s.postSilenceSec)} s silence")
                Text("${s.smoothing.label} smoothing, ${n(s.irWindowMs)} ms IR window")
                Text("THD from harmonics 2–${s.maxHarmonic}, rub & buzz above order ${s.rubBuzzStartOrder}")
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                if (!r.delayMs.isNaN()) Text("Delay: %.0f ms (includes Bluetooth latency)".format(r.delayMs))
                if (!r.inputPeakDbfs.isNaN()) Text("Input peak: %.1f dBFS".format(r.inputPeakDbfs))
                if (!r.irSnrDb.isNaN()) Text("Impulse response signal-to-noise: %.0f dB".format(r.irSnrDb))
                if (current.savedId == null) Text("Microphone processing: $micSource")
                for (w in r.warnings) Text("⚠ $w", color = MaterialTheme.colorScheme.error)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Distortion and rub & buzz are drawn at their absolute level (fundamental + relative level); " +
                        "the cursor values show them relative to the fundamental.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AnalysisDialog(
    current: CurrentMeasurement,
    onApply: (AnalysisSettings, asDefault: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = current.result.settings
    var smoothing by remember { mutableStateOf(s.smoothing) }
    var irWindow by remember { mutableStateOf(SettingsForm.format(s.irWindowMs)) }
    var harmonics by remember { mutableStateOf(s.maxHarmonic.toString()) }
    var order by remember { mutableStateOf(s.rubBuzzStartOrder.toString()) }
    var asDefault by remember { mutableStateOf(false) }

    val parsed = AnalysisInput(irWindow, harmonics, order, smoothing).parse(s)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Analysis settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.recording == null) {
                    Text(
                        "This measurement has no stored recording (saved with an older version, or imported without its WAV file), " +
                            "so it can't be analysed again. Measure again to change these settings.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text("Smoothing", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in Smoothing.entries) {
                            FilterChip(selected = option == smoothing, onClick = { smoothing = option }, label = { Text(option.label) })
                        }
                    }
                    OutlinedTextField(irWindow, { irWindow = it }, label = { Text("IR window") }, suffix = { Text("ms") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(harmonics, { harmonics = it }, label = { Text("Harmonics up to") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(order, { order = it }, label = { Text("Rub & buzz from order") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    for (error in parsed.second) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { asDefault = !asDefault }) {
                        Checkbox(checked = asDefault, onCheckedChange = { asDefault = it })
                        Text("Also use for new measurements")
                    }
                    Text(
                        "Sweep settings (range, duration, level, averages) are part of the recording and can't be changed afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed.first?.let { onApply(it, asDefault) } }, enabled = current.recording != null && parsed.first != null) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatDate(millis: Long): String =
    if (millis <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
