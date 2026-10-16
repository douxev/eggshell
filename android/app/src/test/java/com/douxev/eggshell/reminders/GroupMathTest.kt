package com.douxev.eggshell.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Who shares a grouped medication reminder. The rule that matters: a group
 * only gathers what is due together — « Tout pris » at noon must never mark
 * the morning's doses as taken at noon.
 */
class GroupMathTest {

    private val t0 = 1_760_000_000_000L
    private val minute = 60_000L

    @Test
    fun `the first alarm opens a group`() {
        val g = GroupMath.join(null, lastKey = 4, scheduleId = 11, nowMs = t0)
        assertEquals(5, g.key)
        assertEquals(listOf(11L), g.members)
    }

    @Test
    fun `alarms a few seconds apart share the group`() {
        val g1 = GroupMath.join(null, 4, 11, t0)
        val g2 = GroupMath.join(g1, 5, 12, t0 + 3_000)
        assertEquals(5, g2.key)
        assertEquals(listOf(11L, 12L), g2.members)
    }

    @Test
    fun `a reminder after the window gets its own group`() {
        val g1 = GroupMath.join(null, 4, 11, t0)
        val g2 = GroupMath.join(g1, 5, 12, t0 + 16 * minute)
        assertEquals(6, g2.key)
        assertEquals(listOf(12L), g2.members)
    }

    @Test
    fun `a member is never listed twice`() {
        val g1 = GroupMath.join(null, 0, 11, t0)
        assertEquals(listOf(11L), GroupMath.join(g1, 1, 11, t0 + minute).members)
    }

    @Test
    fun `keys wrap within the notification id range`() {
        assertEquals(0, GroupMath.join(null, 0xFFFF, 1, t0).key)
    }

    @Test
    fun `the open group round-trips, and garbage reads as no group`() {
        val g = MedReminderGroup.Group(7, t0, listOf(3, 9))
        assertEquals(g, GroupMath.decode(GroupMath.encode(g)))
        assertNull(GroupMath.decode("nonsense"))
        assertNull(GroupMath.decode(null))
    }
}
