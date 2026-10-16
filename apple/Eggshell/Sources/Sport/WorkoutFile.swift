import Foundation
import TransitionCore

/// One workout read out of a file a watch exported.
///
/// Deliberately small: what a sport session in this app actually holds, and
/// nothing else. A GPX track carries every coordinate of where someone went,
/// and importing those into a vault that exists to protect people from being
/// tracked would be an odd thing to do without being asked — so the route is
/// read, used to work out how far they went, and dropped.
struct ImportedWorkout: Identifiable, Hashable {
    /// Its own identity, not the start time: two workouts of one file can
    /// share a start, and a list keyed on it shows one of them twice.
    let id = UUID()
    let startedMs: Int64
    let durationS: Int64
    /// Metres, when the file says or the route allows it to be computed.
    let distanceM: Double?
    /// The file's own word for the activity ("Running", "cycling"), if any.
    let activityHint: String?
    /// What the exporter called it. Used as the session note, never invented.
    let title: String?
    /// Average and peak heart rate when the file carries them — per point
    /// (TCX `HeartRateBpm`, GPX `gpxtpx:hr`) or per lap — and nil rather than
    /// a number the vault would refuse (outside 20–300).
    var avgHr: Int64? = nil
    var maxHr: Int64? = nil
}

/// Reads workouts out of the files watches export — the iOS port of Android's
/// `WorkoutFileParser`, on Foundation's `XMLParser`.
///
/// **Why files, and not a vendor SDK.** Every watch maker offers an app that
/// syncs through their own servers, which is exactly the thing this app must
/// not do. A file the user exported themselves involves no account, no
/// network, no background service and no third party — it works with Garmin,
/// Polar, Suunto, Coros, Amazfit, Samsung and anything else that can write GPX
/// or TCX, and it keeps working when a vendor shuts an API down.
///
/// **TCX before GPX where both exist.** TCX states the duration and the
/// distance as recorded by the watch; GPX carries only timestamped points, so
/// both have to be derived — the duration from first and last point (which
/// counts pauses as exercise) and the distance from the coordinates (which is
/// a lower bound, since a track is a polyline through a curve).
///
/// Streaming, not a DOM: a two-hour ride with 20,000 track points does not
/// become 20,000 objects in memory.
enum WorkoutFileParser {

    /// Suffixes worth offering in a file picker.
    static let supportedExtensions = ["gpx", "tcx", "fit"]

    /// Whether `data` is a FIT file: a 12- or 14-byte header whose bytes 8–11
    /// spell ".FIT". Decided from the content, like the XML formats.
    static func isFit(_ data: Data) -> Bool {
        let b = [UInt8](data.prefix(12))
        guard b.count == 12, b[0] == 12 || b[0] == 14 else { return false }
        return b[8] == 0x2E && b[9] == 0x46 && b[10] == 0x49 && b[11] == 0x54
    }

    /// Any supported file: GPX and TCX are XML and parsed here, `.fit` is
    /// binary and decoded by the Rust core — once for both apps, the same
    /// decoder Android uses.
    static func parseAny(_ data: Data) -> [ImportedWorkout] {
        guard isFit(data) else { return parse(data) }
        return parseFitWorkouts(data: data).map {
            ImportedWorkout(
                startedMs: $0.startedMs,
                durationS: $0.durationS,
                distanceM: $0.distanceM,
                activityHint: $0.sport,
                title: $0.title,
                avgHr: $0.avgHr,
                maxHr: $0.maxHr)
        }
    }

    /// Parse whatever is in `data`. Returns every workout the file describes —
    /// a TCX can hold several — or an empty list when it holds none.
    ///
    /// The format is decided by what the document actually contains, not by
    /// the file name: exports arrive named `.xml`, named `activity(3).gpx`, and
    /// named nothing at all when they come through a share sheet.
    static func parse(_ data: Data) -> [ImportedWorkout] {
        let parser = XMLParser(data: data)
        // A workout file is data, never a document to go and resolve. An
        // exported .gpx arrives from wherever the user got it, and trusting a
        // DOCTYPE is the classic way a parser starts reading files it was
        // never given.
        parser.shouldResolveExternalEntities = false
        parser.shouldProcessNamespaces = false
        let handler = WorkoutHandler()
        parser.delegate = handler
        parser.parse()
        return handler.workouts
    }

    /// ISO-8601, which is what both formats specify and what every exporter
    /// actually emits — with or without a fractional second, with `Z` or an
    /// offset. A time that will not parse yields nil rather than an error: one
    /// malformed point must not lose the whole workout.
    static func parseTime(_ raw: String?) -> Int64? {
        guard let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            return nil
        }
        if let date = isoWithFraction.date(from: value) ?? isoPlain.date(from: value) {
            return Int64(date.timeIntervalSince1970 * 1000)
        }
        // Some exporters omit the zone. Those are the time the watch showed,
        // so they are read in the phone's timezone — UTC put every such
        // session an hour or two out.
        let local = localFormatter
        local.timeZone = .current
        for format in ["yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss"] {
            local.dateFormat = format
            if let date = local.date(from: value) {
                return Int64(date.timeIntervalSince1970 * 1000)
            }
        }
        return nil
    }

    // Built once: parseTime runs for every point of a track, and a formatter
    // per call made a two-hour ride tens of thousands of allocations. Parsing
    // happens on one thread at a time, which is all these need.
    private static let isoWithFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let isoPlain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()
    private static let localFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    /// What the vault accepts for a session's heart rate (`0017_sport.sql`).
    static let heartRateRange: ClosedRange<Int64> = 20...300

    /// Great-circle distance between two fixes, in metres.
    ///
    /// Haversine on a spherical earth: about 0.5 % out at worst, which over a
    /// 10 km run is 50 m — far below the noise of consumer GPS, and not worth a
    /// geodesic library to improve.
    static func haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double) -> Double {
        let r = 6_371_000.0
        let dLat = (lat2 - lat1) * .pi / 180
        let dLon = (lon2 - lon1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /// One pass over the document, accumulating whichever of the two shapes it
    /// turns out to be. Both handled together rather than sniffing the root
    /// element first: their element names do not overlap, a file is only ever
    /// one of the two, and deciding up front would mean parsing it twice.
    private final class WorkoutHandler: NSObject, XMLParserDelegate {
        var workouts: [ImportedWorkout] = []

        private var text = ""

        // TCX
        private var tcxSport: String?
        private var tcxStart: Int64?
        private var tcxSeconds = 0.0
        private var tcxMetres = 0.0
        private var inActivity = false
        /// Inside a `<Trackpoint>`. A `<Lap>` states its own distance, while a
        /// `<Trackpoint>` states the running total for the whole activity;
        /// adding both makes a 5 km run come out as 5 km plus every
        /// intermediate reading.
        private var inTrackpoint = false

        /// Open elements, innermost last. Heart rate hides in a `<Value>`
        /// whose meaning depends on its parent, so the parent has to be known
        /// when the value closes.
        private var open: [String] = []

        // Heart rate, shared by both formats.
        private var hrSum: Int64 = 0
        private var hrCount: Int64 = 0
        private var hrPeak: Int64 = 0
        private var lapSeconds = 0.0
        private var lapAvg: Int64?
        private var lapWeighted = 0.0
        private var lapWeight = 0.0

        // GPX
        private var gpxName: String?
        private var gpxType: String?
        private var gpxFirst: Int64?
        private var gpxLast: Int64?
        private var gpxMetres = 0.0
        private var lastLat: Double?
        private var lastLon: Double?
        private var pendingLat: Double?
        private var pendingLon: Double?
        private var inTrack = false

        func parser(
            _ parser: XMLParser, didStartElement elementName: String,
            namespaceURI: String?, qualifiedName qName: String?,
            attributes attrs: [String: String] = [:]
        ) {
            text = ""
            let name = tag(elementName)
            open.append(name)
            switch name {
            case "activity":
                inActivity = true
                tcxSport = attrs["Sport"]
                tcxStart = nil
                tcxSeconds = 0
                tcxMetres = 0
                resetHeartRate()
            case "lap":
                if inActivity && tcxStart == nil {
                    tcxStart = WorkoutFileParser.parseTime(attrs["StartTime"])
                }
                lapSeconds = 0
                lapAvg = nil
            case "trk":
                inTrack = true
                gpxName = nil
                gpxType = nil
                gpxFirst = nil
                gpxLast = nil
                gpxMetres = 0
                lastLat = nil
                lastLon = nil
                resetHeartRate()
            case "trkseg":
                // A new segment is a new stretch of recording: the straight
                // line across the gap was never travelled.
                lastLat = nil
                lastLon = nil
            case "trkpt":
                pendingLat = attrs["lat"].flatMap { Double($0) }
                pendingLon = attrs["lon"].flatMap { Double($0) }
            case "trackpoint":
                inTrackpoint = true
            default:
                break
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            text += string
        }

        func parser(
            _ parser: XMLParser, didEndElement elementName: String,
            namespaceURI: String?, qualifiedName qName: String?
        ) {
            let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
            text = ""
            let name = tag(elementName)
            if open.last == name { open.removeLast() }
            let parent = open.last
            switch name {
            // -- Heart rate ------------------------------------------------
            case "value":
                guard let bpm = Double(value).map({ Int64($0) }) else { break }
                switch parent {
                case "heartratebpm" where inTrackpoint:
                    sample(bpm)
                case "averageheartratebpm" where !inTrackpoint:
                    lapAvg = bpm
                case "maximumheartratebpm" where !inTrackpoint:
                    if WorkoutFileParser.heartRateRange.contains(bpm) { hrPeak = Swift.max(hrPeak, bpm) }
                default:
                    break
                }
            case "hr", "heartrate":
                if inTrack && pendingLat != nil, let bpm = Double(value).map({ Int64($0) }) { sample(bpm) }

            // -- TCX -------------------------------------------------------
            case "id":
                if inActivity && tcxStart == nil { tcxStart = WorkoutFileParser.parseTime(value) }
            case "totaltimeseconds":
                if inActivity {
                    let seconds = Double(value) ?? 0
                    tcxSeconds += seconds
                    lapSeconds += seconds
                }
            case "lap":
                if let avg = lapAvg, WorkoutFileParser.heartRateRange.contains(avg), lapSeconds > 0 {
                    lapWeighted += Double(avg) * lapSeconds
                    lapWeight += lapSeconds
                }
            case "distancemeters":
                // Laps only — see inTrackpoint.
                if inActivity && !inTrackpoint { tcxMetres += Double(value) ?? 0 }
            case "trackpoint":
                inTrackpoint = false
            case "activity":
                if inActivity, let start = tcxStart, tcxSeconds > 0 {
                    workouts.append(ImportedWorkout(
                        startedMs: start,
                        durationS: Int64(tcxSeconds),
                        distanceM: tcxMetres > 0 ? tcxMetres : nil,
                        activityHint: tcxSport,
                        title: nil,
                        avgHr: averageHeartRate(),
                        maxHr: WorkoutFileParser.heartRateRange.contains(hrPeak) ? hrPeak : nil))
                }
                inActivity = false

            // -- GPX -------------------------------------------------------
            case "name":
                if inTrack && gpxName == nil && !value.isEmpty { gpxName = value }
            case "type":
                if inTrack && gpxType == nil && !value.isEmpty { gpxType = value }
            case "time":
                if inTrack && pendingLat != nil, let at = WorkoutFileParser.parseTime(value) {
                    if gpxFirst == nil { gpxFirst = at }
                    gpxLast = at
                }
            case "trkpt":
                if let lat = pendingLat, let lon = pendingLon {
                    if let pLat = lastLat, let pLon = lastLon {
                        gpxMetres += WorkoutFileParser.haversineMetres(
                            lat1: pLat, lon1: pLon, lat2: lat, lon2: lon)
                    }
                    lastLat = lat
                    lastLon = lon
                }
                pendingLat = nil
                pendingLon = nil
            case "trk":
                if let first = gpxFirst, let last = gpxLast, last > first {
                    workouts.append(ImportedWorkout(
                        startedMs: first,
                        durationS: (last - first) / 1000,
                        distanceM: gpxMetres > 0 ? gpxMetres : nil,
                        activityHint: gpxType,
                        title: gpxName,
                        avgHr: averageHeartRate(),
                        maxHr: WorkoutFileParser.heartRateRange.contains(hrPeak) ? hrPeak : nil))
                }
                inTrack = false
            default:
                break
            }
        }

        /// Local name, lowercased: exporters differ on case and on prefixes.
        private func tag(_ name: String) -> String {
            let local = name.split(separator: ":").last.map(String.init) ?? name
            return local.lowercased()
        }

        private func resetHeartRate() {
            hrSum = 0; hrCount = 0; hrPeak = 0
            lapWeighted = 0; lapWeight = 0
        }

        /// One per-point reading. Outside what a heart does, it is noise.
        private func sample(_ bpm: Int64) {
            guard WorkoutFileParser.heartRateRange.contains(bpm) else { return }
            hrSum += bpm
            hrCount += 1
            hrPeak = Swift.max(hrPeak, bpm)
        }

        /// Per-point readings when the file has them, otherwise the laps' own
        /// averages weighted by how long each lap was.
        private func averageHeartRate() -> Int64? {
            let avg: Int64
            if hrCount > 0 {
                avg = hrSum / hrCount
            } else if lapWeight > 0 {
                avg = Int64((lapWeighted / lapWeight).rounded())
            } else {
                return nil
            }
            return WorkoutFileParser.heartRateRange.contains(avg) ? avg : nil
        }
    }
}

/// Turns a file a watch exported into sport sessions — the iOS port of
/// Android's `WatchImporter`.
enum WatchImporter {

    /// What an import would do, shown before anything is written.
    struct Preview: Identifiable {
        let workouts: [ImportedWorkout]
        /// Workouts whose start time already has a session.
        let duplicates: Set<Int64>
        var importable: [ImportedWorkout] { workouts.filter { !duplicates.contains($0.startedMs) } }
        var id: String { "\(workouts.count)-\(duplicates.count)" }
    }

    /// Read a file and say what is in it, without writing anything.
    ///
    /// A preview rather than a straight import, because the user is the one
    /// who knows whether they already logged this ride by hand, and because a
    /// file picked by mistake should cost a glance rather than a cleanup.
    static func preview(_ data: Data, session: VaultService) async -> Preview {
        let workouts = WorkoutFileParser.parseAny(data)
        return Preview(workouts: workouts, duplicates: await alreadyPresent(workouts, session: session))
    }

    /// Write the workouts, matching each to one of the user's own activity
    /// types. Returns how many were written. Types are matched, never
    /// created: inventing "Running" in someone's catalogue because their watch
    /// used that word would be the app deciding how they name their own
    /// training.
    static func importWorkouts(
        _ workouts: [ImportedWorkout], activities: [SportActivity], session: VaultService
    ) async -> Int {
        var written = 0
        for workout in workouts {
            let activityId = matchActivity(hint: workout.activityHint, activities: activities)?.id
            let new = NewSportSession(
                activityId: activityId,
                startedMs: workout.startedMs,
                durationS: workout.durationS,
                freeText: workout.title,
                distanceM: workout.distanceM,
                avgHr: workout.avgHr,
                maxHr: workout.maxHr,
                source: SportStore.sourceWatch)
            if (try? await session.addSportSession(new)) != nil { written += 1 }
        }
        return written
    }

    /// Start times that already have a session. Exact, not fuzzy: a watch
    /// reports the same start instant for the same activity every time it is
    /// exported, so re-importing the same file is caught — while two genuinely
    /// different sessions that happen to fall near each other are left alone.
    private static func alreadyPresent(_ workouts: [ImportedWorkout], session: VaultService) async -> Set<Int64> {
        guard let from = workouts.map(\.startedMs).min(),
              let to = workouts.map(\.startedMs).max() else { return [] }
        let existing = Set(((try? await session.listSportSessionsBetween(fromMs: from, toMs: to + 1)) ?? [])
            .map(\.startedMs))
        return Set(workouts.map(\.startedMs).filter { existing.contains($0) })
    }

    /// Which of the user's activity types a workout belongs to, if any.
    ///
    /// Case-insensitive, and containment in both directions, because exporters
    /// are inconsistent: "Running", "running", "Trail Running" and "Course a
    /// pied" all turn up for the same thing. No match yields nil and the
    /// session is filed with no type — honest, visible, and one tap to fix.
    /// Archived types are never matched: the user put those away.
    static func matchActivity(hint: String?, activities: [SportActivity]) -> SportActivity? {
        guard let hint else { return nil }
        let needle = normalize(hint)
        guard !needle.isEmpty else { return nil }
        let live = activities.filter { !$0.archived }
            .map { ($0, normalize($0.name)) }
            .filter { !$0.1.isEmpty }
        if let hit = live.first(where: { $0.1 == needle })
            ?? live.first(where: { needle.contains($0.1) })
            ?? live.first(where: { $0.1.contains(needle) }) {
            return hit.0
        }
        // Watches speak English ("Running", "Biking"), the catalogue is usually
        // in French (« Course à pied », « Vélo »): go through the family both
        // words belong to. Same families as Android's `WatchImporter`.
        guard let family = families.first(where: { words in words.contains { needle.contains($0) } })
        else { return nil }
        return live.first(where: { pair in family.contains { pair.1.contains($0) } })?.0
    }

    /// Lowercase, accents off, separators to spaces: « Vélo_route » ≈ "velo route".
    private static func normalize(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "fr_FR"))
            .lowercased()
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
    }

    private static let families: [[String]] = [
        ["running", "run", "jogging", "footing", "course", "trail"],
        ["biking", "cycling", "bike", "ride", "velo", "cyclisme", "vtt"],
        ["walking", "walk", "marche", "hiking", "hike", "randonnee", "rando"],
        ["swimming", "swim", "natation", "nage", "piscine"],
        ["strength", "weight", "musculation", "muscu", "renfo"],
        ["rowing", "aviron"],
        ["yoga"],
        ["elliptical", "elliptique"],
        ["ski"],
    ]
}
