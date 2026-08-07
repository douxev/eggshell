package com.douxev.eggshell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import com.douxev.eggshell.data.ThemePrefs

/**
 * Applies the user-selected palette + the expressive type scale to the app.
 *
 * The active palette is read from [ThemePrefs] via [ThemeViewModel] so the
 * theme switches live the moment the user picks a new one from Réglages.
 * When the user keeps the default ([AppTheme.SYSTEM]) we fall back to the
 * lavender palette and let the OS dark-mode setting choose between light
 * and dark.
 *
 * Material 3 has no `success` role, so the palette's green travels alongside
 * the scheme in [LocalEggExtendedColors] — that is what keeps the Évolution
 * tiles and the success chips reactive to all 15 palettes.
 */
@Composable
fun EggshellTheme(content: @Composable () -> Unit) {
    val vm: ThemeViewModel = hiltViewModel()
    val selected by vm.theme.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val scheme = resolveScheme(selected, systemInDark = systemDark)
    CompositionLocalProvider(
        LocalEggExtendedColors provides extendedColorsFor(selected, systemInDark = systemDark),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TransitionTypography,
        ) {
            // Inside MaterialTheme so it reads the scheme that was just applied:
            // the status- and navigation-bar icons have to flip polarity with
            // the palette, and the palette is what this composable exists to
            // choose. Replaces the appearance handling androidx's
            // enableEdgeToEdge() used to do for us.
            SyncSystemBarIcons()
            content()
        }
    }
}

@HiltViewModel
class ThemeViewModel @Inject constructor(prefs: ThemePrefs) : ViewModel() {
    val theme: StateFlow<AppTheme> = prefs.theme
}
