package io.github.guilhermefeitosa66.openpdfviewer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** libre-apps palette (site-libre-apps-org/brand). Terracotta for shapes, rust for text. */
@Immutable
data class ReaderColors(
    val gutter: Color,
    val highlight: Color,
    val highlightCurrent: Color,
    val highlightCurrentRing: Color,
    val selection: Color,
    val handle: Color,
    val pill: Color,
    val onPill: Color,
    val scrollThumb: Color,
)

private val LightReader = ReaderColors(
    gutter = Color(0xFFE7DAC4),
    highlight = Color(0xFFF5D7A1),
    highlightCurrent = Color(0xFFF0A46E),
    highlightCurrentRing = Color(0xFFB5502B),
    selection = Color(0x38B5502B),
    handle = Color(0xFFB5502B),
    pill = Color(0xFF2A1A12),
    onPill = Color(0xFFF7EEDF),
    scrollThumb = Color(0xD9B5502B),
)

private val DarkReader = ReaderColors(
    gutter = Color(0xFF15100D),
    highlight = Color(0xFFF5D7A1),
    highlightCurrent = Color(0xFFF0A46E),
    highlightCurrentRing = Color(0xFFD9973A),
    selection = Color(0x59D9973A),
    handle = Color(0xFFD9973A),
    pill = Color(0xFFF3E7D8),
    onPill = Color(0xFF1E1612),
    scrollThumb = Color(0xD9D9973A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFB5502B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF5D7A1),
    onPrimaryContainer = Color(0xFF3E2418),
    secondary = Color(0xFF8E3A1E),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFD9973A),
    background = Color(0xFFF7EEDF),
    onBackground = Color(0xFF2A1A12),
    surface = Color(0xFFF7EEDF),
    onSurface = Color(0xFF2A1A12),
    surfaceVariant = Color(0xFFEFE2CC),
    onSurfaceVariant = Color(0xFF6B5242),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF7EE),
    surfaceContainer = Color(0xFFFCF7EE),
    surfaceContainerHigh = Color(0xFFF7EEDF),
    surfaceContainerHighest = Color(0xFFEFE2CC),
    outline = Color(0xFF9C8370),
    outlineVariant = Color(0xFFE2D2B9),
    inverseSurface = Color(0xFF2A1A12),
    inverseOnSurface = Color(0xFFF7EEDF),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFD9973A),
    onPrimary = Color(0xFF1E1612),
    primaryContainer = Color(0xFF3E2418),
    onPrimaryContainer = Color(0xFFF5D7A1),
    secondary = Color(0xFFE8B062),
    onSecondary = Color(0xFF1E1612),
    tertiary = Color(0xFFE57A36),
    background = Color(0xFF1E1612),
    onBackground = Color(0xFFF3E7D8),
    surface = Color(0xFF1E1612),
    onSurface = Color(0xFFF3E7D8),
    surfaceVariant = Color(0xFF2A1F19),
    onSurfaceVariant = Color(0xFFBBA58F),
    surfaceContainerLowest = Color(0xFF15100D),
    surfaceContainerLow = Color(0xFF251B16),
    surfaceContainer = Color(0xFF2A1F19),
    surfaceContainerHigh = Color(0xFF2A1F19),
    surfaceContainerHighest = Color(0xFF332620),
    outline = Color(0xFF8A7563),
    outlineVariant = Color(0xFF3E2E24),
    inverseSurface = Color(0xFFF3E7D8),
    inverseOnSurface = Color(0xFF1E1612),
)

val LocalReaderColors = staticCompositionLocalOf { LightReader }

@Composable
fun OpenPdfTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalReaderColors provides if (dark) DarkReader else LightReader,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
