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
