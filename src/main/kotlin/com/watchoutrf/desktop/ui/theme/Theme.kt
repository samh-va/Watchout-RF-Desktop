package com.watchoutrf.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WatchoutRFColorScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = DeepBlack,
    primaryContainer = Color(0xFF003D22),
    onPrimaryContainer = NeonGreen,

    secondary = CyanBright,
    onSecondary = DeepBlack,
    secondaryContainer = Color(0xFF003D4D),
    onSecondaryContainer = CyanBright,

    tertiary = AmberYellow,
    onTertiary = DeepBlack,

    background = DeepBlack,
    onBackground = TextPrimary,

    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,

    error = ErrorRed,
    onError = DeepBlack,

    outline = GridLine,
    outlineVariant = Color(0xFF1A1A2A),
)

@Composable
fun WatchoutRFTheme(content: @Composable () -> Unit) {
    val colorScheme = WatchoutRFColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = WatchoutRFTypography,
        content = content,
    )
}
