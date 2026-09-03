package com.douxev.eggshell.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Widget configuration survives app updates, so the only property that really
 * matters is that a new build reads what an old one wrote.
 *
 * A parser that quietly got stricter would not throw — it would return nothing,
 * and every configured widget on someone's home screen would revert to its
 * defaults with no error anywhere.
 */
class WidgetConfigCodecTest {

    @Test
    fun `a config round-trips`() {
        val configs = mapOf(
            7 to WidgetConfigPrefs.Config(
                showsContent = true, rows = 2, targetKind = "folder", targetId = 42L,
            ),
            9 to WidgetConfigPrefs.Config(),
        )
        assertEquals(configs, WidgetConfigCodec.decode(WidgetConfigCodec.encode(configs)))
    }

    /**
     * A row written by a build that had only the first three fields. Whatever
     * this format grows next, this row must keep resolving to the same widget
     * with defaults for what it does not carry.
     */
    @Test
    fun `a row from an older format is read with defaults, not dropped`() {
        val decoded = WidgetConfigCodec.decode("7,1,2")
        assertEquals(1, decoded.size)
        val config = decoded.getValue(7)
        assertTrue(config.showsContent)
        assertEquals(2, config.rows)
        assertNull(config.targetKind)
        assertNull(config.targetId)
    }

    /**
     * A row written by a *newer* build, with fields this one has never heard
     * of. Downgrades happen (a sideloaded APK, a restored older install), and
     * the extra columns must be ignored rather than poison the row.
     */
    @Test
    fun `unknown trailing fields are ignored`() {
        val config = WidgetConfigCodec.decode("7,1,2,folder,42,something,else").getValue(7)
        assertEquals("folder", config.targetKind)
        assertEquals(42L, config.targetId)
    }

    @Test
    fun `a corrupt row is dropped alone, not with the whole file`() {
        val decoded = WidgetConfigCodec.decode("7,1,2,folder,42\nnonsense\n9,0,3,,")
        assertEquals(setOf(7, 9), decoded.keys)
    }

    @Test
    fun `empty target fields read back as absent, not as empty strings`() {
        val config = WidgetConfigCodec.decode("7,0,3,,").getValue(7)
        assertNull(config.targetKind)
        assertNull(config.targetId)
    }

    @Test
    fun `content is off unless the row says otherwise`() {
        // The opt-in default has to survive the format, not just the class:
        // anything other than "1" means the widget stays a door.
        assertTrue(!WidgetConfigCodec.decode("7,0,3,,").getValue(7).showsContent)
        assertTrue(!WidgetConfigCodec.decode("7,,3,,").getValue(7).showsContent)
    }
}
