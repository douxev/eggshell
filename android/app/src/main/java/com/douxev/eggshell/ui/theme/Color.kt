package com.douxev.eggshell.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Material 3 "expressive" lavender palette ported from the design bundle's
 * `m3.css` token set. Light + dark variants kept 1:1 with the prototype so
 * the implementation matches what the designer signed off on.
 */

// ---- Light ----

val LavendeLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF6A4FA3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF250059),
    inversePrimary = Color(0xFFD4BBFF),

    secondary = Color(0xFF635B70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9DEF8),
    onSecondaryContainer = Color(0xFF1F182B),

    tertiary = Color(0xFF98455F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3E0721),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFDF7FF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFDF7FF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454E),

    surfaceTint = Color(0xFF6A4FA3),
    inverseSurface = Color(0xFF322F35),
    inverseOnSurface = Color(0xFFF5EFF6),

    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCBC4CF),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F1FB),
    surfaceContainer = Color(0xFFF2EBF6),
    surfaceContainerHigh = Color(0xFFECE5F0),
    surfaceContainerHighest = Color(0xFFE6DFEA),

    surfaceBright = Color(0xFFFDF7FF),
    surfaceDim = Color(0xFFDDD8E0),

    scrim = Color(0xFF000000),
)

// ---- Dark ----

val LavendeDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFD4BBFF),
    onPrimary = Color(0xFF3B1C71),
    primaryContainer = Color(0xFF523689),
    onPrimaryContainer = Color(0xFFEBDDFF),
    inversePrimary = Color(0xFF6A4FA3),

    secondary = Color(0xFFCDC2DB),
    onSecondary = Color(0xFF342D41),
    secondaryContainer = Color(0xFF4B4358),
    onSecondaryContainer = Color(0xFFE9DEF8),

    tertiary = Color(0xFFFFB1C7),
    onTertiary = Color(0xFF5E1133),
    tertiaryContainer = Color(0xFF7B2949),
    onTertiaryContainer = Color(0xFFFFD9E2),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCAC4CF),

    surfaceTint = Color(0xFFD4BBFF),
    inverseSurface = Color(0xFFE6E0E9),
    inverseOnSurface = Color(0xFF322F35),

    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454E),

    surfaceContainerLowest = Color(0xFF0E0D13),
    surfaceContainerLow = Color(0xFF1C1B20),
    surfaceContainer = Color(0xFF211F25),
    surfaceContainerHigh = Color(0xFF2B292F),
    surfaceContainerHighest = Color(0xFF36343A),

    surfaceBright = Color(0xFF3B383E),
    surfaceDim = Color(0xFF141218),

    scrim = Color(0xFF000000),
)
