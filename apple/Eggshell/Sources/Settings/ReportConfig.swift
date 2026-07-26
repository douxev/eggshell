import Foundation

// The doctor report's *configuration*: the period, the eight modules, and the
// little that is remembered between two visits (§6.12, §9).
//
// Nothing here reads the vault and nothing here draws: it is the value the
// export screen edits, the builder consumes and the Rendez-vous card quotes in
// its subtitle. Only *choices* are stored — never a count, never the date of
// anything that happened, only the two bounds the user picked.

/// The five period pills of §6.12.
enum ReportPeriod: String, CaseIterable, Identifiable {
    case m1, m3, m6, all, custom

    var id: String { rawValue }

    var label: String {
        switch self {
        case .m1:     return "1 mois"
        case .m3:     return "3 mois"
        case .m6:     return "6 mois"
        case .all:    return "Tout"
        case .custom: return "Personnalisé"
        }
    }
}

/// What produced a custom range. Not a duplicate of `ReportPeriod`: it is what
/// lets the recap line *name* the period (« depuis la dernière consultation »)
/// instead of only dating it.
enum ReportShortcut: String, CaseIterable, Identifiable {
    case week1, week2, lastVisit, treatmentStart, manual

    var id: String { rawValue }

    /// Chip label of the « Période personnalisée » sheet. `manual` has none —
    /// it is the state you land in by typing the dates yourself.
    var label: String {
        switch self {
        case .week1:          return "1 semaine"
        case .week2:          return "2 semaines"
        case .lastVisit:      return "Depuis la dernière consultation"
        case .treatmentStart: return "Depuis le début du traitement"
        case .manual:         return "Dates choisies"
        }
    }

    /// The four chips actually offered, in order. `manual` is a state, not a
    /// choice, so it is not one of them.
    static let offered: [ReportShortcut] = [.week1, .week2, .lastVisit, .treatmentStart]
}

/// An ordered pair of bounds. Handed them backwards every window filter would
/// come back empty and the title would read from the future to the past, so
/// they are put back in order rather than guessed at.
struct ReportRange: Hashable {
    let fromMs: Int64
    let toMs: Int64

    init(fromMs: Int64, toMs: Int64) {
        self.fromMs = min(fromMs, toMs)
        self.toMs = max(fromMs, toMs)
    }

    /// Whole days covered, never negative.
    var days: Int {
        let cal = Calendar.current
        let a = cal.startOfDay(for: Date(timeIntervalSince1970: Double(fromMs) / 1000))
        let b = cal.startOfDay(for: Date(timeIntervalSince1970: Double(toMs) / 1000))
        return max(0, cal.dateComponents([.day], from: a, to: b).day ?? 0)
    }

    func contains(_ atMs: Int64) -> Bool { atMs >= fromMs && atMs <= toMs }
}

/// The eight exportable modules, and nothing else (§6.12.4).
struct ReportModules: Hashable {
    var medications = true
    var hormones = true
    var weight = true
    var feel = true
    var questions = true
    var bleeding = false
    var voice = true
    /// Never on by default. The only module with that rule (§6.12.4).
    var photos = false

    var flags: [Bool] {
        [medications, hormones, weight, feel, questions, bleeding, voice, photos]
    }

    var activeCount: Int { flags.filter { $0 }.count }

    /// The estimate the button shows, `1 + ceil(n / 3)` (§6.12.6). It is an
    /// estimate on purpose: the renderer knows the real count once it has laid
    /// the document out, and that is the one the footer prints.
    var pages: Int { 1 + Int(ceil(Double(activeCount) / 3.0)) }
}

/// The export configuration, remembered between visits.
///
/// Before the refonte nothing was persisted on either platform: every visit
/// reset the period and re-checked every module, which is exactly the kind of
/// default that gets a photo into a document by accident.
///
/// The report's *identity block* is pointedly not here. A name and a date of
/// birth are the two things that would make this file identifying, and
/// `UserDefaults` is not encrypted — they live in the vault instead, see
/// `ReportIdentityStore`.
enum ReportPrefs {
    private static let d = UserDefaults(suiteName: "com.douxev.eggshell.report") ?? .standard

    private static let keyPeriod = "period"
    private static let keyShortcut = "shortcut"
    private static let keyFrom = "custom_from"
    private static let keyTo = "custom_to"
    private static let moduleKeys = [
        "m_meds", "m_hormones", "m_weight", "m_feel",
        "m_questions", "m_bleeding", "m_voice", "m_photos",
    ]

    static var period: ReportPeriod {
        get { ReportPeriod(rawValue: d.string(forKey: keyPeriod) ?? "") ?? .m3 }
        set { d.set(newValue.rawValue, forKey: keyPeriod) }
    }

    static var shortcut: ReportShortcut {
        get { ReportShortcut(rawValue: d.string(forKey: keyShortcut) ?? "") ?? .lastVisit }
        set { d.set(newValue.rawValue, forKey: keyShortcut) }
    }

    /// Zero means « never set »; the screen then falls back to the shortcut.
    static var customFromMs: Int64 {
        get { Int64(d.integer(forKey: keyFrom)) }
        set { d.set(Int(newValue), forKey: keyFrom) }
    }

    static var customToMs: Int64 {
        get { Int64(d.integer(forKey: keyTo)) }
        set { d.set(Int(newValue), forKey: keyTo) }
    }

    static var modules: ReportModules {
        get {
            let fallback = ReportModules()
            let defaults = fallback.flags
            var stored: [Bool] = []
            for (index, key) in moduleKeys.enumerated() {
                stored.append(d.object(forKey: key) == nil ? defaults[index] : d.bool(forKey: key))
            }
            return ReportModules(
                medications: stored[0], hormones: stored[1], weight: stored[2], feel: stored[3],
                questions: stored[4], bleeding: stored[5], voice: stored[6], photos: stored[7])
        }
        set {
            for (index, key) in moduleKeys.enumerated() {
                d.set(newValue.flags[index], forKey: key)
            }
        }
    }
}

/// Turns a pill (plus, for `custom`, a shortcut and its anchors) into bounds.
enum ReportPeriodResolver {

    /// - Parameters:
    ///   - lastVisitMs: the most recent appointment already past, if any.
    ///   - treatmentStartMs: the oldest medication sheet, if any.
    ///   - earliestMs: the oldest datum the vault holds, of **any** kind — dose
    ///     events included. What « Tout » means, and the only honest floor for
    ///     it. `nil` when the caller has not resolved it.
    static func range(
        period: ReportPeriod,
        shortcut: ReportShortcut,
        customFromMs: Int64,
        customToMs: Int64,
        lastVisitMs: Int64?,
        treatmentStartMs: Int64?,
        earliestMs: Int64? = nil,
        now: Int64 = Time.nowMs()
    ) -> ReportRange {
        switch period {
        case .m1:  return ReportRange(fromMs: back(months: 1, from: now), toMs: now)
        case .m3:  return ReportRange(fromMs: back(months: 3, from: now), toMs: now)
        case .m6:  return ReportRange(fromMs: back(months: 6, from: now), toMs: now)
        // « Tout » is genuinely unbounded — a cutoff of a few years is a silent
        // truncation in a document handed to a doctor — but unbounded is not the
        // epoch: starting at zero made the H1, the subtitle, the recap line and
        // the file name all say « 1 janvier 1970 » and « 20659 jours ».
        case .all: return ReportRange(fromMs: earliestMs ?? 0, toMs: now)
        case .custom:
            if customFromMs > 0 && customToMs > 0 {
                return ReportRange(fromMs: customFromMs, toMs: customToMs)
            }
            return shortcutRange(
                shortcut,
                lastVisitMs: lastVisitMs,
                treatmentStartMs: treatmentStartMs,
                now: now)
        }
    }

    static func shortcutRange(
        _ shortcut: ReportShortcut,
        lastVisitMs: Int64?,
        treatmentStartMs: Int64?,
        now: Int64 = Time.nowMs()
    ) -> ReportRange {
        switch shortcut {
        case .week1: return ReportRange(fromMs: back(days: 7, from: now), toMs: now)
        case .week2: return ReportRange(fromMs: back(days: 14, from: now), toMs: now)
        case .lastVisit:
            // No past consultation yet: fall back to the treatment's own start
            // rather than to an arbitrary number of weeks.
            let from = lastVisitMs ?? treatmentStartMs ?? back(months: 3, from: now)
            return ReportRange(fromMs: from, toMs: now)
        case .treatmentStart:
            return ReportRange(fromMs: treatmentStartMs ?? back(months: 6, from: now), toMs: now)
        case .manual:
            return ReportRange(fromMs: back(months: 3, from: now), toMs: now)
        }
    }

    /// How the recap line *names* the period, lowercase so it reads inside a
    /// sentence (« 91 jours · depuis la dernière consultation »).
    static func origin(period: ReportPeriod, shortcut: ReportShortcut, manual: Bool) -> String {
        switch period {
        case .m1:  return "1 dernier mois"
        case .m3:  return "3 derniers mois"
        case .m6:  return "6 derniers mois"
        case .all: return "tout l'historique"
        case .custom:
            if manual { return "période personnalisée" }
            switch shortcut {
            case .week1:          return "1 dernière semaine"
            case .week2:          return "2 dernières semaines"
            case .lastVisit:      return "depuis la dernière consultation"
            case .treatmentStart: return "depuis le début du traitement"
            case .manual:         return "période personnalisée"
            }
        }
    }

    private static func back(months: Int, from now: Int64) -> Int64 {
        let date = Date(timeIntervalSince1970: Double(now) / 1000)
        let moved = Calendar.current.date(byAdding: .month, value: -months, to: date) ?? date
        return Int64(moved.timeIntervalSince1970 * 1000)
    }

    private static func back(days: Int, from now: Int64) -> Int64 {
        let date = Date(timeIntervalSince1970: Double(now) / 1000)
        let moved = Calendar.current.date(byAdding: .day, value: -days, to: date) ?? date
        return Int64(moved.timeIntervalSince1970 * 1000)
    }
}

/// How much of each module the chosen period actually holds. Every one of these
/// is a real count over the range — the subtitles of §6.12.4 announce a volume,
/// so they must never announce one the document cannot deliver.
struct ReportVolumes: Hashable {
    var molecules = 0
    var doses = 0
    var labs = 0
    var weights = 0
    var feelEntries = 0
    var questions = 0
    var questionsDate: String?
    var bleedingDays = 0
    var clips = 0
    var photos = 0

    /// « Aucune donnée sur la période » is the universal empty wording (§5.3).
    static let empty = "Aucune donnée sur la période"

    static func plural(_ count: Int, _ singular: String, _ plural: String) -> String {
        "\(count) " + (count <= 1 ? singular : plural)
    }
}
