package com.douxev.eggshell.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3 "expressive" type scale ported from the design's `m3.css`.
 * Each named slot in Compose's [Typography] maps to a CSS .t-* class in the
 * prototype:
 *
 * - displayLarge  ← .t-display-l   (52/60, 400, -.25)
 * - displayMedium ← .t-display-s   (34/42, 500)
 * - headlineLarge ← .t-headline-l  (30/38, 400)
 * - headlineMedium← .t-headline    (26/34, 500)
 * - headlineSmall ← .t-headline-s  (22/28, 500)
 * - titleLarge    ← .t-title-l     (20/26, 500)
 * - titleMedium   ← .t-title       (17/24, 600, +.1)
 * - titleSmall    ← .t-title-s     (15/20, 600, +.1)
 * - bodyLarge     ← .t-body        (15/22, 400, +.15)
 * - bodyMedium    ← .t-body        (15/22, 400, +.15)
 * - bodySmall     ← .t-body-s      (13/18, 400, +.2)
 * - labelLarge    ← .t-label       (14/20, 600, +.1)
 * - labelMedium   ← .t-label       (14/20, 600, +.1)
 * - labelSmall    ← .t-label-s     (11/16, 600, +.5)
 */
val TransitionTypography = Typography(
    displayLarge = TextStyle(fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.W400, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.W500),
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.W400),

    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.W400),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.W500),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.W500),

    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.W500),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.W600, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.W600, letterSpacing = 0.1.sp),

    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.W400, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.W400, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W400, letterSpacing = 0.2.sp),

    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.W600, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.W600, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.W600, letterSpacing = 0.5.sp),
)
