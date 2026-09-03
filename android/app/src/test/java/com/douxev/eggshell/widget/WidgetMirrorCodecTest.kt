package com.douxev.eggshell.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror holds user text — note titles and first lines — and the format
 * joins rows with separators. Anyone\'s note can contain those separators.
 *
 * That is the whole point of these tests: a title with a comma in it must not
 * come back as two fields, and a body with a newline must not come back as two
 * rows. Both failures produce a widget showing something plausible that is
 * simply not what the user wrote, with no error anywhere.
 */
class WidgetMirrorCodecTest {

    private fun row(title: String, subtitle: String = "s", id: Long = 1L) =
        WidgetContentMirror.Row(title, subtitle, id)

    @Test
    fun `rows round-trip`() {
        val mirror = mapOf(
            3 to listOf(row("Rendez-vous", "endocrino", 7L), row("Courses", "", 8L)),
            4 to listOf(row("Seule", "note", 9L)),
        )
        assertEquals(mirror, WidgetMirrorCodec.decode(WidgetMirrorCodec.encode(mirror)))
    }

    @Test
    fun `a comma in a title survives`() {
        val mirror = mapOf(1 to listOf(row("Lundi, mardi, mercredi")))
        assertEquals(mirror, WidgetMirrorCodec.decode(WidgetMirrorCodec.encode(mirror)))
    }

    @Test
    fun `a newline in a subtitle does not become a second row`() {
        val mirror = mapOf(1 to listOf(row("Titre", "ligne un\nligne deux")))
        val decoded = WidgetMirrorCodec.decode(WidgetMirrorCodec.encode(mirror))
        assertEquals(1, decoded.getValue(1).size)
        assertEquals(mirror, decoded)
    }

    /**
     * The entry separator is a NUL. Kotlin strings can hold one, so a note
     * containing it must not be able to forge a second widget\'s worth of rows.
     */
    @Test
    fun `a separator character in the text cannot forge an entry`() {
        val mirror = mapOf(1 to listOf(row("avant\u0000999\nfaux titre,x,5")))
        val decoded = WidgetMirrorCodec.decode(WidgetMirrorCodec.encode(mirror))
        assertEquals(setOf(1), decoded.keys)
        assertEquals(mirror, decoded)
    }

    @Test
    fun `accents and emoji survive`() {
        val mirror = mapOf(1 to listOf(row("Règles ✿ après-midi", "ça va mieux 🌱")))
        assertEquals(mirror, WidgetMirrorCodec.decode(WidgetMirrorCodec.encode(mirror)))
    }

    @Test
    fun `an empty mirror encodes and decodes to nothing`() {
        assertTrue(WidgetMirrorCodec.decode(WidgetMirrorCodec.encode(emptyMap())).isEmpty())
    }

    @Test
    fun `a corrupt row is dropped without taking its neighbours`() {
        val good = WidgetMirrorCodec.encode(mapOf(1 to listOf(row("bonne", "note", 5L))))
        val decoded = WidgetMirrorCodec.decode(good + "\nnot-a-row")
        assertEquals(listOf(row("bonne", "note", 5L)), decoded.getValue(1))
    }
}
