package com.douxev.eggshell.ui.sport

import org.junit.Assert.assertEquals
import org.junit.Test

/** The two bits of the step calendar that are arithmetic rather than layout. */
class StepCalendarTest {

    @Test
    fun `a day with no reading is not a day with zero steps`() {
        // Both shade as empty, but only because there is nothing to show — the
        // caller is what distinguishes "no data" from "did not move".
        assertEquals(0f, goalFraction(null, 8_000))
        assertEquals(0f, goalFraction(0, 8_000))
    }

    @Test
    fun `the disc fills in proportion to the goal`() {
        assertEquals(0.5f, goalFraction(4_000, 8_000))
        assertEquals(1f, goalFraction(8_000, 8_000))
    }

    /**
     * Clamped: one long walk must not make an ordinary good day look pale by
     * comparison. There is no colour beyond "done".
     */
    @Test
    fun `beating the goal three times over is still a full disc`() {
        assertEquals(1f, goalFraction(24_000, 8_000))
    }

    @Test
    fun `a nonsensical goal cannot divide by zero`() {
        assertEquals(0f, goalFraction(5_000, 0))
    }

    /** A month cell has about four characters; anything longer is ellipsised. */
    @Test
    fun `counts are shortened to fit a calendar cell`() {
        assertEquals("999", compactSteps(999))
        assertEquals("1.0k", compactSteps(1_000))
        assertEquals("8.4k", compactSteps(8_412))
        assertEquals("12k", compactSteps(12_400))
    }
}
