package de.flkaiser.paanalyzer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A small frequency-response curve over an axis, for the Results destination. */
val ResultsIcon: ImageVector = ImageVector.Builder(
    name = "Results",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(3f, 4f)
        lineTo(3f, 20f)
        lineTo(21f, 20f)
    }
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(6f, 13f)
        curveTo(8f, 13f, 8.5f, 8f, 11f, 8f)
        curveTo(13.5f, 8f, 13.5f, 15f, 16f, 15f)
        curveTo(18f, 15f, 18.5f, 10f, 20f, 10f)
    }
}.build()
