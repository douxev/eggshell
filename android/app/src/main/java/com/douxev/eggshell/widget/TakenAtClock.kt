package com.douxev.eggshell.widget

import java.util.Calendar
import java.util.TimeZone

/**
 * Turns "I took it at 8h30" into an instant.
 *
 * Its own file, and free of Android, because the rule it encodes is easy to get
 * wrong and impossible to notice when it is: an off-by-one-day timestamp is
 * still a plausible-looking dose in the history, and the punctuality statistics
 * the dose log exists for would just be quietly false.
 */
object TakenAtClock {

    /**
     * The most recent moment that was [hour]:[minute] local time, at or before
     * [nowMs].
     *
     * Today when that time has already passed, yesterday otherwise. The second
     * case is not an edge case: it is someone at 00h30 recording the dose they
     * took at 23h30, which is exactly when a person catches up on a dose they
     * forgot to log. Reading it as *today* would place it 23 hours in the
     * future — a dose that had not happened yet.
     *
     * [zone] is a parameter only so the tests can pin one; callers pass the
     * device default, because the user means their own wall clock.
     */
    fun mostRecentOccurrence(
        hour: Int,
        minute: Int,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Strictly greater: a dose logged at exactly the current minute is now,
        // not yesterday.
        if (cal.timeInMillis > nowMs) cal.add(Calendar.DAY_OF_MONTH, -1)
        return cal.timeInMillis
    }
}
