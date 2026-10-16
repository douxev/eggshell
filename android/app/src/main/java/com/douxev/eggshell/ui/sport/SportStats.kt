package com.douxev.eggshell.ui.sport

import java.time.LocalDate
import java.time.ZoneId
import uniffi.transition.SportSession

/**
 * The arithmetic behind the Sport dashboard, as pure functions.
 *
 * Its own file and free of Android so the rules can be tested: a streak that is
 * silently one day out, or a week that counts a session twice, is the kind of
 * wrong that looks completely plausible on screen. These numbers are also the
 * ones people take personally — telling someone their streak broke when it did
 * not is worse than showing nothing.
 */
object SportStats {

    /**
     * Consecutive days up to and including today that have a session.
     *
     * **Today not having one yet does not break the streak.** A streak counted
     * strictly from today would read zero every morning until the person
     * trained, which is both wrong and discouraging at the exact moment it is
     * read. So the count starts from today if today has a session, and from
     * yesterday otherwise; only a gap before that ends it.
     */
    fun currentStreak(
        sessionDays: Set<LocalDate>,
        today: LocalDate,
    ): Int {
        var day = if (today in sessionDays) today else today.minusDays(1)
        var streak = 0
        while (day in sessionDays) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    /** The longest run of consecutive days ever recorded. */
    fun longestStreak(sessionDays: Set<LocalDate>): Int {
        if (sessionDays.isEmpty()) return 0
        var best = 0
        for (day in sessionDays) {
            // Only count from the start of a run, so each run is walked once
            // rather than once per day in it.
            if (day.minusDays(1) in sessionDays) continue
            var length = 0
            var cursor = day
            while (cursor in sessionDays) {
                length++
                cursor = cursor.plusDays(1)
            }
            if (length > best) best = length
        }
        return best
    }

    /** The local days on which any session started. */
    fun sessionDays(sessions: List<SportSession>, zone: ZoneId): Set<LocalDate> =
        sessions.map { localDate(it.startedMs, zone) }.toSet()

    fun localDate(atMs: Long, zone: ZoneId): LocalDate =
        java.time.Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

    /**
     * Total minutes trained in `[from, to)` — half-open, matching the core's
     * range query, so "this week" and "last week" never both count the session
     * that sits on the boundary.
     */
    fun minutesBetween(
        sessions: List<SportSession>,
        fromMs: Long,
        toMs: Long,
    ): Long = sessions
        .filter { it.startedMs >= fromMs && it.startedMs < toMs }
        .sumOf { it.durationS } / 60

    fun countBetween(
        sessions: List<SportSession>,
        fromMs: Long,
        toMs: Long,
    ): Int = sessions.count { it.startedMs >= fromMs && it.startedMs < toMs }
}

/**
 * The step calendar's numbers — what a pedometer like Stepsy shows under its
 * month: a total, an average, the goal days, a streak, a comparison.
 *
 * Same discipline as [SportStats]: pure, Android-free, tested. And one rule of
 * its own — **a day with no record is not a day of zero steps.** The pedometer
 * may have been off, the phone in a drawer; averaging those in as zeros would
 * tell someone they walk less than they do. So averages run over the days that
 * have a count. A streak is different: it is a run of days that reached the
 * goal, and a day with no count did not.
 */
object StepStats {

    data class Month(
        val total: Long,
        /** Mean over the days that have a count; 0 when none do. */
        val average: Long,
        val daysAtGoal: Int,
        val best: Long,
        val daysRecorded: Int,
    )

    fun month(steps: Map<LocalDate, Long>, goal: Int): Month {
        val counted = steps.values.filter { it > 0 }
        return Month(
            total = counted.sum(),
            average = if (counted.isEmpty()) 0 else counted.sum() / counted.size,
            daysAtGoal = counted.count { goal > 0 && it >= goal },
            best = counted.maxOrNull() ?: 0,
            daysRecorded = counted.size,
        )
    }

    /**
     * Consecutive days at or above the goal, up to today.
     *
     * Today not there *yet* does not break it — the same rule as the session
     * streak, for the same reason: at 9 a.m. nobody has reached their goal, and
     * a counter that reads zero every morning is both wrong and discouraging.
     */
    fun goalStreak(steps: Map<LocalDate, Long>, goal: Int, today: LocalDate): Int {
        if (goal <= 0) return 0
        fun met(day: LocalDate) = (steps[day] ?: 0L) >= goal
        var day = if (met(today)) today else today.minusDays(1)
        var streak = 0
        while (met(day)) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    /** The longest run of goal days in [steps]. */
    fun bestGoalStreak(steps: Map<LocalDate, Long>, goal: Int): Int {
        if (goal <= 0) return 0
        val days = steps.filterValues { it >= goal }.keys
        return SportStats.longestStreak(days)
    }

    /**
     * How this month's average compares with last month's, in whole percent,
     * or null when there is nothing to compare with — "+∞ %" helps nobody.
     */
    fun change(current: Long, previous: Long): Int? {
        if (previous <= 0 || current <= 0) return null
        return Math.round((current - previous) * 100.0 / previous).toInt()
    }
}
