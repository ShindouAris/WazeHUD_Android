package com.chisadin.hudwz.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = HudCyan,
    onPrimary = HudBlack,
    secondary = HudBlue,
    tertiary = HudAmber,
    error = HudRed,
    background = HudBlack,
    onBackground = HudText,
    surface = HudSurface,
    onSurface = HudText,
    surfaceVariant = HudSurfaceHigh,
    onSurfaceVariant = HudMuted,
    outline = HudOutline,
)

private val LightColorScheme = lightColorScheme(
    primary = HudBlue,
    onPrimary = DaySurface,
    secondary = HudCyan,
    tertiary = HudAmber,
    error = HudRed,
    background = DayBackground,
    onBackground = DayText,
    surface = DaySurface,
    onSurface = DayText,
    surfaceVariant = Color(0xFFE7EDF3),
    onSurfaceVariant = Color(0xFF3E4C59),
    outline = Color(0xFF738397),
)

@Composable
fun HudwzTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
