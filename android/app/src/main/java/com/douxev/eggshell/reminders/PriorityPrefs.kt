package com.douxev.eggshell.reminders

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-reminder "priority notification" flag.
 *
 * Priority ON  → notification uses the high-importance channel (heads-up,
 *                vibration, sound).
 * Priority OFF → notification uses the default-importance channel (silent,
 *                appears in the shade only).
 *
 * Med schedules (DB-backed) and lab reminders (plain prefs) live in separate
 * namespaces so their numeric IDs can overlap without collisions.
 */
@Singleton
class PriorityPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isMedPriority(scheduleId: Long): Boolean = prefs.getBoolean(medKey(scheduleId), false)
    fun setMedPriority(scheduleId: Long, priority: Boolean) {
        prefs.edit().putBoolean(medKey(scheduleId), priority).apply()
    }
    fun removeMed(scheduleId: Long) {
        prefs.edit().remove(medKey(scheduleId)).apply()
    }

    fun isLabPriority(labId: Long): Boolean = prefs.getBoolean(labKey(labId), false)
    fun setLabPriority(labId: Long, priority: Boolean) {
        prefs.edit().putBoolean(labKey(labId), priority).apply()
    }
    fun removeLab(labId: Long) {
        prefs.edit().remove(labKey(labId)).apply()
    }

    private fun medKey(id: Long) = "med_$id"
    private fun labKey(id: Long) = "lab_$id"

    companion object {
        private const val PREFS_NAME = "transition_reminder_priority"
    }
}
