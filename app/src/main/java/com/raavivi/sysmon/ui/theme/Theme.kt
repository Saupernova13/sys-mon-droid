package com.raavivi.sysmon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = BgDark,
    secondary = Sky,
    tertiary = Violet,
    background = BgDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceMuted,
    outline = OutlineDark,
    error = Coral,
)

// A light scheme is provided for completeness, but the app defaults to dark to
// match the dashboard's intent.
private val LightColors = lightColorScheme(
    primary = MintDim,
    secondary = Sky,
    tertiary = Violet,
    error = Coral,
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
    ),
)

// Instrument: the sys-mon web panel's neutral ramp. Interaction is brightness,
// never hue (DESIGN.md rule 2), so primary is a light neutral.
private val InstrumentColors = darkColorScheme(
    primary = N7,
    onPrimary = N0,
    primaryContainer = N3,
    onPrimaryContainer = N9,
    secondary = N6,
    tertiary = InstrumentPalette.idCpu,
    background = N0,
    onBackground = N8,
    surface = N1,
    onSurface = N8,
    surfaceVariant = N2,
    onSurfaceVariant = N7,
    surfaceContainerLowest = N1,
    surfaceContainerLow = N1,
    surfaceContainer = N1,
    surfaceContainerHigh = N2,
    surfaceContainerHighest = N2,
    outline = N4,
    outlineVariant = N3,
    error = InstCrit,
    onError = N0,
)

private val InstrumentShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)

private val InstrumentTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.07.em,
    ),
)

@Composable
fun SysMonTheme(
    style: ThemeStyle = ThemeStyle.Classic,
    darkTheme: Boolean = true || isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val instrument = style == ThemeStyle.Instrument
    CompositionLocalProvider(
        LocalSysMonPalette provides if (instrument) InstrumentPalette else ClassicPalette,
    ) {
        MaterialTheme(
            colorScheme = when {
                instrument -> InstrumentColors
                darkTheme -> DarkColors
                else -> LightColors
            },
            typography = if (instrument) InstrumentTypography else AppTypography,
            shapes = if (instrument) InstrumentShapes else Shapes(),
            content = content,
        )
    }
}
