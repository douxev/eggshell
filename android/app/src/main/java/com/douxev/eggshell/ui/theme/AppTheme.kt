package com.douxev.eggshell.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Catalogue of themes available from Réglages → Thème.
 *
 * Each entry resolves to a single [ColorScheme] — except [SYSTEM], which
 * follows the OS dark-mode setting and uses the lavender palette.
 *
 * The `accentSeed` is what the picker shows as a preview swatch, and the
 * background/surface come from the actual ColorScheme so the picker cells
 * look like a tiny screenshot of the theme.
 */
enum class AppTheme(
    val id: String,
    val displayName: String,
    val isDark: Boolean,
) {
    SYSTEM("system", "Système", isDark = false),
    LAVENDER_LIGHT("lavender_light", "Lavender", isDark = false),
    LAVENDER_DARK("lavender_dark", "Lavender (sombre)", isDark = true),
    CATPPUCCIN_LATTE("catppuccin_latte", "Catppuccin Latte", isDark = false),
    CATPPUCCIN_MOCHA("catppuccin_mocha", "Catppuccin Mocha", isDark = true),
    GRUVBOX_LIGHT("gruvbox_light", "Gruvbox", isDark = false),
    GRUVBOX_DARK("gruvbox_dark", "Gruvbox (sombre)", isDark = true),
    TOKYO_NIGHT("tokyo_night", "Tokyo Night", isDark = true),
    DRACULA("dracula", "Dracula", isDark = true),
    NORD("nord", "Nord", isDark = true),
    ROSE_PINE("rose_pine", "Rosé Pine", isDark = true),
    SOLARIZED_LIGHT("solarized_light", "Solarized", isDark = false),
    SOLARIZED_DARK("solarized_dark", "Solarized (sombre)", isDark = true),
    ONE_DARK("one_dark", "One Dark", isDark = true),
    MAYUKAI("mayukai_mirage", "Mayukai Mirage", isDark = true);

    companion object {
        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Resolves an [AppTheme] entry to the concrete [ColorScheme] to apply.
 *
 * For [AppTheme.SYSTEM], `systemInDark` decides whether to load the light
 * or dark lavender. For every other entry the result is deterministic.
 */
fun resolveScheme(theme: AppTheme, systemInDark: Boolean): ColorScheme = when (theme) {
    AppTheme.SYSTEM -> if (systemInDark) LavendeDark else LavendeLight
    AppTheme.LAVENDER_LIGHT -> LavendeLight
    AppTheme.LAVENDER_DARK -> LavendeDark
    AppTheme.CATPPUCCIN_LATTE -> CatppuccinLatte
    AppTheme.CATPPUCCIN_MOCHA -> CatppuccinMocha
    AppTheme.GRUVBOX_LIGHT -> GruvboxLight
    AppTheme.GRUVBOX_DARK -> GruvboxDark
    AppTheme.TOKYO_NIGHT -> TokyoNight
    AppTheme.DRACULA -> Dracula
    AppTheme.NORD -> Nord
    AppTheme.ROSE_PINE -> RosePine
    AppTheme.SOLARIZED_LIGHT -> SolarizedLight
    AppTheme.SOLARIZED_DARK -> SolarizedDark
    AppTheme.ONE_DARK -> OneDark
    AppTheme.MAYUKAI -> MayukaiMirage
}

/** Preview swatch (background + primary) used by the theme picker. */
fun themeSwatch(theme: AppTheme): Pair<Color, Color> {
    val scheme = resolveScheme(theme, systemInDark = false)
    return scheme.background to scheme.primary
}
