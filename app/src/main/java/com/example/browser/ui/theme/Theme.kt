package com.example.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.browser.data.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = HexoraViolet,
    secondary = HexoraTeal,
    error = HexoraError,
)

private val DarkColors = darkColorScheme(
    primary = HexoraVioletLight,
    primaryContainer = HexoraVioletDarkContainer,
    secondary = HexoraTealDark,
    error = HexoraErrorDark,
)

private val GlassLightColors = lightColorScheme(
    primary = HexoraViolet,
    secondary = HexoraTeal,
    error = HexoraError,
    background = GlassLightBackground,
    surface = GlassLightSurface,
    surfaceVariant = GlassLightSurfaceVariant,
)

private val GlassDarkColors = darkColorScheme(
    primary = HexoraVioletLight,
    primaryContainer = HexoraVioletDarkContainer,
    secondary = HexoraTealDark,
    error = HexoraErrorDark,
    background = GlassDarkBackground,
    surface = GlassDarkSurface,
    surfaceVariant = GlassDarkSurfaceVariant,
)

/** True only while the Liquid Glass appearance is selected. */
val LocalLiquidGlass = staticCompositionLocalOf { false }

/** Material 3 theme driven by the persisted [ThemeMode] (ARCHITECTURE.md §16). */
@Composable
fun HexoraBrowserTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDarkColors = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        // Glass follows the system brightness for its base tint, while its
        // surfaces stay translucent in both variants.
        ThemeMode.GLASS -> systemDark
    }
    val colors = when (themeMode) {
        ThemeMode.GLASS -> if (useDarkColors) GlassDarkColors else GlassLightColors
        else -> if (useDarkColors) DarkColors else LightColors
    }
    CompositionLocalProvider(LocalLiquidGlass provides (themeMode == ThemeMode.GLASS)) {
        MaterialTheme(
            colorScheme = colors,
            content = content,
        )
    }
}
