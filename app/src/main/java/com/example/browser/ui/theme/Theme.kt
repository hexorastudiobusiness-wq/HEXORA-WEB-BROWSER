package com.example.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.text.font.FontFamily
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

// Blue carries the interface and amber is the accent, in the same proportion
// the logo uses: one blue mark, two amber notes.
private val HexoraColors = darkColorScheme(
    primary = HexoraBlue,
    onPrimary = HexoraOnAccent,
    primaryContainer = HexoraBlueContainer,
    onPrimaryContainer = HexoraOnBlueContainer,
    secondary = HexoraAmber,
    onSecondary = HexoraOnAccent,
    secondaryContainer = HexoraAmberContainer,
    onSecondaryContainer = HexoraOnAmberContainer,
    tertiary = HexoraAmber,
    onTertiary = HexoraOnAccent,
    background = HexoraInk,
    onBackground = HexoraOnSurface,
    surface = HexoraSurface,
    onSurface = HexoraOnSurface,
    surfaceVariant = HexoraSurfaceVariant,
    onSurfaceVariant = HexoraMuted,
    outline = HexoraOutline,
    surfaceTint = HexoraBlue,
    surfaceDim = HexoraInk,
    surfaceBright = HexoraSurfaceVariant,
    surfaceContainerLowest = HexoraInk,
    surfaceContainerLow = HexoraSurface,
    surfaceContainer = HexoraSurfaceVariant,
    surfaceContainerHigh = HexoraSurfaceHigh,
    surfaceContainerHighest = HexoraSurfaceHighest,
    inverseSurface = HexoraOnSurface,
    inverseOnSurface = HexoraInk,
    inversePrimary = HexoraBlueContainer,
    scrim = Color.Black,
    error = HexoraErrorDark,
)

private val PaperColors = lightColorScheme(
    primary = PaperPrimary,
    onPrimary = PaperSheet,
    primaryContainer = PaperContainer,
    onPrimaryContainer = PaperInk,
    secondary = PaperInkMuted,
    onSecondary = PaperSheet,
    secondaryContainer = PaperSheetHigh,
    onSecondaryContainer = PaperInk,
    tertiary = PaperInkMuted,
    onTertiary = PaperSheet,
    background = PaperBackground,
    onBackground = PaperInk,
    surface = PaperSheet,
    onSurface = PaperInk,
    surfaceVariant = PaperSheetHigh,
    onSurfaceVariant = PaperInkMuted,
    outline = PaperOutline,
    surfaceTint = PaperPrimary,
    surfaceDim = PaperDim,
    surfaceBright = PaperBright,
    surfaceContainerLowest = PaperBright,
    surfaceContainerLow = PaperSheetLow,
    surfaceContainer = PaperSheetLow,
    surfaceContainerHigh = PaperSheetHigh,
    surfaceContainerHighest = PaperSheetHighest,
    inverseSurface = PaperInk,
    inverseOnSurface = PaperSheet,
    inversePrimary = PaperContainer,
    scrim = PaperInk,
    error = PaperError,
)

private val AuroraColors = darkColorScheme(
    primary = AuroraMint,
    onPrimary = AuroraOnMint,
    primaryContainer = AuroraMintContainer,
    onPrimaryContainer = AuroraOnMintContainer,
    secondary = AuroraViolet,
    onSecondary = AuroraOnViolet,
    secondaryContainer = AuroraVioletContainer,
    onSecondaryContainer = AuroraOnVioletContainer,
    tertiary = AuroraViolet,
    onTertiary = AuroraOnViolet,
    background = AuroraBackground,
    onBackground = AuroraOnSurface,
    surface = AuroraSurface,
    onSurface = AuroraOnSurface,
    surfaceVariant = AuroraSurfaceVariant,
    onSurfaceVariant = AuroraMuted,
    outline = AuroraOutline,
    surfaceTint = AuroraMint,
    surfaceDim = AuroraBackground,
    surfaceBright = AuroraSurfaceVariant,
    surfaceContainerLowest = AuroraBackground,
    surfaceContainerLow = AuroraSurface,
    surfaceContainer = AuroraSurfaceVariant,
    surfaceContainerHigh = AuroraSurfaceHigh,
    surfaceContainerHighest = AuroraSurfaceHighest,
    inverseSurface = AuroraOnSurface,
    inverseOnSurface = AuroraBackground,
    inversePrimary = AuroraMintContainer,
    scrim = Color.Black,
    error = HexoraErrorDark,
)

/**
 * Paper is a page, so it is set in a serif — the one thing that makes text
 * look printed rather than typed into an app. The rest of the themes keep the
 * platform sans, which is what every other screen in the app was laid out for.
 */
private val DefaultTypography: Typography = Typography()

private val PaperTypography: Typography = DefaultTypography.let { base ->
    val serif = FontFamily.Serif
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = serif),
        displayMedium = base.displayMedium.copy(fontFamily = serif),
        displaySmall = base.displaySmall.copy(fontFamily = serif),
        headlineLarge = base.headlineLarge.copy(fontFamily = serif),
        headlineMedium = base.headlineMedium.copy(fontFamily = serif),
        headlineSmall = base.headlineSmall.copy(fontFamily = serif),
        titleLarge = base.titleLarge.copy(fontFamily = serif),
        titleMedium = base.titleMedium.copy(fontFamily = serif),
        titleSmall = base.titleSmall.copy(fontFamily = serif),
        bodyLarge = base.bodyLarge.copy(fontFamily = serif),
        bodyMedium = base.bodyMedium.copy(fontFamily = serif),
        bodySmall = base.bodySmall.copy(fontFamily = serif),
        // Labels stay sans: they are chrome (tab counters, buttons, top bars),
        // and a serif label at 11 sp is the one place this theme loses
        // legibility without gaining anything.
        labelLarge = base.labelLarge,
        labelMedium = base.labelMedium,
        labelSmall = base.labelSmall,
    )
}

/**
 * True while the paper theme is on. Screens use it for the one thing a colour
 * scheme cannot express: the logo has to be printed in ink too, or a sheet of
 * paper would carry a bright blue and amber tile.
 */
val LocalPaperTheme = staticCompositionLocalOf { false }

/** Fully desaturated: ink, not the brand blue and amber. */
private val InkFilter: ColorFilter =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Colour filter for the logo: [InkFilter] under the paper theme, nothing
 * anywhere else. Null means "draw it as it is", which is what every other
 * theme wants.
 */
@Composable
fun logoColorFilter(): ColorFilter? = if (LocalPaperTheme.current) InkFilter else null

/** Material 3 theme driven by the persisted [ThemeMode] (ARCHITECTURE.md §16). */
@Composable
fun HexoraBrowserTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val paper = themeMode == ThemeMode.PAPER
    val colors = when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) DarkColors else LightColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.HEXORA -> HexoraColors
        ThemeMode.PAPER -> PaperColors
        ThemeMode.AURORA -> AuroraColors
    }
    val typography = if (paper) PaperTypography else DefaultTypography
    CompositionLocalProvider(LocalPaperTheme provides paper) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            content = content,
        )
    }
}
