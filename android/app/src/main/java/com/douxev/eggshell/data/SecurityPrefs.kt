package com.douxev.eggshell.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Misc. on-device privacy / security toggles that don't belong in the vault
 * (which is unlocked) and don't belong in [com.douxev.eggshell.security.VaultPrefs]
 * (which is about KDF + decoy material).
 *
 * Right now this is just the "block screenshots and recents preview" flag —
 * stored in plain SharedPreferences and exposed as a StateFlow so MainActivity
 * can react in real time the moment the user toggles it.
 */
@Singleton
class SecurityPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        SecurePrefs.get(context, PREFS_NAME)

    // Default ON: the recents thumbnail, screenshots, casting and screen
    // recording all expose med names, hormone values, journal entries by
    // default. Users who want to take a screenshot for a doctor / friend
    // can opt out from Réglages → Confidentialité.
    private val _blockScreenshots = MutableStateFlow(prefs.getBoolean(KEY_BLOCK_SHOTS, true))
    val blockScreenshots: StateFlow<Boolean> = _blockScreenshots.asStateFlow()

    fun setBlockScreenshots(value: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_SHOTS, value).apply()
        _blockScreenshots.value = value
    }

    companion object {
        private const val PREFS_NAME = "transition_security_prefs"
        private const val KEY_BLOCK_SHOTS = "block_screenshots"
    }
}
