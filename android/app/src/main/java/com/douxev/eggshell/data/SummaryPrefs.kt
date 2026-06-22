package com.douxev.eggshell.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the auto-summary compares week-over-week or month-over-month. */
enum class SummaryPeriod { WEEK, MONTH }

/**
 * Stores the user's chosen summary cadence (week vs month). Defaults to month,
 * which is the more stable, less-noisy comparison for mood/adherence trends.
 */
@Singleton
class SummaryPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = SecurePrefs.get(context, PREFS_NAME)

    private val _period = MutableStateFlow(
        runCatching { SummaryPeriod.valueOf(prefs.getString(KEY_PERIOD, null) ?: DEFAULT.name) }
            .getOrDefault(DEFAULT)
    )
    val period: StateFlow<SummaryPeriod> = _period.asStateFlow()

    fun setPeriod(period: SummaryPeriod) {
        prefs.edit().putString(KEY_PERIOD, period.name).apply()
        _period.value = period
    }

    companion object {
        private const val PREFS_NAME = "transition_summary"
        private const val KEY_PERIOD = "period"
        private val DEFAULT = SummaryPeriod.MONTH
    }
}
