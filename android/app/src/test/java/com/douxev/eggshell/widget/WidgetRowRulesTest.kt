package com.douxev.eggshell.widget

import com.douxev.eggshell.R
import com.douxev.eggshell.ui.common.builtinLabelRes
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cycle day, the gauge names and the capped count are what someone reads
 * off the home screen at a glance, and each failed before in a way that still
 * looked plausible: "J1" all through a period, bars with no name, "500 photos"
 * for a library of two thousand.
 */
class WidgetRowRulesTest {

    private val today = LocalDate.of(2026, 10, 16)
    private fun daysAgo(n: Long) = today.minusDays(n)

    // -------------------------------------------------------------- cycleDay

    @Test
    fun `a period logged day by day counts from its first day, not its last`() {
        val period = (0L..3L).map(::daysAgo) // Oct 13..16, logged today too
        assertEquals(4, WidgetRowRules.cycleDay(period, today))
    }

    @Test
    fun `after the period ends the count keeps going from its first day`() {
        val period = (10L..14L).map(::daysAgo) // Oct 2..6
        assertEquals(15, WidgetRowRules.cycleDay(period, today))
    }

    @Test
    fun `one unlogged day inside a period does not restart it`() {
        val period = listOf(daysAgo(4), daysAgo(3), daysAgo(1), daysAgo(0))
        assertEquals(5, WidgetRowRules.cycleDay(period, today))
    }

    @Test
    fun `a real gap starts a new bleed`() {
        val previous = (30L..34L).map(::daysAgo)
        val latest = listOf(daysAgo(2), daysAgo(1))
        assertEquals(3, WidgetRowRules.cycleDay(previous + latest, today))
    }

    @Test
    fun `several entries on one day count once`() {
        val days = listOf(daysAgo(1), daysAgo(1), daysAgo(0), daysAgo(0))
        assertEquals(2, WidgetRowRules.cycleDay(days, today))
    }

    @Test
    fun `days after today are ignored rather than giving day zero`() {
        val days = listOf(daysAgo(2), today.plusDays(1))
        assertEquals(3, WidgetRowRules.cycleDay(days, today))
    }

    @Test
    fun `no bleeding day means no cycle day`() {
        assertNull(WidgetRowRules.cycleDay(emptyList(), today))
        assertNull(WidgetRowRules.cycleDay(listOf(today.plusDays(3)), today))
    }

    // ------------------------------------------------------------ gaugeLabel

    @Test
    fun `a built-in gauge is named by the app, not by its empty stored label`() {
        val label = WidgetRowRules.gaugeLabel(
            label = "",
            metricKey = "mood",
            builtinRes = builtinLabelRes("journal", "mood"),
            resolve = { if (it == R.string.gauge_mood) "Humeur" else "?" },
        )
        assertEquals("Humeur", label)
    }

    @Test
    fun `every journal built-in has a name to resolve`() {
        listOf("mood", "dysphoria", "euphoria", "libido", "energy").forEach { key ->
            assertTrue("no label for $key", builtinLabelRes("journal", key) != 0)
        }
    }

    @Test
    fun `a custom gauge keeps the label its owner typed`() {
        val label = WidgetRowRules.gaugeLabel(
            label = "Sommeil",
            metricKey = "custom_1",
            builtinRes = builtinLabelRes("journal", "custom_1"),
            resolve = { "?" },
        )
        assertEquals("Sommeil", label)
    }

    @Test
    fun `an unnamed gauge falls back to its key rather than to nothing`() {
        val label = WidgetRowRules.gaugeLabel(
            label = " ",
            metricKey = "custom_2",
            builtinRes = 0,
            resolve = { "?" },
        )
        assertEquals("custom_2", label)
    }

    // -------------------------------------------------------------- isCapped

    @Test
    fun `a full scan may be short of the truth, a partial one is exact`() {
        assertTrue(WidgetRowRules.isCapped(500, 500L))
        assertFalse(WidgetRowRules.isCapped(499, 500L))
        assertFalse(WidgetRowRules.isCapped(0, 500L))
    }
}
