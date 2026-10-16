package com.douxev.eggshell.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pedometer's arithmetic. Every mistake here is silent — the number on
 * screen still looks like a step count — so each rule the old build got wrong
 * has a test named after the way it went wrong.
 */
class StepMathTest {

    private val zone = ZoneId.of("Europe/Paris")

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val mon = LocalDate.of(2026, 10, 12)
    private val tue = mon.plusDays(1)
    private val wed = mon.plusDays(2)

    // -- delta -----------------------------------------------------------------

    @Test
    fun `a first reading credits nothing`() {
        assertEquals(0L, StepMath.delta(null, null, 52_000, 7))
    }

    @Test
    fun `growth on the same boot is the difference`() {
        assertEquals(1_200L, StepMath.delta(10_000, 7, 11_200, 7))
    }

    /** The bug: 12 000 → reboot → 15 000 steps used to read as +3 000. */
    @Test
    fun `a reboot followed by more walking than the old baseline is still a reboot`() {
        assertEquals(15_000L, StepMath.delta(12_000, 7, 15_000, 8))
    }

    @Test
    fun `a counter that went down is a restart even without a boot count`() {
        assertEquals(300L, StepMath.delta(12_000, null, 300, null))
        assertEquals(300L, StepMath.delta(12_000, 7, 300, 7))
    }

    // -- split -----------------------------------------------------------------

    @Test
    fun `a reading on the same day goes to that day`() {
        assertEquals(mapOf(tue to 900L), StepMath.split(900, at(tue, 9), at(tue, 18), zone))
    }

    @Test
    fun `no previous reading time credits the reading day`() {
        assertEquals(mapOf(tue to 900L), StepMath.split(900, null, at(tue, 18), zone))
    }

    /**
     * The bug this whole rewrite is about: steps from Monday and Tuesday all
     * landing on Wednesday, the day the app was next opened.
     */
    @Test
    fun `a gap over several days is shared by time, not dumped on the last one`() {
        val shares = StepMath.split(4_800, at(mon, 0), at(wed, 0), zone)
        assertEquals(mapOf(mon to 2_400L, tue to 2_400L), shares)
    }

    @Test
    fun `shares always add up to the delta exactly`() {
        val shares = StepMath.split(1_001, at(mon, 7, 13), at(wed, 16, 41), zone)
        assertEquals(1_001L, shares.values.sum())
        assertTrue(shares.keys.all { it in setOf(mon, tue, wed) })
    }

    @Test
    fun `the 23h59 reading leaves almost nothing for the next day`() {
        val shares = StepMath.split(3_000, at(mon, 23, 59), at(tue, 8), zone)
        assertEquals(3_000L, shares.values.sum())
        assertTrue((shares[mon] ?: 0L) <= 10L)
    }

    @Test
    fun `a clock set backwards does not split into the future`() {
        assertEquals(mapOf(mon to 500L), StepMath.split(500, at(tue, 9), at(mon, 9), zone))
    }

    @Test
    fun `daylight saving day is split on real hours`() {
        // 2026-10-25: Paris falls back, the day lasts 25 hours.
        val sun = LocalDate.of(2026, 10, 25)
        val shares = StepMath.split(4_900, at(sun, 0), at(sun.plusDays(1), 0).plus(24 * 3_600_000L), zone)
        assertEquals(4_900L, shares.values.sum())
        assertEquals(2_500L, shares[sun])
    }

    // -- record & prune ----------------------------------------------------------

    @Test
    fun `recording reopens the day and moves the baseline`() {
        val state = StepState(
            lastRaw = 1_000, lastBoot = 3, lastReadMs = at(tue, 8),
            days = mapOf(tue to StepDayEntry(400, flushed = true)),
        )
        val (next, delta) = StepMath.record(state, 1_500, 3, at(tue, 9), zone)
        assertEquals(500L, delta)
        assertEquals(StepDayEntry(900, flushed = false), next.days[tue])
        assertEquals(1_500L, next.lastRaw)
        assertEquals(at(tue, 9), next.lastReadMs)
    }

    /**
     * Read at 23:00, rebooted at 7:00, read again at 9:00: the 600 steps since
     * boot are this morning's. Splitting from 23:00 used to hand a share of
     * them to the evening before.
     */
    @Test
    fun `after a reboot the steps are placed after the boot`() {
        val state = StepState(lastRaw = 9_000, lastBoot = 3, lastReadMs = at(mon, 23))
        val (next, delta) = StepMath.record(state, 600, 4, at(tue, 9), zone, bootMs = at(tue, 7))
        assertEquals(600L, delta)
        assertEquals(mapOf(tue to StepDayEntry(600, flushed = false)), next.days)
    }

    /**
     * The 3.0.0 bug on the first device it ran on: the previous build left a
     * bare raw baseline (no time, no boot), the phone had been up for eight
     * days, and the first reading credited 32 000 steps — days of walking — to
     * today. A baseline with no time credits nothing; it only re-baselines.
     */
    @Test
    fun `a baseline with no time credits nothing to today`() {
        val legacy = StepState(lastRaw = 10_000)
        val (next, credited) = StepMath.record(
            legacy, raw = 42_000, boot = 89, nowMs = at(wed, 17), zone = zone,
            bootMs = at(wed.minusDays(8), 9),
        )
        assertEquals(0L, credited)
        assertTrue(next.days.isEmpty())
        assertEquals(42_000L, next.lastRaw)
        assertEquals(at(wed, 17), next.lastReadMs)
    }

    /** ...unless the phone booted today: then every counted step is today's. */
    @Test
    fun `a baseline with no time after a boot this morning credits today`() {
        val legacy = StepState(lastRaw = 30_000)
        val (next, credited) = StepMath.record(
            legacy, raw = 1_800, boot = 90, nowMs = at(wed, 17), zone = zone, bootMs = at(wed, 7),
        )
        assertEquals(1_800L, credited)
        assertEquals(mapOf(wed to StepDayEntry(1_800, flushed = false)), next.days)
    }

    @Test
    fun `pruning never drops what the vault has not received`() {
        val old = wed.minusDays(60)
        val state = StepState(
            lastReadMs = at(wed, 9),
            days = mapOf(
                old to StepDayEntry(5_000, flushed = false),
                old.plusDays(1) to StepDayEntry(5_000, flushed = true),
                wed to StepDayEntry(100, flushed = true),
            ),
        )
        val pruned = StepMath.prune(state, wed, zone)
        assertTrue(old in pruned.days)
        assertTrue(old.plusDays(1) !in pruned.days)
        assertTrue(wed in pruned.days)
    }

    // -- codec -----------------------------------------------------------------

    @Test
    fun `the ledger round-trips`() {
        val state = StepState(
            lastRaw = 123_456, lastBoot = 42, lastReadMs = at(tue, 23, 59), seeded = true,
            days = mapOf(mon to StepDayEntry(8_000, true), tue to StepDayEntry(3_210, false)),
        )
        assertEquals(state, StepCodec.decode(StepCodec.encode(state)))
    }

    @Test
    fun `corrected days round-trip, and a ledger written without them still decodes`() {
        val state = StepState(lastRaw = 1, days = mapOf(mon to StepDayEntry(10, true)), corrected = setOf(mon, tue))
        assertEquals(state, StepCodec.decode(StepCodec.encode(state)))
        val older = "2|1|||0|$mon:10:1"
        assertEquals(emptySet<LocalDate>(), StepCodec.decode(older)!!.corrected)
    }

    @Test
    fun `an empty ledger round-trips`() {
        assertEquals(StepState(), StepCodec.decode(StepCodec.encode(StepState())))
    }

    @Test
    fun `an unknown format is refused rather than misread`() {
        assertNull(StepCodec.decode("9|1|2|3|1|"))
        assertNull(StepCodec.decode("garbage"))
    }

    // -- midnight alarm ----------------------------------------------------------

    @Test
    fun `the day-end alarm is tonight, or tomorrow night once it has passed`() {
        val evening = ZonedDateTime.of(tue.atTime(20, 0), zone)
        assertEquals(tue.atTime(23, 59), StepSync.nextDayEnd(evening).toLocalDateTime())
        val late = ZonedDateTime.of(tue.atTime(23, 59, 30), zone)
        assertEquals(wed.atTime(23, 59), StepSync.nextDayEnd(late).toLocalDateTime())
    }
}
