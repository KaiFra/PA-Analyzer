package de.flkaiser.paanalyzer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.flkaiser.paanalyzer.ui.PaAnalyzerApp
import de.flkaiser.paanalyzer.ui.PaAnalyzerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaAnalyzerTheme {
                PaAnalyzerApp()
            }
        }
    }
}
