package com.example.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.browser.data.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = NeutralPrimary,
    onPrimary = Color.White,
    primaryContainer = NeutralContainer,
    onPrimaryContainer = NeutralPrimary,
    secondary = NeutralSecondary,
    onSecondary = Color.White,
    secondaryContainer = NeutralContainer,
    onSecondaryContainer = NeutralPrimary,
    background = Color.White,
    onBackground = NeutralPrimary,
    surface = Color.White,
    onSurface = NeutralPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = NeutralSecondary,
    outline = LightOutline,
    surfaceTint = NeutralPrimary,
    surfaceDim = LightOutline,
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = NeutralContainer,
    surfaceContainerHighest = LightOutline,
    inverseSurface = NeutralPrimary,
    inverseOnSurface = Color.White,
    inversePrimary = NeutralContainer,
    scrim = Color.Black,
    error = HexoraError,
)

private val DarkColors = darkColorScheme(
    primary = NeutralPrimaryDark,
    onPrimary = NeutralPrimary,
    primaryContainer = NeutralContainerDark,
    onPrimaryContainer = NeutralPrimaryDark,
    secondary = NeutralSecondaryDark,
    secondaryContainer = NeutralContainerDark,
    background = Color(0xFF121212),
    onBackground = NeutralPrimaryDark,
    surface = Color(0xFF1E1E1E),
    onSurface = NeutralPrimaryDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = NeutralSecondaryDark,
    outline = DarkOutline,
    surfaceTint = NeutralPrimaryDark,
    surfaceDim = Color(0xFF0E0E0E),
    surfaceBright = Color(0xFF303134),
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerLow = Color(0xFF1E1E1E),
    surfaceContainer = Color(0xFF262626),
    surfaceContainerHigh = DarkSurfaceVariant,
    surfaceContainerHighest = Color(0xFF3C4043),
    inverseSurface = NeutralPrimaryDark,
    inverseOnSurface = NeutralPrimary,
    inversePrimary = NeutralContainerDark,
    scrim = Color.Black,
    error = HexoraErrorDark,
)

private val HexoraColors = darkColorScheme(
    primary = HexoraOrange,
    onPrimary = HexoraInk,
    primaryContainer = HexoraOrangeDarkContainer,
    onPrimaryContainer = HexoraOrangeLight,
    secondary = HexoraOrangeLight,
    onSecondary = HexoraInk,
    secondaryContainer = HexoraInkSurfaceVariant,
    onSecondaryContainer = HexoraInkOnSurface,
    tertiary = HexoraOrangeLight,
    onTertiary = HexoraInk,
    background = HexoraInk,
    onBackground = HexoraInkOnSurface,
    surface = HexoraInkSurface,
    onSurface = HexoraInkOnSurface,
    surfaceVariant = HexoraInkSurfaceVariant,
    onSurfaceVariant = HexoraInkMuted,
    outline = HexoraInkOutline,
    surfaceTint = HexoraOrange,
    surfaceDim = HexoraInk,
    surfaceBright = HexoraInkSurfaceVariant,
    surfaceContainerLowest = HexoraInk,
    surfaceContainerLow = HexoraInkSurface,
    surfaceContainer = HexoraInkSurfaceVariant,
    surfaceContainerHigh = Color(0xFF38222B),
    surfaceContainerHighest = Color(0xFF472C33),
    inverseSurface = HexoraOrangeLight,
    inverseOnSurface = HexoraInk,
    inversePrimary = HexoraOrangeDarkContainer,
    scrim = Color.Black,
    error = HexoraErrorDark,
)

/** Material 3 theme driven by the persisted [ThemeMode] (ARCHITECTURE.md §16). */
@Composable
fun HexoraBrowserTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val colors = when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) DarkColors else LightColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.HEXORA -> HexoraColors
    }
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
