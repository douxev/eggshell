package com.douxev.eggshell.data.watch

import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * One workout read out of a file a watch exported.
 *
 * Deliberately small: what a sport session in this app actually holds, and
 * nothing else. A GPX track carries every coordinate of where someone went, and
 * importing those into a vault that exists to protect people from being tracked
 * would be an odd thing to do without being asked — so the route is read, used
 * to work out how far they went, and dropped.
 */
data class ImportedWorkout(
    val startedMs: Long,
    val durationS: Long,
    /** Metres, when the file says or the route allows it to be computed. */
    val distanceM: Double?,
    /** The file's own word for the activity ("Running", "cycling"), if any. */
    val activityHint: String?,
    /** What the exporter called it. Used as the session note, never invented. */
    val title: String?,
    /**
     * Average and peak heart rate, when the file carries them — per trackpoint
     * (TCX `HeartRateBpm`, GPX `gpxtpx:hr`) or per lap. Null otherwise, and
     * null rather than a number the vault would refuse (outside 20–300).
     */
    val avgHr: Long? = null,
    val maxHr: Long? = null,
)

/**
 * Reads workouts out of the files watches export.
 *
 * **Why files, and not a vendor SDK.** Every watch maker offers an app that
 * syncs through their own servers, which is exactly the thing this app must not
 * do. A file the user exported themselves involves no account, no network, no
 * background service and no third party — it works with Garmin, Polar, Suunto,
 * Coros, Amazfit, Samsung and anything else that can write GPX or TCX, and it
 * keeps working when a vendor shuts an API down.
 *
 * **TCX before GPX where both exist.** TCX states the duration and the distance
 * as recorded by the watch; GPX carries only timestamped points, so both have
 * to be derived — the duration from first and last point (which counts pauses
 * as exercise) and the distance from the coordinates (which is a lower bound,
 * since a track is a polyline through a curve).
 *
 * Parsed with `javax.xml.parsers` SAX, which exists identically on Android and
 * on a plain JVM. That is not incidental: it is what lets the whole parser be
 * unit-tested against real exporter output without a device, and it adds no
 * dependency. Nothing here builds a DOM either, so a two-hour ride with 20,000
 * track points does not become 20,000 objects on the heap.
 */
object WorkoutFileParser {

    /** Suffixes worth offering in a file picker. */
    val SUPPORTED_EXTENSIONS = listOf("gpx", "tcx", "fit")

    /**
     * Whether [bytes] are a FIT file: a 12- or 14-byte header whose bytes 8–11
     * spell ".FIT". Decided from the content, like the XML formats.
     */
    fun isFit(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            (bytes[0].toInt() == 12 || bytes[0].toInt() == 14) &&
            bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte() &&
            bytes[10] == 'I'.code.toByte() && bytes[11] == 'T'.code.toByte()

    private const val DISALLOW_DOCTYPE =
        "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL =
        "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER =
        "http://xml.org/sax/features/external-parameter-entities"

    /**
     * Parse whatever is in [input]. Returns every workout the file describes —
     * a TCX can hold several — or an empty list when it holds none.
     *
     * The format is decided by what the document actually contains, not by the
     * file name: exports arrive named `.xml`, named `activity(3).gpx`, and named
     * nothing at all when they come through a share sheet.
     */
    fun parse(input: InputStream): List<ImportedWorkout> {
        val handler = WorkoutHandler()
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            // A workout file is data, never a document to go and resolve.
            // An exported .gpx arrives from wherever the user got it, and
            // trusting a DOCTYPE is the classic way a parser starts reading
            // files it was never given. runCatching because not every
            // implementation knows every feature name, and a parser that
            // rejects the flag is not a reason to refuse the import.
            runCatching { setFeature(DISALLOW_DOCTYPE, true) }
            runCatching { setFeature(EXTERNAL_GENERAL, false) }
            runCatching { setFeature(EXTERNAL_PARAMETER, false) }
        }.newSAXParser().parse(input, handler)
        return handler.workouts
    }

    /**
     * One pass over the document, accumulating whichever of the two shapes it
     * turns out to be.
     *
     * Both handled together rather than sniffing the root element first: their
     * element names do not overlap, a file is only ever one of the two, and
     * deciding up front would mean either buffering the whole document or
     * parsing it twice.
     */
    private class WorkoutHandler : DefaultHandler() {
        val workouts = mutableListOf<ImportedWorkout>()

        private val text = StringBuilder()

        // TCX
        private var tcxSport: String? = null
        private var tcxStart: Long? = null
        private var tcxSeconds = 0.0
        private var tcxMetres = 0.0
        private var inActivity = false

        /**
         * Inside a `<Trackpoint>`.
         *
         * The distinction this flag exists for: a `<Lap>` states its own
         * distance, while a `<Trackpoint>` states the running total for the
         * whole activity. Adding both makes a 5 km run come out as 5 km plus
         * every intermediate reading — wrong by an amount that grows with how
         * often the watch sampled, and entirely plausible-looking.
         */
        private var inTrackpoint = false

        /**
         * Open elements, innermost last. Heart rate hides in a `<Value>` whose
         * meaning depends on its parent — `AverageHeartRateBpm`,
         * `MaximumHeartRateBpm`, a trackpoint's `HeartRateBpm` — so the parent
         * has to be known when the value closes.
         */
        private val open = ArrayDeque<String>()

        // Heart rate, shared by both formats.
        private var hrSum = 0L
        private var hrCount = 0L
        private var hrPeak = 0L
        private var lapSeconds = 0.0
        private var lapAvg: Long? = null
        private var lapWeighted = 0.0
        private var lapWeight = 0.0

        // GPX
        private var gpxName: String? = null
        private var gpxType: String? = null
        private var gpxFirst: Long? = null
        private var gpxLast: Long? = null
        private var gpxMetres = 0.0
        private var lastLat: Double? = null
        private var lastLon: Double? = null
        private var pendingLat: Double? = null
        private var pendingLon: Double? = null
        private var inTrack = false

        override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
            text.setLength(0)
            val name = tag(qName)
            open.addLast(name)
            when (name) {
                "activity" -> {
                    inActivity = true
                    tcxSport = attrs.getValue("Sport")
                    tcxStart = null
                    tcxSeconds = 0.0
                    tcxMetres = 0.0
                    resetHeartRate()
                }
                "lap" -> {
                    if (inActivity && tcxStart == null) {
                        tcxStart = parseTime(attrs.getValue("StartTime"))
                    }
                    lapSeconds = 0.0
                    lapAvg = null
                }
                "trk" -> {
                    inTrack = true
                    gpxName = null
                    gpxType = null
                    gpxFirst = null
                    gpxLast = null
                    gpxMetres = 0.0
                    lastLat = null
                    lastLon = null
                    resetHeartRate()
                }
                // A new segment is a new stretch of recording — after a pause,
                // or a GPS that dropped out. The straight line across the gap
                // was never travelled and must not count as distance.
                "trkseg" -> {
                    lastLat = null
                    lastLon = null
                }
                "trkpt" -> {
                    pendingLat = attrs.getValue("lat")?.toDoubleOrNull()
                    pendingLon = attrs.getValue("lon")?.toDoubleOrNull()
                }
                "trackpoint" -> inTrackpoint = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, local: String?, qName: String) {
            val value = text.toString().trim()
            text.setLength(0)
            val name = tag(qName)
            if (open.lastOrNull() == name) open.removeLast()
            val parent = open.lastOrNull()
            when (name) {
                // -- Heart rate (TCX) --------------------------------------
                "value" -> {
                    val bpm = value.toDoubleOrNull()?.toLong()
                    if (bpm != null) when (parent) {
                        "heartratebpm" -> if (inTrackpoint) sample(bpm)
                        "averageheartratebpm" -> if (!inTrackpoint) lapAvg = bpm
                        "maximumheartratebpm" -> if (!inTrackpoint && bpm in HR_RANGE) {
                            hrPeak = maxOf(hrPeak, bpm)
                        }
                    }
                }
                // -- Heart rate (GPX extensions) ---------------------------
                "hr", "heartrate" -> if (inTrack && pendingLat != null) {
                    value.toDoubleOrNull()?.toLong()?.let(::sample)
                }

                // -- TCX ---------------------------------------------------
                "id" -> if (inActivity && tcxStart == null) tcxStart = parseTime(value)
                "totaltimeseconds" -> if (inActivity) {
                    val seconds = value.toDoubleOrNull() ?: 0.0
                    tcxSeconds += seconds
                    lapSeconds += seconds
                }
                "lap" -> {
                    val avg = lapAvg
                    if (avg != null && avg in HR_RANGE && lapSeconds > 0) {
                        lapWeighted += avg * lapSeconds
                        lapWeight += lapSeconds
                    }
                }
                // Laps only — see inTrackpoint.
                "distancemeters" -> if (inActivity && !inTrackpoint) {
                    tcxMetres += value.toDoubleOrNull() ?: 0.0
                }
                "trackpoint" -> inTrackpoint = false
                "activity" -> {
                    val start = tcxStart
                    if (inActivity && start != null && tcxSeconds > 0) {
                        workouts += ImportedWorkout(
                            startedMs = start,
                            durationS = tcxSeconds.toLong(),
                            distanceM = tcxMetres.takeIf { it > 0 },
                            activityHint = tcxSport,
                            title = null,
                            avgHr = averageHeartRate(),
                            maxHr = hrPeak.takeIf { it in HR_RANGE },
                        )
                    }
                    inActivity = false
                }

                // -- GPX ---------------------------------------------------
                "name" -> if (inTrack && gpxName == null) gpxName = value.ifBlank { null }
                "type" -> if (inTrack && gpxType == null) gpxType = value.ifBlank { null }
                "time" -> if (inTrack && pendingLat != null) {
                    parseTime(value)?.let { at ->
                        if (gpxFirst == null) gpxFirst = at
                        gpxLast = at
                    }
                }
                "trkpt" -> {
                    val lat = pendingLat
                    val lon = pendingLon
                    if (lat != null && lon != null) {
                        val pLat = lastLat
                        val pLon = lastLon
                        if (pLat != null && pLon != null) {
                            gpxMetres += haversineMetres(pLat, pLon, lat, lon)
                        }
                        lastLat = lat
                        lastLon = lon
                    }
                    pendingLat = null
                    pendingLon = null
                }
                "trk" -> {
                    val first = gpxFirst
                    val last = gpxLast
                    if (first != null && last != null && last > first) {
                        workouts += ImportedWorkout(
                            startedMs = first,
                            durationS = (last - first) / 1000,
                            distanceM = gpxMetres.takeIf { it > 0 },
                            activityHint = gpxType,
                            title = gpxName,
                            avgHr = averageHeartRate(),
                            maxHr = hrPeak.takeIf { it in HR_RANGE },
                        )
                    }
                    inTrack = false
                }
            }
        }

        /** Local name, lowercased: exporters differ on case and on prefixes. */
        private fun tag(qName: String) = qName.substringAfter(':').lowercase()

        private fun resetHeartRate() {
            hrSum = 0
            hrCount = 0
            hrPeak = 0
            lapWeighted = 0.0
            lapWeight = 0.0
        }

        /** One per-point reading. Outside what a heart does, it is noise. */
        private fun sample(bpm: Long) {
            if (bpm !in HR_RANGE) return
            hrSum += bpm
            hrCount++
            hrPeak = maxOf(hrPeak, bpm)
        }

        /**
         * Per-point readings when the file has them — the finer source — and
         * otherwise the laps' own averages, weighted by how long each lap was.
         */
        private fun averageHeartRate(): Long? {
            val avg = when {
                hrCount > 0 -> hrSum / hrCount
                lapWeight > 0 -> Math.round(lapWeighted / lapWeight)
                else -> return null
            }
            return avg.takeIf { it in HR_RANGE }
        }
    }

    /** What the vault accepts for a session's heart rate (`0017_sport.sql`). */
    private val HR_RANGE = 20L..300L

    /**
     * ISO-8601, which is what both formats specify and what every exporter
     * actually emits — with or without a fractional second, with `Z` or an
     * offset. A time that will not parse yields null rather than an exception:
     * one malformed point must not lose the whole workout.
     */
    internal fun parseTime(raw: String?): Long? {
        val value = raw?.trim().orEmpty().ifBlank { return null }
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Some exporters omit the zone. Those are local times in practice —
            // a watch writes the time it showed — so read them in the phone's
            // timezone; UTC put every such session one or two hours out.
            try {
                java.time.LocalDateTime.parse(value)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * Great-circle distance between two fixes, in metres.
     *
     * Haversine on a spherical earth: about 0.5% out at worst, which over a
     * 10 km run is 50 m — far below the noise of consumer GPS, and not worth a
     * geodesic library to improve.
     */
    internal fun haversineMetres(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
