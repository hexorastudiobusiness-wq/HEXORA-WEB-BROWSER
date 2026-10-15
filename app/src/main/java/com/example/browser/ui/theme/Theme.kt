package com.example.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

/** Material 3 theme driven by the persisted [ThemeMode] (ARCHITECTURE.md §16). */
@Composable
fun HexoraBrowserTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val useDarkColors = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (useDarkColors) DarkColors else LightColors,
        content = content,
    )
}
