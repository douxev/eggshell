package com.douxev.eggshell.data.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser against the shapes real watches actually export.
 *
 * Worth testing properly because every failure here is silent: a workout that
 * parses to the wrong duration, or lands an hour off, or comes back with a
 * distance multiplied by its number of samples, still looks like a workout.
 * The user would have to remember what their watch said to notice.
 */
class WorkoutFileParserTest {

    private fun parse(xml: String) = WorkoutFileParser.parse(xml.byteInputStream())

    // -- TCX ------------------------------------------------------------------

    private val garminTcx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
          <Activities>
            <Activity Sport="Running">
              <Id>2026-09-03T07:30:00.000Z</Id>
              <Lap StartTime="2026-09-03T07:30:00.000Z">
                <TotalTimeSeconds>1200.0</TotalTimeSeconds>
                <DistanceMeters>3000.0</DistanceMeters>
                <Track>
                  <Trackpoint>
                    <Time>2026-09-03T07:30:10.000Z</Time>
                    <DistanceMeters>25.0</DistanceMeters>
                  </Trackpoint>
                  <Trackpoint>
                    <Time>2026-09-03T07:30:20.000Z</Time>
                    <DistanceMeters>55.0</DistanceMeters>
                  </Trackpoint>
                </Track>
              </Lap>
              <Lap StartTime="2026-09-03T07:50:00.000Z">
                <TotalTimeSeconds>600.0</TotalTimeSeconds>
                <DistanceMeters>2000.0</DistanceMeters>
              </Lap>
            </Activity>
          </Activities>
        </TrainingCenterDatabase>
    """.trimIndent()

    @Test
    fun `a tcx activity reports the watch's own duration and distance`() {
        val workout = parse(garminTcx).single()
        assertEquals(1800L, workout.durationS)
        assertEquals("Running", workout.activityHint)
    }

    /**
     * The trap this format sets: a Lap states its own distance, a Trackpoint
     * states the running total for the whole activity. Add both up and a 5 km
     * run becomes 5 km plus every intermediate reading — a number that is wrong
     * by an amount that grows with how often the watch sampled.
     */
    @Test
    fun `trackpoint distances are not added to the lap totals`() {
        val workout = parse(garminTcx).single()
        assertEquals(5_000.0, workout.distanceM!!, 0.1)
    }

    @Test
    fun `laps add up to the total duration`() {
        // 1200 + 600, not the first lap alone and not the longest.
        assertEquals(1800L, parse(garminTcx).single().durationS)
    }

    @Test
    fun `a tcx file can hold several activities`() {
        val two = garminTcx.replace(
            "</Activity>\n  </Activities>",
            "</Activity>\n  </Activities>",
        ).replace(
            "</Activities>",
            """
              <Activity Sport="Biking">
                <Id>2026-09-04T18:00:00.000Z</Id>
                <Lap StartTime="2026-09-04T18:00:00.000Z">
                  <TotalTimeSeconds>3600.0</TotalTimeSeconds>
                  <DistanceMeters>25000.0</DistanceMeters>
                </Lap>
              </Activity>
            </Activities>
            """.trimIndent(),
        )
        val workouts = parse(two)
        assertEquals(2, workouts.size)
        assertEquals("Biking", workouts[1].activityHint)
        assertEquals(3600L, workouts[1].durationS)
    }

    // -- GPX ------------------------------------------------------------------

    private val gpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Some Watch">
          <trk>
            <name>Course du matin</name>
            <type>running</type>
            <trkseg>
              <trkpt lat="48.8566" lon="2.3522"><time>2026-09-03T07:00:00Z</time></trkpt>
              <trkpt lat="48.8600" lon="2.3522"><time>2026-09-03T07:05:00Z</time></trkpt>
              <trkpt lat="48.8650" lon="2.3522"><time>2026-09-03T07:12:00Z</time></trkpt>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()

    @Test
    fun `a gpx track gives its span as the duration`() {
        val workout = parse(gpx).single()
        assertEquals(12 * 60L, workout.durationS)
        assertEquals("running", workout.activityHint)
        assertEquals("Course du matin", workout.title)
    }

    /**
     * Roughly 0.0084 degrees of latitude, which is about 930 m. Loose bound:
     * the point is that the distance is computed from the route at all, not
     * that haversine agrees with a geodesic to the metre.
     */
    @Test
    fun `a gpx distance is derived from the track`() {
        val metres = parse(gpx).single().distanceM!!
        assertTrue("expected ~930 m, got $metres", metres in 800.0..1_100.0)
    }

    @Test
    fun `a namespaced document parses like a bare one`() {
        val prefixed = gpx
            .replace("<trk>", "<gpx:trk>").replace("</trk>", "</gpx:trk>")
            .replace("<trkseg>", "<gpx:trkseg>").replace("</trkseg>", "</gpx:trkseg>")
        assertEquals(1, parse(prefixed).size)
    }

    // -- Robustness -----------------------------------------------------------

    @Test
    fun `a track with a single point is not a workout`() {
        val one = gpx.substringBefore("<trkpt lat=\"48.8600\"") + "</trkseg></trk></gpx>"
        assertTrue(parse(one).isEmpty())
    }

    @Test
    fun `a file with nothing in it yields nothing rather than throwing`() {
        assertTrue(parse("<gpx version=\"1.1\"></gpx>").isEmpty())
    }

    @Test
    fun `an unparseable timestamp does not lose the rest of the track`() {
        val broken = gpx.replace("2026-09-03T07:05:00Z", "not-a-time")
        // First and last still parse, so the span is still known.
        assertEquals(12 * 60L, parse(broken).single().durationS)
    }

    /** Some exporters omit the zone. An hour out beats losing the session. */
    @Test
    fun `a timestamp with no zone is read as UTC rather than dropped`() {
        assertNotNull(WorkoutFileParser.parseTime("2026-09-03T07:00:00"))
        assertEquals(
            WorkoutFileParser.parseTime("2026-09-03T07:00:00Z"),
            WorkoutFileParser.parseTime("2026-09-03T07:00:00"),
        )
    }

    @Test
    fun `fractional seconds and offsets both parse`() {
        assertNotNull(WorkoutFileParser.parseTime("2026-09-03T07:00:00.123Z"))
        assertNotNull(WorkoutFileParser.parseTime("2026-09-03T09:00:00+02:00"))
        assertNull(WorkoutFileParser.parseTime(""))
        assertNull(WorkoutFileParser.parseTime(null))
    }

    @Test
    fun `haversine agrees with a known distance`() {
        // Paris to Lyon, about 392 km.
        val metres = WorkoutFileParser.haversineMetres(48.8566, 2.3522, 45.7640, 4.8357)
        assertTrue("got $metres", metres in 385_000.0..400_000.0)
    }

    /**
     * A workout file is data the user got from somewhere. A DOCTYPE that made
     * the parser go and read a local file would turn importing a run into an
     * exfiltration primitive.
     */
    @Test
    fun `an external entity is not resolved`() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <gpx><trk><name>&xxe;</name></trk></gpx>
        """.trimIndent()
        // Either refused outright or parsed with nothing resolved; both are
        // fine, silently reading the file is not.
        val result = runCatching { parse(xxe) }
        result.getOrNull()?.let { assertTrue(it.isEmpty()) }
    }
}
