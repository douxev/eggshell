package com.douxev.eggshell.reminders

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-medication notification alias, keyed by medication id.
 *
 * An alias is a *fake* label the user picks (e.g. "Vitamines" for an
 * estradiol prescription), so storing it in plain SharedPreferences leaks
 * nothing — that's the whole point. It lives outside the encrypted vault so
 * the reminder path can read it while locked, the same reasoning as
 * [ReminderPrefs]. Only consulted when [NotifContentPrefs.Mode.ALIAS] is on;
 * a medication with no alias falls back to the generic copy (never the real
 * name).
 */
@Singleton
class MedAliasPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        com.douxev.eggshell.data.SecurePrefs.get(context, PREFS_NAME)

    fun get(medicationId: Long): String? =
        prefs.getString(key(medicationId), null)?.takeIf { it.isNotBlank() }

    fun set(medicationId: Long, alias: String?) {
        prefs.edit().apply {
            if (alias.isNullOrBlank()) remove(key(medicationId))
            else putString(key(medicationId), alias.trim())
        }.apply()
    }

    private fun key(medicationId: Long) = "alias_$medicationId"

    companion object {
        private const val PREFS_NAME = "transition_med_alias_prefs"
    }
}
