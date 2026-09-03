package com.douxev.eggshell.ui.sport

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Streaks and weekly totals are numbers people take personally. Telling someone
 * their streak broke when it did not is worse than showing nothing, and an
 * off-by-one here looks entirely plausible on screen.
 */
class SportStatsTest {

    private val today = LocalDate.of(2026, 9, 3)

    private fun days(vararg offsets: Long) = offsets.map { today.minusDays(it) }.toSet()

    @Test
    fun `an empty history has no streak`() {
        assertEquals(0, SportStats.currentStreak(emptySet(), today))
        assertEquals(0, SportStats.longestStreak(emptySet()))
    }

    @Test
    fun `consecutive days ending today count`() {
        assertEquals(3, SportStats.currentStreak(days(0, 1, 2), today))
    }

    /**
     * The rule that matters most: not having trained *yet today* must not read
     * as a broken streak. Otherwise the number is zero every morning, at exactly
     * the moment someone opens the app to decide whether to go.
     */
    @Test
    fun `today being empty does not break a streak that ran until yesterday`() {
        assertEquals(2, SportStats.currentStreak(days(1, 2), today))
    }

    @Test
    fun `a gap before yesterday ends the streak`() {
        assertEquals(0, SportStats.currentStreak(days(2, 3, 4), today))
    }

    @Test
    fun `a day skipped in the middle splits the run`() {
        assertEquals(1, SportStats.currentStreak(days(0, 2, 3), today))
    }

    @Test
    fun `the longest streak is found anywhere in the history`() {
        // A four-day run a fortnight back, a two-day run just now.
        assertEquals(4, SportStats.longestStreak(days(0, 1, 14, 15, 16, 17)))
    }

    @Test
    fun `a single day is a streak of one`() {
        assertEquals(1, SportStats.currentStreak(days(0), today))
        assertEquals(1, SportStats.longestStreak(days(5)))
    }

    /** Half-open, matching the core: no session lands in two adjacent weeks. */
    @Test
    fun `week totals do not double-count the boundary session`() {
        val sessions = listOf(
            session(startedMs = 1_000, durationS = 600),
            session(startedMs = 2_000, durationS = 1_200),
        )
        assertEquals(10, SportStats.minutesBetween(sessions, 1_000, 2_000))
        assertEquals(20, SportStats.minutesBetween(sessions, 2_000, 3_000))
        assertEquals(1, SportStats.countBetween(sessions, 1_000, 2_000))
    }

    private fun session(startedMs: Long, durationS: Long) = uniffi.transition.SportSession(
        id = startedMs,
        activityId = null,
        startedMs = startedMs,
        durationS = durationS,
        freeText = null,
        distanceM = null,
        avgHr = null,
        maxHr = null,
        source = "manual",
    )
}
