package com.douxev.eggshell.widget

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's « Pris à… » resolves an hour to an instant, and a mistake there
 * is silent: an off-by-one-day dose still looks like a dose, and only the
 * punctuality history — the thing the log exists for — comes out wrong.
 */
class TakenAtClockTest {

    private val paris: TimeZone = TimeZone.getTimeZone("Europe/Paris")

    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int,
    ): Long = Calendar.getInstance(paris).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    @Test
    fun `an hour earlier today resolves to today`() {
        val now = at(2026, 9, 3, 14, 0)
        assertEquals(at(2026, 9, 3, 8, 30), TakenAtClock.mostRecentOccurrence(8, 30, now, paris))
    }

    /**
     * The case the rule exists for: someone just past midnight recording the
     * dose they took before going to bed. Read as "today" it would sit 23 hours
     * in the future — a dose that has not happened.
     */
    @Test
    fun `an hour still ahead today resolves to yesterday`() {
        val now = at(2026, 9, 3, 0, 30)
        assertEquals(at(2026, 9, 2, 23, 30), TakenAtClock.mostRecentOccurrence(23, 30, now, paris))
    }

    @Test
    fun `the current minute is now, not yesterday`() {
        val now = at(2026, 9, 3, 14, 0)
        assertEquals(now, TakenAtClock.mostRecentOccurrence(14, 0, now, paris))
    }

    @Test
    fun `the result is never in the future`() {
        val now = at(2026, 9, 3, 9, 15)
        for (hour in 0..23) {
            for (minute in listOf(0, 30, 59)) {
                val resolved = TakenAtClock.mostRecentOccurrence(hour, minute, now, paris)
                assertTrue(
                    "$hour:$minute resolved after now",
                    resolved <= now,
                )
                assertTrue(
                    "$hour:$minute resolved more than a day back",
                    now - resolved < 24L * 60 * 60 * 1000,
                )
            }
        }
    }

    /**
     * The spring-forward morning. 02h30 does not exist in Paris on 2026-03-29,
     * and Calendar resolves it to 03h30 — the important part is that the answer
     * stays in the past rather than jumping a day either way.
     */
    @Test
    fun `a skipped hour on a DST change still lands in the past`() {
        val now = at(2026, 3, 29, 10, 0)
        val resolved = TakenAtClock.mostRecentOccurrence(2, 30, now, paris)
        assertTrue(resolved <= now)
        assertTrue(now - resolved < 24L * 60 * 60 * 1000)
    }
}
