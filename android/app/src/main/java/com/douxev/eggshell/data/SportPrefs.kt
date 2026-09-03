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
 * Settings inside the Sport module: whether the pedometer runs, and the daily
 * step goal.
 *
 * Separate from [FeaturesPrefs] because these are not "is this module on" —
 * someone can log sessions by hand and want nothing to do with the sensor, and
 * that has to be the default. Nothing here is sensitive on its own (a goal
 * number and a boolean); the steps themselves live in the vault.
 */
@Singleton
class SportPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = SecurePrefs.get(context, PREFS_NAME)

    /**
     * Off by default, and deliberately a second decision after enabling the
     * module: turning this on is what makes the app ask for
     * ACTIVITY_RECOGNITION, and that should follow an explicit "yes, count my
     * steps" rather than merely "yes, I do sport".
     */
    private val _pedometer = MutableStateFlow(prefs.getBoolean(KEY_PEDOMETER, false))
    val pedometer: StateFlow<Boolean> = _pedometer.asStateFlow()

    fun setPedometer(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PEDOMETER, enabled).apply()
        _pedometer.value = enabled
    }

    private val _dailyGoal = MutableStateFlow(prefs.getInt(KEY_GOAL, DEFAULT_GOAL))
    val dailyGoal: StateFlow<Int> = _dailyGoal.asStateFlow()

    fun setDailyGoal(steps: Int) {
        val clamped = steps.coerceIn(MIN_GOAL, MAX_GOAL)
        prefs.edit().putInt(KEY_GOAL, clamped).apply()
        _dailyGoal.value = clamped
    }

    companion object {
        /**
         * 8000, not the folklore 10000 — that number came from the brand name
         * of a 1960s Japanese pedometer, and the health research behind it tops
         * out well below. A goal someone never reaches is a goal that makes
         * them feel worse for having walked, which is the opposite of the point.
         */
        const val DEFAULT_GOAL = 8_000
        const val MIN_GOAL = 500
        const val MAX_GOAL = 50_000
        private const val PREFS_NAME = "transition_sport_prefs"
        private const val KEY_PEDOMETER = "pedometer"
        private const val KEY_GOAL = "daily_goal"
    }
}
