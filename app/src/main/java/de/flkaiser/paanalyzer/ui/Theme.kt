package de.flkaiser.paanalyzer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun PaAnalyzerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Fixed chart colours that read well on light and dark backgrounds. */
object ChartColors {
    val fundamental = Color(0xFF2F80ED)
    val thd = Color(0xFFE53935)
    val harmonics = listOf(
        Color(0xFFFB8C00), // H2
        Color(0xFF43A047), // H3
        Color(0xFF8E24AA), // H4
        Color(0xFF00ACC1), // H5
        Color(0xFF6D4C41), // H6
        Color(0xFFD81B60), // H7
        Color(0xFF7CB342), // H8
        Color(0xFF3949AB), // H9
        Color(0xFFFDD835), // H10
    )
    val rubBuzzRms = Color(0xFFE53935)
    val rubBuzzPeak = Color(0xFFFB8C00)
    val noise = Color(0xFF9E9E9E)
}
