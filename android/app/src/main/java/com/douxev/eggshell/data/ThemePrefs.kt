package com.douxev.eggshell.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.douxev.eggshell.ui.theme.AppTheme

/**
 * Persists the user-selected colour theme.
 *
 * Stored in plain SharedPreferences — a colour scheme is a presentation
 * preference, not data, so it doesn't belong in the encrypted vault.
 * Exposed as a StateFlow so the root composable picks up theme changes
 * without an app restart.
 */
@Singleton
class ThemePrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(AppTheme.fromId(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<AppTheme> = _theme.asStateFlow()

    fun set(theme: AppTheme) {
        prefs.edit().putString(KEY_THEME, theme.id).apply()
        _theme.value = theme
    }

    companion object {
        private const val PREFS_NAME = "transition_theme_prefs"
        private const val KEY_THEME = "theme_id"
    }
}
