package com.douxev.eggshell.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * Design tokens that Material 3's [androidx.compose.material3.ColorScheme] has no
 * slot for, plus the shape and spacing scales of the refonte.
 *
 * The redesign forbids hard-coded hexadecimals in screens: everything is a token
 * so all 15 palettes stay reactive. `success` is the one colour role M3 doesn't
 * model, so it travels in its own [EggExtendedColors] provided by [EggshellTheme].
 */

@Immutable
data class EggExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

/** Lavender light — the reference values from the handoff (§3.1). */
private val LavenderLightExtended = EggExtendedColors(
    success = Color(0xFF3F6A3F),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFC2F0BF),
    onSuccessContainer = Color(0xFF0A2A0A),
)

private val LavenderDarkExtended = EggExtendedColors(
    success = Color(0xFFA6D3A0),
    onSuccess = Color(0xFF0C2A0C),
    successContainer = Color(0xFF265225),
    onSuccessContainer = Color(0xFFC2F0BF),
)

/**
 * Per-theme `success` role. Each palette keeps its own family's green (or, for
 * Rosé Pine which has none, its "foam" positive tone) so the Évolution tiles and
 * the success chips never look pasted in from another theme.
 */
fun extendedColorsFor(theme: AppTheme, systemInDark: Boolean): EggExtendedColors = when (theme) {
    AppTheme.SYSTEM -> if (systemInDark) LavenderDarkExtended else LavenderLightExtended
    AppTheme.LAVENDER_LIGHT -> LavenderLightExtended
    AppTheme.LAVENDER_DARK -> LavenderDarkExtended

    AppTheme.CATPPUCCIN_LATTE -> EggExtendedColors(
        success = Color(0xFF40A02B),
        onSuccess = Color(0xFFFFFFFF),
        successContainer = Color(0xFFCFF0C4),
        onSuccessContainer = Color(0xFF0E2A08),
    )
    AppTheme.CATPPUCCIN_MOCHA -> EggExtendedColors(
        success = Color(0xFFA6E3A1),
        onSuccess = Color(0xFF1E1E2E),
        successContainer = Color(0xFF2C4A32),
        onSuccessContainer = Color(0xFFA6E3A1),
    )
    AppTheme.GRUVBOX_LIGHT -> EggExtendedColors(
        success = Color(0xFF79740E),
        onSuccess = Color(0xFFFBF1C7),
        successContainer = Color(0xFFE1E0A6),
        onSuccessContainer = Color(0xFF252608),
    )
    AppTheme.GRUVBOX_DARK -> EggExtendedColors(
        success = Color(0xFFB8BB26),
        onSuccess = Color(0xFF282828),
        successContainer = Color(0xFF4A4C1A),
        onSuccessContainer = Color(0xFFB8BB26),
    )
    AppTheme.TOKYO_NIGHT -> EggExtendedColors(
        success = Color(0xFF9ECE6A),
        onSuccess = Color(0xFF1A1B26),
        successContainer = Color(0xFF33452A),
        onSuccessContainer = Color(0xFF9ECE6A),
    )
    AppTheme.DRACULA -> EggExtendedColors(
        success = Color(0xFF50FA7B),
        onSuccess = Color(0xFF282A36),
        successContainer = Color(0xFF2E4A38),
        onSuccessContainer = Color(0xFF50FA7B),
    )
    AppTheme.NORD -> EggExtendedColors(
        success = Color(0xFFA3BE8C),
        onSuccess = Color(0xFF2E3440),
        successContainer = Color(0xFF3F4A38),
        onSuccessContainer = Color(0xFFA3BE8C),
    )
    // Rosé Pine ships no green; "foam" is its positive tone.
    AppTheme.ROSE_PINE -> EggExtendedColors(
        success = Color(0xFF9CCFD8),
        onSuccess = Color(0xFF191724),
        successContainer = Color(0xFF2A3E42),
        onSuccessContainer = Color(0xFF9CCFD8),
    )
    AppTheme.SOLARIZED_LIGHT -> EggExtendedColors(
        success = Color(0xFF859900),
        onSuccess = Color(0xFFFDF6E3),
        successContainer = Color(0xFFE1E8C1),
        onSuccessContainer = Color(0xFF263400),
    )
    AppTheme.SOLARIZED_DARK -> EggExtendedColors(
        success = Color(0xFF859900),
        onSuccess = Color(0xFF002B36),
        successContainer = Color(0xFF1C3B1A),
        onSuccessContainer = Color(0xFFA6B93C),
    )
    AppTheme.ONE_DARK -> EggExtendedColors(
        success = Color(0xFF98C379),
        onSuccess = Color(0xFF282C34),
        successContainer = Color(0xFF35452C),
        onSuccessContainer = Color(0xFF98C379),
    )
    AppTheme.MAYUKAI -> EggExtendedColors(
        success = Color(0xFFA5D6A7),
        onSuccess = Color(0xFF1F2335),
        successContainer = Color(0xFF2E4033),
        onSuccessContainer = Color(0xFFA5D6A7),
    )
}

val LocalEggExtendedColors = staticCompositionLocalOf { LavenderLightExtended }

/**
 * The tokens screens are allowed to read directly. Anything not exposed here
 * must come from `MaterialTheme.colorScheme`.
 */
object EggColors {
    val success: Color
        @Composable @ReadOnlyComposable get() = LocalEggExtendedColors.current.success
    val onSuccess: Color
        @Composable @ReadOnlyComposable get() = LocalEggExtendedColors.current.onSuccess
    val successContainer: Color
        @Composable @ReadOnlyComposable get() = LocalEggExtendedColors.current.successContainer
    val onSuccessContainer: Color
        @Composable @ReadOnlyComposable get() = LocalEggExtendedColors.current.onSuccessContainer

    /**
     * Sage — the Évolution family's tile colour. Raw `successContainer` is too
     * saturated next to the lavender and the rose, so it's desaturated towards
     * the neutral of the same lightness (handoff §3.2).
     */
    val evolutionContainer: Color
        @Composable @ReadOnlyComposable get() = lerp(
            LocalEggExtendedColors.current.successContainer,
            MaterialTheme.colorScheme.surfaceContainerHighest,
            0.45f,
        )
    val onEvolutionContainer: Color
        @Composable @ReadOnlyComposable get() = LocalEggExtendedColors.current.onSuccessContainer

    /**
     * Neutral — the « Autres » family. The three named families each carry a
     * hue that means something about the data inside them; this one is a
     * drawer, so it deliberately carries none rather than inventing a fourth
     * accent the palette would then have to justify.
     */
    val otherContainer: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest
    val onOtherContainer: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    /** Grid lines, in every chart of the app and of the PDF (§5.1). */
    val chartGrid: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
}

/** Corner radii of the refonte (§3.4). */
object EggShapes {
    val Card = RoundedCornerShape(24.dp)
    val ListRow = RoundedCornerShape(28.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val LauncherTile = RoundedCornerShape(17.dp)
    val IconTile = RoundedCornerShape(12.dp)
    val SmallTile = RoundedCornerShape(14.dp)
    /** Rounded square, deliberately not a circle. */
    val Fab = RoundedCornerShape(18.dp)
    val Pill = RoundedCornerShape(100.dp)
    val Field = RoundedCornerShape(16.dp)
    val Note = RoundedCornerShape(18.dp)
}

/** The 8-grid of the refonte (§3.5). */
object EggDim {
    /** Screen side margin. */
    val ScreenMargin = 16.dp
    /** Default inner padding of a content card. */
    val CardPadding = 20.dp
    /** Vertical gap between two blocks of the home screen. */
    val BlockGap = 12.dp
    /**
     * A FAB or an action bar *reserves* its band, it never floats over content:
     * the scrollable area stops this far above the bottom of the screen.
     */
    val ActionBandHeight = 84.dp
    val TouchTarget = 44.dp
    /** Gap between two stacked [com.douxev.eggshell.ui.components.ListRow]s. */
    val RowGap = 10.dp
}
