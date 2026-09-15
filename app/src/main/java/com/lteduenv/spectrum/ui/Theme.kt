package com.lteduenv.spectrum.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AnalyzerColors {
    val Background = Color(0xFF0A0A12)
    val Panel = Color(0xFF15131F)
    val GridLine = Color(0xFF3A3555)
    val GridLineStrong = Color(0xFF534C7A)
    val Trace = Color(0xFFFFC24B)
    val TracePeak = Color(0xFFFF5C5C)
    val AccentBlue = Color(0xFF3B6FE0)
    val AccentBlueDim = Color(0xFF23315C)
    val TextPrimary = Color(0xFFE8E6F5)
    val TextSecondary = Color(0xFF9A96B5)
    val Good = Color(0xFF4CD97B)
    val Warn = Color(0xFFFFB020)
    val Bad = Color(0xFFFF5C5C)
}

private val analyzerScheme = darkColorScheme(
    background = AnalyzerColors.Background,
    surface = AnalyzerColors.Panel,
    primary = AnalyzerColors.AccentBlue,
    onBackground = AnalyzerColors.TextPrimary,
    onSurface = AnalyzerColors.TextPrimary,
)

@Composable
fun SpectrumCheckTheme(content: @Composable () -> Unit) {
    // The instrument UI it mirrors is always a dark trace-on-black display, so we don't
    // follow the system light/dark setting here.
    MaterialTheme(
        colorScheme = analyzerScheme,
        content = content,
    )
}
