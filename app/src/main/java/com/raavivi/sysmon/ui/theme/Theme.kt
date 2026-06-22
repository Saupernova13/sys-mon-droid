package com.raavivi.sysmon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
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

@Composable
fun SysMonTheme(
    darkTheme: Boolean = true || isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
