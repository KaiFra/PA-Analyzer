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

/** Three mixer faders, for the EQ destination. */
val EqIcon: ImageVector = ImageVector.Builder(
    name = "Eq",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // Fader tracks.
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        moveTo(6f, 3f); lineTo(6f, 21f)
        moveTo(12f, 3f); lineTo(12f, 21f)
        moveTo(18f, 3f); lineTo(18f, 21f)
    }
    // Fader knobs at different heights.
    path(fill = SolidColor(Color.Black)) {
        moveTo(3.5f, 13f); lineTo(8.5f, 13f); lineTo(8.5f, 17f); lineTo(3.5f, 17f); close()
        moveTo(9.5f, 5f); lineTo(14.5f, 5f); lineTo(14.5f, 9f); lineTo(9.5f, 9f); close()
        moveTo(15.5f, 10f); lineTo(20.5f, 10f); lineTo(20.5f, 14f); lineTo(15.5f, 14f); close()
    }
}.build()

/** Bars of different heights, like a real-time analyzer, for the Live destination. */
val LiveIcon: ImageVector = ImageVector.Builder(
    name = "Live",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.5f, strokeLineCap = StrokeCap.Round) {
        moveTo(4f, 20f); lineTo(4f, 14f)
        moveTo(8f, 20f); lineTo(8f, 7f)
        moveTo(12f, 20f); lineTo(12f, 11f)
        moveTo(16f, 20f); lineTo(16f, 4f)
        moveTo(20f, 20f); lineTo(20f, 13f)
    }
}.build()

/** Two bars (pause); not part of the core icon set. */
val PauseIcon: ImageVector = ImageVector.Builder(
    name = "Pause",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
        moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
    }
}.build()
