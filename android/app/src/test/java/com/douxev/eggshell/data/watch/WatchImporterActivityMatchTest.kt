package com.douxev.eggshell.data.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.transition.SportActivity

/**
 * Matching a watch's word for an activity onto the user's own catalogue.
 *
 * The rule being pinned is that a wrong match is worse than no match: a session
 * filed under the wrong type is silent and has to be spotted, while one filed
 * with no type is visible and one tap to fix.
 */
class WatchImporterActivityMatchTest {

    private fun activity(id: Long, name: String, archived: Boolean = false) = SportActivity(
        id = id, name = name, kind = "cardio", color = null,
        archived = archived, createdMs = 0,
    )

    private val catalogue = listOf(
        activity(1, "Course"),
        activity(2, "Vélo"),
        activity(3, "Natation"),
    )

    @Test
    fun `an exact name matches whatever the case`() {
        assertEquals(1L, WatchImporter.matchActivity("course", catalogue)?.id)
        assertEquals(1L, WatchImporter.matchActivity("COURSE", catalogue)?.id)
    }

    @Test
    fun `a longer label containing the type still matches`() {
        // "Trail Course" from an exporter that prefixes the discipline.
        assertEquals(1L, WatchImporter.matchActivity("Trail Course", catalogue)?.id)
    }

    @Test
    fun `an unknown activity is filed with no type rather than guessed`() {
        assertNull(WatchImporter.matchActivity("Kayaking", catalogue))
    }

    @Test
    fun `no hint at all matches nothing`() {
        assertNull(WatchImporter.matchActivity(null, catalogue))
        assertNull(WatchImporter.matchActivity("   ", catalogue))
    }

    /**
     * An archived type is one the user has put away. Filing a fresh import
     * under it would quietly bring it back into their history.
     */
    @Test
    fun `an archived type is never matched`() {
        val withArchived = catalogue + activity(4, "Escalade", archived = true)
        assertNull(WatchImporter.matchActivity("Escalade", withArchived))
    }

    /** Watches write English, catalogues are usually French. */
    @Test
    fun `an english watch word finds the french type`() {
        assertEquals(1L, WatchImporter.matchActivity("Running", catalogue)?.id)
        assertEquals(2L, WatchImporter.matchActivity("Biking", catalogue)?.id)
        assertEquals(3L, WatchImporter.matchActivity("open_water_swimming", catalogue)?.id)
    }

    @Test
    fun `accents and separators do not get in the way`() {
        val withRoad = catalogue + activity(5, "Vélo de route")
        assertEquals(2L, WatchImporter.matchActivity("velo", withRoad)?.id)
    }

    @Test
    fun `an activity no family knows stays unmatched`() {
        assertNull(WatchImporter.matchActivity("Escalade", catalogue))
    }
}
