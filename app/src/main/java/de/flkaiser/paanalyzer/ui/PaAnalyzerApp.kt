package de.flkaiser.paanalyzer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private data class Destination(val page: Page, val label: String, val icon: ImageVector)

private val DESTINATIONS = listOf(
    Destination(Page.MEASURE, "Measure", Icons.Filled.PlayArrow),
    Destination(Page.RESULTS, "Results", ResultsIcon),
    Destination(Page.EQ, "EQ", EqIcon),
    Destination(Page.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun PaAnalyzerApp(viewModel: MeasurementViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppScaffold(state, viewModel)
}

/** The whole UI for a given state; separated from the ViewModel so it can be rendered in screenshot tests. */
@Composable
fun AppScaffold(state: UiState, viewModel: AppActions) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    // Viewing curves in landscape: give the chart the whole screen (bars come back with a swipe).
    ImmersiveMode(enabled = landscape && state.page == Page.RESULTS && state.current != null)

    val content: @Composable () -> Unit = {
        when (state.page) {
            Page.MEASURE -> MeasureScreen(state, viewModel)
            Page.RESULTS -> ResultsScreen(state, viewModel, landscape)
            Page.EQ -> EqScreen(state, viewModel, landscape)
            Page.SETTINGS -> SettingsScreen(state, viewModel)
        }
    }

    if (landscape) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                NavigationRail(windowInsets = WindowInsets(0), modifier = Modifier.fillMaxHeight()) {
                    for (d in DESTINATIONS) {
                        NavigationRailItem(
                            selected = state.page == d.page,
                            onClick = { viewModel.selectPage(d.page) },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    for (d in DESTINATIONS) {
                        NavigationBarItem(
                            selected = state.page == d.page,
                            onClick = { viewModel.selectPage(d.page) },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { content() }
        }
    }
}

@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    DisposableEffect(enabled, window) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
