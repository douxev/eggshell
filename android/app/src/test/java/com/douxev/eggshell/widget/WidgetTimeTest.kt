package com.douxev.eggshell.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit boundaries for the widgets' relative times.
 *
 * Worth pinning because a wrong bucket is invisible: "in 1 h" for something two
 * hours away reads perfectly well and is simply false, and the reader acts on
 * it — these labels sit next to a dose that has to be taken on time.
 */
class WidgetTimeTest {

    @Test
    fun `under an hour is stated in minutes`() {
        assertEquals(WidgetTime.Scale.MINUTES to 0, WidgetTime.scale(0))
        assertEquals(WidgetTime.Scale.MINUTES to 59, WidgetTime.scale(59))
    }

    @Test
    fun `an hour exactly switches unit`() {
        assertEquals(WidgetTime.Scale.HOURS to 1, WidgetTime.scale(60))
    }

    @Test
    fun `the last minute before a day is still hours`() {
        assertEquals(WidgetTime.Scale.HOURS to 23, WidgetTime.scale(60 * 24 - 1))
        assertEquals(WidgetTime.Scale.DAYS to 1, WidgetTime.scale(60 * 24))
    }

    /**
     * Truncating, not rounding: "in 1 h" for something 119 minutes away is a
     * promise the widget cannot keep. The reader may wait longer than the label
     * says, never less.
     */
    @Test
    fun `durations are truncated toward the present, never rounded up`() {
        assertEquals(WidgetTime.Scale.HOURS to 1, WidgetTime.scale(119))
        assertEquals(WidgetTime.Scale.DAYS to 2, WidgetTime.scale(60 * 24 * 3 - 1))
    }
}
