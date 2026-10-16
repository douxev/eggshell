package com.douxev.eggshell.ui.sport

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The numbers under the step calendar. */
class StepStatsTest {

    private val today = LocalDate.of(2026, 10, 16)

    private fun steps(vararg pairs: Pair<Long, Long>) =
        pairs.associate { (offset, n) -> today.minusDays(offset) to n }

    /** A day the pedometer was off is not a day of zero steps. */
    @Test
    fun `the average ignores days with no count`() {
        val m = StepStats.month(steps(0L to 6_000, 1L to 0, 2L to 10_000), goal = 8_000)
        assertEquals(16_000L, m.total)
        assertEquals(8_000L, m.average)
        assertEquals(2, m.daysRecorded)
        assertEquals(1, m.daysAtGoal)
        assertEquals(10_000L, m.best)
    }

    @Test
    fun `an empty month is all zeros, not a division by zero`() {
        val m = StepStats.month(emptyMap(), goal = 8_000)
        assertEquals(0L, m.average)
        assertEquals(0, m.daysRecorded)
    }

    @Test
    fun `not at goal yet today does not break the streak`() {
        val s = steps(0L to 2_000, 1L to 9_000, 2L to 8_000, 3L to 100)
        assertEquals(2, StepStats.goalStreak(s, 8_000, today))
    }

    @Test
    fun `today at goal extends the streak`() {
        val s = steps(0L to 8_000, 1L to 9_000)
        assertEquals(2, StepStats.goalStreak(s, 8_000, today))
    }

    @Test
    fun `a day without a count ends the streak`() {
        val s = steps(1L to 9_000, 3L to 9_000, 4L to 9_000)
        assertEquals(1, StepStats.goalStreak(s, 8_000, today))
        assertEquals(2, StepStats.bestGoalStreak(s, 8_000))
    }

    @Test
    fun `month-on-month change is a rounded percentage, or nothing`() {
        assertEquals(10, StepStats.change(5_500, 5_000))
        assertEquals(-20, StepStats.change(4_000, 5_000))
        assertNull(StepStats.change(5_000, 0))
        assertNull(StepStats.change(0, 5_000))
    }
}
