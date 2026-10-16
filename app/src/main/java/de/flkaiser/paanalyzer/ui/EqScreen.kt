@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.flkaiser.paanalyzer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.flkaiser.paanalyzer.dsp.EqDesigner
import de.flkaiser.paanalyzer.dsp.EqExport
import de.flkaiser.paanalyzer.dsp.EqSettings
import de.flkaiser.paanalyzer.dsp.EqSuggestion
import de.flkaiser.paanalyzer.dsp.EqTarget
import de.flkaiser.paanalyzer.dsp.FilterType
import de.flkaiser.paanalyzer.settings.SettingsForm
import java.util.Locale

private val MEASURED_COLOR = Color(0xFF2F80ED)
private val TARGET_COLOR = Color(0xFF9E9E9E)
private val PREDICTED_COLOR = Color(0xFF2E7D32)
private val EQ_COLOR = Color(0xFFFB8C00)

@Composable
fun EqScreen(state: UiState, viewModel: AppActions, landscape: Boolean) {
    val current = state.current
    if (current == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No measurement open", style = MaterialTheme.typography.titleMedium)
            Text(
                "Measure the PA (or open a saved measurement on the Results page) to get EQ suggestions.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { viewModel.selectPage(Page.MEASURE) }) { Text("Measure") }
        }
        return
    }
    val suggestion = state.eqSuggestion
    val chart = remember(current.createdAtMillis) { ChartState(current.result.settings.startHz..current.result.settings.endHz) }

    if (landscape) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(4.dp)) {
                if (suggestion != null) EqChart(suggestion, chart, Modifier.fillMaxSize())
            }
            Column(
                Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Header(current.name)
                if (suggestion != null) {
                    Legend(suggestion, chart.cursorHz)
                    FilterList(suggestion, current.name)
                }
                state.eqError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Controls(state.eqSettings, state.eqSuggestion, viewModel)
                Caveat()
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header(current.name)
            if (suggestion != null) {
                EqChart(suggestion, chart, Modifier.fillMaxWidth().height(280.dp))
                Legend(suggestion, chart.cursorHz)
                FilterList(suggestion, current.name)
            }
            state.eqError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Controls(state.eqSettings, state.eqSuggestion, viewModel)
            Caveat()
        }
    }
}

@Composable
private fun Header(name: String) {
    Column {
        Text("EQ suggestion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("for “$name”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EqChart(s: EqSuggestion, chart: ChartState, modifier: Modifier) {
    val series = listOf(
        PlotSeries(s.frequencies, s.targetDb, TARGET_COLOR, dashed = true, width = 1.5f),
        PlotSeries(s.frequencies, s.measuredDb, MEASURED_COLOR, width = 2f),
        PlotSeries(s.frequencies, s.eqDb, EQ_COLOR, width = 1.5f),
        PlotSeries(s.frequencies, s.predictedDb, PREDICTED_COLOR, width = 2.5f),
    )
    val autoY = { PlotMath.autoRange(series.map { it.values }, minSpan = 30.0, maxSpan = 60.0, step = 5.0) }
    FrequencyPlot(
        series = series,
        xRange = chart.xRange,
        yRange = chart.yRange ?: autoY(),
        cursorHz = chart.cursorHz,
        gestures = chart.gestures(autoY),
        modifier = modifier,
    )
}

@Composable
private fun Legend(s: EqSuggestion, cursorHz: Double?) {
    fun at(values: DoubleArray): String? = cursorHz?.let {
        val v = Curves.interpolateLog(s.frequencies, values, it)
        if (v.isFinite()) String.format(Locale.getDefault(), "%.1f dB", v) else null
    }
    Column {
        Text(
            if (cursorHz == null) "Tap the chart for values" else "Cursor: ${PlotMath.formatFrequency(cursorHz)} Hz",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem("Measured", MEASURED_COLOR, at(s.measuredDb))
            LegendItem("Target", TARGET_COLOR, at(s.targetDb))
            LegendItem("EQ", EQ_COLOR, at(s.eqDb))
            LegendItem("With EQ", PREDICTED_COLOR, at(s.predictedDb))
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color, value: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(if (value == null) label else "$label $value", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun FilterList(s: EqSuggestion, name: String) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Deviation from target ${EqDesigner.formatHz(s.correctedFromHz)} – ${EqDesigner.formatHz(s.correctedToHz)}: " +
                    String.format(Locale.getDefault(), "±%.1f dB → ±%.1f dB", s.rmsBeforeDb, s.rmsAfterDb) +
                    " · ${s.filters.size} of ${s.settings.filterCount} bands",
                style = MaterialTheme.typography.bodySmall,
            )
            s.highPass?.let {
                FilterRow("HPF", "High-pass", EqDesigner.formatHz(it.frequencyHz), "", "${it.slopeDbPerOctave} dB/oct LR")
            }
            if (s.filters.isEmpty() && s.highPass == null) Text("No EQ needed.", style = MaterialTheme.typography.bodyMedium)
            for ((i, f) in s.filters.withIndex()) {
                FilterRow(
                    number = "${i + 1}",
                    type = if (f.type == FilterType.PEAK) "Peak" else f.type.label,
                    frequency = EqDesigner.formatHz(f.frequencyHz),
                    gain = String.format(Locale.getDefault(), "%+.1f dB", f.gainDb),
                    shape = if (f.type == FilterType.PEAK) String.format(Locale.getDefault(), "Q %.2f · %.2f oct", f.q, f.bandwidthOctaves) else "12 dB/oct",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(context, EqExport.toText(s, name)) }) { Text("Copy") }
                OutlinedButton(onClick = { shareText(context, EqExport.toText(s, name)) }) { Text("Share") }
            }
            for (note in s.notes) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FilterRow(number: String, type: String, frequency: String, gain: String, shape: String) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number, style = style, modifier = Modifier.width(28.dp), fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(frequency, style = style, fontWeight = FontWeight.SemiBold)
                Text(gain, style = style)
            }
            Text("$type · $shape", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Controls(settings: EqSettings, suggestion: EqSuggestion?, viewModel: AppActions) {
    var minHz by remember(settings.minHz) { mutableStateOf(SettingsForm.format(settings.minHz)) }
    var maxHz by remember(settings.maxHz) { mutableStateOf(SettingsForm.format(settings.maxHz)) }
    fun applyRange(lo: String, hi: String) {
        val l = lo.trim().replace(',', '.').toDoubleOrNull() ?: return
        val h = hi.trim().replace(',', '.').toDoubleOrNull() ?: return
        val updated = settings.copy(minHz = l, maxHz = h)
        if (updated.validate().isEmpty()) viewModel.updateEqSettings(updated)
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("EQ settings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Changes apply immediately: the suggestion and chart above update.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Choice("Target", EqTarget.entries, settings.target, { it.label }, enabled = true) {
                viewModel.updateEqSettings(settings.copy(target = it))
            }
            Text(settings.target.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Bands available on your mixer", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.updateEqSettings(settings.copy(filterCount = settings.filterCount - 1)) }, enabled = settings.filterCount > 1) {
                    Text("−")
                }
                Text("${settings.filterCount}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { viewModel.updateEqSettings(settings.copy(filterCount = settings.filterCount + 1)) },
                    enabled = settings.filterCount < EqSettings.MAX_FILTERS,
                ) { Text("+") }
            }
            suggestion?.let { BandUsage(it) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Correct from", minHz, "Hz", enabled = true, modifier = Modifier.weight(1f)) {
                    minHz = it
                    applyRange(it, maxHz)
                }
                NumberField("to", maxHz, "Hz", enabled = true, modifier = Modifier.weight(1f)) {
                    maxHz = it
                    applyRange(minHz, it)
                }
            }
            Choice("Maximum boost", listOf(0.0, 3.0, 6.0), settings.maxBoostDb, { "+${it.toInt()} dB" }, enabled = true) {
                viewModel.updateEqSettings(settings.copy(maxBoostDb = it))
            }
            Choice("Maximum cut", listOf(6.0, 12.0, 18.0), settings.maxCutDb, { "−${it.toInt()} dB" }, enabled = true) {
                viewModel.updateEqSettings(settings.copy(maxCutDb = it))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Suggest a high-pass at the bass roll-off", modifier = Modifier.weight(1f))
                Switch(checked = settings.suggestHighPass, onCheckedChange = { viewModel.updateEqSettings(settings.copy(suggestHighPass = it)) })
            }
            TextButton(onClick = { viewModel.updateEqSettings(EqSettings()) }) { Text("Reset EQ settings") }
        }
    }
}

/** Explains why the number of suggested bands can be lower than the number allowed. */
@Composable
private fun BandUsage(s: EqSuggestion) {
    val used = s.filters.size
    val allowed = s.settings.filterCount
    val text = when {
        used == allowed -> "Uses all $allowed bands. More bands may bring the response closer to the target."
        used == 0 -> "No bands needed: the response is already within about 1 dB of the target."
        else -> "Uses $used of $allowed bands: further bands would change the result by less than about 1 dB, so they are left out. " +
            "A stricter target needs a wider range, more boost or a different target curve."
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Caveat() {
    Text(
        "The suggestion is based on one microphone position and the phone's uncalibrated microphone. " +
            "Measure where the audience is (or several positions and compare), apply the filters, then measure again. " +
            "EQ can reduce resonances and correct the tonal balance, but it can't fill dips caused by room reflections " +
            "or fix timing between speakers.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 16.dp),
    )
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("EQ suggestion", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "EQ suggestion")
    }
    context.startActivity(Intent.createChooser(send, "Share EQ suggestion"))
}
