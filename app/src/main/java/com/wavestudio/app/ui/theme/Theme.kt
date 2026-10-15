package com.wavestudio.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

data class WaveStudioColors(
    val trackA: Color = Color(0xFF4A90E2),
    val trackB: Color = Color(0xFFE27A4A),
    val trackC: Color = Color(0xFF9A4AE2),
    val trackD: Color = Color(0xFF4AE299),
    val clipBg: Color = Color(0xFF3D6DD3),
    val playhead: Color = Color(0xFFFF4A4A),
    val grid: Color = Color(0xFF2A2F38),
    val waveform: Color = Color(0xFFB8C5D6),
    val waveformPeak: Color = Color(0xFFE8EEF7),
    val record: Color = Color(0xFFE53935),
    val meterGood: Color = Color(0xFF43E97B),
    val meterWarn: Color = Color(0xFFFFC107),
    val meterBad: Color = Color(0xFFE53935),
    val material: ColorScheme = darkColorScheme()
)

val LocalWaveColors = compositionLocalOf { WaveStudioColors() }

object WaveStudioTheme {
    val colors: WaveStudioColors
        @Composable get() = LocalWaveColors.current
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5DA9FF),
    onPrimary = Color(0xFF001A33),
    primaryContainer = Color(0xFF003F73),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFFFB4A2),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D),
    outlineVariant = Color(0xFF21262D),
    error = Color(0xFFFF6B6B),
    tertiary = Color(0xFF43E97B)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF005DCC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFB23B1C),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1A1E23),
    surface = Color.White,
    onSurface = Color(0xFF1A1E23),
    surfaceVariant = Color(0xFFE8EDF5),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFC9D1D9),
    error = Color(0xFFBA1A1A),
    tertiary = Color(0xFF1F9D55)
)

@Composable
fun WaveStudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    accentColor: Color = Color(0xFF5DA9FF),
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // On a light background a pale accent has no contrast, so it is darkened there.
    val accent = if (darkTheme) accentColor else androidx.compose.ui.graphics.lerp(accentColor, Color.Black, 0.35f)
    val colorScheme = when {
        useDynamic -> if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)   // wallpaper colours win
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }.let { base ->
        if (useDynamic) base
        else base.copy(primary = accent, onPrimary = if (accent.luminance() > 0.45f) Color.Black else Color.White)
    }

    val waveColors = WaveStudioColors(material = colorScheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as Activity).window
            WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalWaveColors provides waveColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            shapes = Shapes(),
            content = content
        )
    }
}
