package com.douxev.eggshell.data.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Heart Rate Measurement characteristic, byte by byte.
 *
 * Every branch here is a way to be quietly wrong: read a uint16 payload as a
 * uint8 and you get the low byte, which is a perfectly believable heart rate;
 * get the endianness backwards and you get another one. Nothing throws, nothing
 * looks odd on screen, and the number lands in someone's training history.
 */
class HeartRateParserTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `a plain uint8 reading`() {
        // flags 0x00 → uint8, nothing optional.
        assertEquals(72, HeartRateParser.parse(bytes(0x00, 72))!!.bpm)
    }

    @Test
    fun `a uint16 reading is little-endian`() {
        // flags 0x01 → uint16. 0x0110 = 272, not 0x1001 = 4097.
        assertEquals(272, HeartRateParser.parse(bytes(0x01, 0x10, 0x01))!!.bpm)
    }

    /**
     * The mistake this test exists for: a uint16 packet read as uint8 yields
     * its low byte. 0x9E is 158 — a completely ordinary heart rate, and wrong.
     */
    @Test
    fun `a uint16 reading is not truncated to its low byte`() {
        val sample = HeartRateParser.parse(bytes(0x01, 0x9E, 0x01))!!
        assertEquals(414, sample.bpm)
    }

    @Test
    fun `energy expended is skipped over before the rr intervals`() {
        // flags 0x18 = energy + RR, uint8 value.
        // 62 bpm, 500 kJ, then two RR intervals of 1024 and 512 units.
        val sample = HeartRateParser.parse(
            bytes(0x18, 62, 0xF4, 0x01, 0x00, 0x04, 0x00, 0x02)
        )!!
        assertEquals(62, sample.bpm)
        assertEquals(500, sample.energyExpendedKJ)
        // 1024/1024 s = 1000 ms, 512/1024 s = 500 ms.
        assertEquals(listOf(1000, 500), sample.rrIntervalsMs)
    }

    /**
     * Without skipping the energy field, the first RR interval would be read
     * out of the energy bytes — the classic way this format is misparsed.
     */
    @Test
    fun `rr intervals are not read out of the energy field`() {
        val sample = HeartRateParser.parse(
            bytes(0x18, 62, 0xF4, 0x01, 0x00, 0x04)
        )!!
        assertEquals(listOf(1000), sample.rrIntervalsMs)
    }

    @Test
    fun `rr intervals convert from 1024ths of a second to milliseconds`() {
        // flags 0x10 = RR only. 0x0300 = 768 units = 750 ms.
        val sample = HeartRateParser.parse(bytes(0x10, 60, 0x00, 0x03))!!
        assertEquals(listOf(750), sample.rrIntervalsMs)
    }

    @Test
    fun `a truncated packet is dropped rather than read past`() {
        assertNull(HeartRateParser.parse(bytes(0x01, 0x10)))   // uint16, one byte
        assertNull(HeartRateParser.parse(bytes(0x08, 60, 0x01))) // energy, one byte
        assertNull(HeartRateParser.parse(bytes()))
        assertNull(HeartRateParser.parse(bytes(0x00)))
    }

    /**
     * A strap that has lost skin contact reports zero. Averaging that in would
     * drag the session's number down for every second it was loose.
     */
    @Test
    fun `a zero reading is not a heart rate`() {
        assertNull(HeartRateParser.parse(bytes(0x00, 0)))
    }

    @Test
    fun `sensor contact bits do not shift the payload`() {
        // flags 0x06 = contact supported and detected, still uint8.
        assertEquals(88, HeartRateParser.parse(bytes(0x06, 88))!!.bpm)
    }
}

/** The two numbers a session actually keeps. */
class HeartRateAccumulatorTest {

    @Test
    fun `nothing recorded means null, not zero`() {
        val acc = HeartRateAccumulator()
        assertNull(acc.average)
        assertNull(acc.max)
        assertEquals(0L, acc.samples)
    }

    @Test
    fun `average and peak are tracked across samples`() {
        val acc = HeartRateAccumulator()
        listOf(120, 140, 160, 130).forEach(acc::add)
        assertEquals(137, acc.average)
        assertEquals(160, acc.max)
        assertEquals(4L, acc.samples)
    }

    @Test
    fun `dropouts are ignored rather than averaged in`() {
        val acc = HeartRateAccumulator()
        listOf(150, 0, 150, 0).forEach(acc::add)
        assertEquals(150, acc.average)
        assertEquals(2L, acc.samples)
    }

    @Test
    fun `a reset session does not inherit the previous one's numbers`() {
        val acc = HeartRateAccumulator()
        listOf(180, 190).forEach(acc::add)
        acc.reset()
        assertNull(acc.average)
        assertNull(acc.max)
        acc.add(100)
        assertEquals(100, acc.average)
        assertEquals(100, acc.max)
    }

    @Test
    fun `a long session does not overflow`() {
        val acc = HeartRateAccumulator()
        repeat(100_000) { acc.add(200) }
        assertEquals(200, acc.average)
        assertTrue(acc.samples == 100_000L)
    }
}
