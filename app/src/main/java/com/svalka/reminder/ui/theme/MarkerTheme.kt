package com.svalka.reminder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK;
    companion object { fun fromValue(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM }
}

data class MarkerPalette(
    val background: Color,
    val foreground: Color,
    val secondary: Color,
    val divider: Color,
    val accent: Color,
    val accentText: Color,
    val reminderText: Color,
    val sheet: Color
)

private val terminalDarkPalette = MarkerPalette(
    background = Color(0xFF101010), foreground = Color(0xFFE8E4D9),
    secondary = Color(0xFFA9A49B), divider = Color(0xFF34312F),
    accent = Color(0xFFF0443E), accentText = Color(0xFF101010),
    reminderText = Color(0xFFF26B65), sheet = Color(0xFF1A1918)
)

private val terminalLightPalette = MarkerPalette(
    background = Color(0xFFF5F2EB), foreground = Color(0xFF181716),
    secondary = Color(0xFF665F59), divider = Color(0xFFD8D2C9),
    accent = Color(0xFFCF332D), accentText = Color(0xFFFFFFFF),
    reminderText = Color(0xFFB92B26), sheet = Color(0xFFFFFDF8)
)

private val LocalMarkerPalette = staticCompositionLocalOf { terminalDarkPalette }

@Composable
fun markerColors(): MarkerPalette = LocalMarkerPalette.current

@Composable
fun SvalkaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val palette = if (dark) terminalDarkPalette else terminalLightPalette
    val scheme = if (dark) darkColorScheme(
        primary = palette.accent, onPrimary = palette.accentText,
        background = palette.background, onBackground = palette.foreground,
        surface = palette.sheet, onSurface = palette.foreground,
        outline = palette.divider
    ) else lightColorScheme(
        primary = palette.accentText, onPrimary = palette.accent,
        background = palette.background, onBackground = palette.foreground,
        surface = palette.sheet, onSurface = palette.foreground,
        outline = palette.divider
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalMarkerPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = SvalkaTypography, content = content)
    }
}
