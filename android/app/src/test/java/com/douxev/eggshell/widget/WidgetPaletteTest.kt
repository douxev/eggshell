package com.douxev.eggshell.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The colour role a gauge is drawn in is stored in the mirror by ordinal and
 * mapped from the metric key the same way the journal screen maps it, so the
 * bar for « Humeur » is the same colour on the home screen as in the app.
 */
class WidgetPaletteTest {

    @Test
    fun `built-in metrics keep their screen colours`() {
        assertEquals(WidgetPalette.Role.PRIMARY, WidgetPalette.Role.forMetric("mood", 3))
        assertEquals(WidgetPalette.Role.ERROR, WidgetPalette.Role.forMetric("dysphoria", 0))
        assertEquals(WidgetPalette.Role.TERTIARY, WidgetPalette.Role.forMetric("euphoria", 0))
        assertEquals(WidgetPalette.Role.SECONDARY, WidgetPalette.Role.forMetric("libido", 0))
        assertEquals(WidgetPalette.Role.SUCCESS, WidgetPalette.Role.forMetric("energy", 0))
    }

    @Test
    fun `custom metrics cycle through four roles by position`() {
        val roles = (0 until 8).map { WidgetPalette.Role.forMetric("custom_$it", it) }
        assertEquals(
            listOf(
                WidgetPalette.Role.PRIMARY, WidgetPalette.Role.ERROR,
                WidgetPalette.Role.TERTIARY, WidgetPalette.Role.SECONDARY,
            ) * 2,
            roles,
        )
    }

    /** An ordinal from a newer build that added a role must not crash a render. */
    @Test
    fun `an unknown role ordinal falls back to primary`() {
        assertEquals(WidgetPalette.Role.PRIMARY, WidgetPalette.Role.of(99))
        assertEquals(WidgetPalette.Role.PRIMARY, WidgetPalette.Role.of(-1))
    }

    private operator fun <T> List<T>.times(n: Int): List<T> = List(n) { this }.flatten()
}
