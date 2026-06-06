package com.douxev.eggshell.reminders

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global "what does a medication reminder show?" setting.
 *
 * Privacy-first default is [Mode.GENERIC]: the notification reveals nothing
 * about which medication is due. Users can opt into showing the real name
 * ([Mode.NAME]) or a per-medication alias ([Mode.ALIAS], resolved from
 * [MedAliasPrefs]). Whatever the mode resolves to is mirrored — in plain
 * text — into [ReminderPrefs] so the reminder can render it while the vault
 * is locked. The real name therefore only ever lands in plain storage when
 * the user explicitly asks for it.
 *
 * Exposed as a [StateFlow] so the settings screen reacts live, mirroring the
 * [com.douxev.eggshell.data.SecurityPrefs] pattern.
 */
@Singleton
class NotifContentPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    enum class Mode {
        GENERIC, NAME, ALIAS;

        companion object {
            fun from(raw: String?): Mode = entries.firstOrNull { it.name == raw } ?: GENERIC
        }
    }

    private val prefs: SharedPreferences =
        com.douxev.eggshell.data.SecurePrefs.get(context, PREFS_NAME)

    private val _mode = MutableStateFlow(Mode.from(prefs.getString(KEY_MODE, null)))
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    /** Synchronous read for the alarm/notification path (no coroutine). */
    val current: Mode get() = _mode.value

    fun setMode(mode: Mode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    companion object {
        private const val PREFS_NAME = "transition_notif_content_prefs"
        private const val KEY_MODE = "mode"
    }
}
