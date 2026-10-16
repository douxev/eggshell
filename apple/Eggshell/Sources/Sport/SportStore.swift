import Foundation
import TransitionCore

/// The sport module's settings that live outside the vault, mirroring
/// Android's `SportPrefs`: whether the pedometer is on, and the daily goal.
///
/// Off by default, and deliberately a second decision after enabling the
/// module: turning this on is what makes the app ask for Motion access, and
/// that should follow an explicit "yes, count my steps" rather than merely
/// "yes, I do sport". Neither value says anything about anyone, so plain
/// UserDefaults is fine.
@MainActor
final class SportPrefs: ObservableObject {
    private let d = UserDefaults(suiteName: "com.douxev.eggshell.sport") ?? .standard

    @Published var pedometer: Bool { didSet { d.set(pedometer, forKey: "pedometer") } }
    /// « Importer depuis Santé (Apple) ». Off by default and, like the
    /// pedometer, a second decision after enabling the module: turning it on
    /// is what asks for Health access. Only ever shown when the build carries
    /// the HealthKit entitlement (`HealthKitImporter.isEnabledInBuild`).
    @Published var healthImport: Bool { didSet { d.set(healthImport, forKey: "health_import") } }
    @Published var dailyGoal: Int {
        didSet {
            let clamped = min(max(dailyGoal, Self.minGoal), Self.maxGoal)
            if clamped != dailyGoal { dailyGoal = clamped; return }
            d.set(dailyGoal, forKey: "daily_goal")
        }
    }

    init() {
        pedometer = d.object(forKey: "pedometer") == nil ? false : d.bool(forKey: "pedometer")
        healthImport = d.bool(forKey: "health_import")
        let stored = d.object(forKey: "daily_goal") == nil ? Self.defaultGoal : d.integer(forKey: "daily_goal")
        dailyGoal = min(max(stored, Self.minGoal), Self.maxGoal)
    }

    // MARK: Statics, for code that runs outside the view tree

    private static var defaults: UserDefaults {
        UserDefaults(suiteName: "com.douxev.eggshell.sport") ?? .standard
    }

    /// The unlock hook runs before any view exists, so it reads the switch
    /// straight from the store rather than through an instance.
    static func pedometerIsOn() -> Bool { defaults.bool(forKey: "pedometer") }

    static func healthImportIsOn() -> Bool { defaults.bool(forKey: "health_import") }

    /// When the last Santé import finished, or nil before the first. A date
    /// and nothing else: what was imported is in the vault.
    static func healthLastSync() -> Date? {
        let ms = defaults.object(forKey: "health_last_sync_ms") as? NSNumber
        return ms.map { Date(timeIntervalSince1970: $0.doubleValue / 1000) }
    }

    static func setHealthLastSync(_ date: Date) {
        defaults.set(NSNumber(value: Int64(date.timeIntervalSince1970 * 1000)), forKey: "health_last_sync_ms")
    }

    /// Per-day gaps between what Core Motion reports and what the user
    /// corrected the day to — see `StepCounter.correct`. A difference of two
    /// counts, keyed by day; it says less than the vault already does. Only
    /// the last eight days are kept: Core Motion forgets after seven, and so
    /// can this.
    static func stepOffsets() -> [String: Int64] {
        (defaults.dictionary(forKey: "step_offsets") as? [String: NSNumber])?
            .mapValues { $0.int64Value } ?? [:]
    }

    static func setStepOffset(_ offset: Int64, forDayKey key: String) {
        var all = stepOffsets()
        all[key] = offset
        let horizon = SportFormat.dayKey(
            Calendar.current.date(byAdding: .day, value: -8, to: Date()) ?? Date())
        all = all.filter { $0.key >= horizon }
        defaults.set(all.mapValues { NSNumber(value: $0) }, forKey: "step_offsets")
    }

    /// 8000, not the folklore 10000 — that number came from the brand name of a
    /// 1960s Japanese pedometer, and the health research behind it tops out
    /// well below. A goal someone never reaches is a goal that makes them feel
    /// worse for having walked, which is the opposite of the point.
    static let defaultGoal = 8_000
    static let minGoal = 500
    static let maxGoal = 50_000
    /// The goals most people pick; anything else goes in the field.
    static let presets = [5_000, 8_000, 10_000, 12_000]
}

/// The four categories the core recognises. Free-form in SQL, closed here.
enum SportKind: String, CaseIterable, Identifiable {
    case cardio, strength, mobility, other

    var id: String { rawValue }

    var label: String {
        switch self {
        case .cardio:   return "Cardio"
        case .strength: return "Renfo"
        case .mobility: return "Souplesse"
        case .other:    return "Autre"
        }
    }

    static func of(_ key: String) -> SportKind { SportKind(rawValue: key) ?? .other }
}

/// The arithmetic behind the Sport dashboard, as pure functions — the same
/// rules as Android's `SportStats`, so the two apps never disagree about
/// whether someone's run is still alive.
///
/// These numbers are the ones people take personally: telling someone their
/// streak broke when it did not is worse than showing nothing.
enum SportStats {

    /// Consecutive days up to and including today that have a session.
    ///
    /// **Today not having one yet does not break the streak.** A streak counted
    /// strictly from today would read zero every morning until the person
    /// trained, which is both wrong and discouraging at the exact moment it is
    /// read. So the count starts from today if today has a session, and from
    /// yesterday otherwise; only a gap before that ends it.
    static func currentStreak(sessionDays: Set<Date>, today: Date, calendar: Calendar = .current) -> Int {
        let todayStart = calendar.startOfDay(for: today)
        var day = sessionDays.contains(todayStart)
            ? todayStart
            : calendar.date(byAdding: .day, value: -1, to: todayStart) ?? todayStart
        var streak = 0
        while sessionDays.contains(day) {
            streak += 1
            guard let previous = calendar.date(byAdding: .day, value: -1, to: day) else { break }
            day = previous
        }
        return streak
    }

    /// The longest run of consecutive days ever recorded.
    static func longestStreak(sessionDays: Set<Date>, calendar: Calendar = .current) -> Int {
        var best = 0
        for day in sessionDays {
            // Only count from the start of a run, so each run is walked once
            // rather than once per day in it.
            if let before = calendar.date(byAdding: .day, value: -1, to: day), sessionDays.contains(before) {
                continue
            }
            var length = 0
            var cursor = day
            while sessionDays.contains(cursor) {
                length += 1
                guard let next = calendar.date(byAdding: .day, value: 1, to: cursor) else { break }
                cursor = next
            }
            best = max(best, length)
        }
        return best
    }

    /// The local days (start of day) on which any session started.
    static func sessionDays(_ sessions: [SportSession], calendar: Calendar = .current) -> Set<Date> {
        Set(sessions.map { calendar.startOfDay(for: Date(timeIntervalSince1970: Double($0.startedMs) / 1000)) })
    }

    /// Total minutes trained in `[from, to)` — half-open, matching the core's
    /// range query, so "this week" and "last week" never both count the
    /// session that sits on the boundary.
    static func minutesBetween(_ sessions: [SportSession], fromMs: Int64, toMs: Int64) -> Int64 {
        sessions
            .filter { $0.startedMs >= fromMs && $0.startedMs < toMs }
            .reduce(0) { $0 + $1.durationS } / 60
    }

    static func countBetween(_ sessions: [SportSession], fromMs: Int64, toMs: Int64) -> Int {
        sessions.filter { $0.startedMs >= fromMs && $0.startedMs < toMs }.count
    }

    /// `[start, end)` of the week holding `date`, where the week starts where
    /// the user's locale says it does, not on a fixed Monday: "this week" that
    /// disagrees with their calendar is a number they cannot check.
    static func weekBounds(containing date: Date, calendar: Calendar = .current) -> (fromMs: Int64, toMs: Int64) {
        let interval = calendar.dateInterval(of: .weekOfYear, for: date)
            ?? DateInterval(start: calendar.startOfDay(for: date), duration: 7 * 86_400)
        return (Int64(interval.start.timeIntervalSince1970 * 1000),
                Int64(interval.end.timeIntervalSince1970 * 1000))
    }
}

/// The step calendar's numbers — what a pedometer like Stepsy shows under its
/// month — with the same rules as Android's `StepStats`.
///
/// A day with no record is not a day of zero steps: the pedometer may have
/// been off, the phone in a drawer. Averages run over the days that have a
/// count. A streak is different: it is a run of days that reached the goal,
/// and a day with no count did not.
enum StepStats {

    struct Month: Equatable {
        var total: Int64 = 0
        var average: Int64 = 0
        var daysAtGoal = 0
        var best: Int64 = 0
        var daysRecorded = 0
    }

    static func month(_ steps: [Date: Int64], goal: Int) -> Month {
        let counted = steps.values.filter { $0 > 0 }
        let total = counted.reduce(0, +)
        return Month(
            total: total,
            average: counted.isEmpty ? 0 : total / Int64(counted.count),
            daysAtGoal: goal > 0 ? counted.filter { $0 >= Int64(goal) }.count : 0,
            best: counted.max() ?? 0,
            daysRecorded: counted.count)
    }

    /// Consecutive days at or above the goal, up to today. Today not there
    /// *yet* does not break it — at 9 a.m. nobody has reached their goal.
    static func goalStreak(_ steps: [Date: Int64], goal: Int, today: Date, calendar: Calendar = .current) -> Int {
        guard goal > 0 else { return 0 }
        let met: (Date) -> Bool = { (steps[$0] ?? 0) >= Int64(goal) }
        let start = calendar.startOfDay(for: today)
        var day = met(start) ? start : (calendar.date(byAdding: .day, value: -1, to: start) ?? start)
        var streak = 0
        while met(day) {
            streak += 1
            guard let previous = calendar.date(byAdding: .day, value: -1, to: day) else { break }
            day = previous
        }
        return streak
    }

    static func bestGoalStreak(_ steps: [Date: Int64], goal: Int, calendar: Calendar = .current) -> Int {
        guard goal > 0 else { return 0 }
        return SportStats.longestStreak(
            sessionDays: Set(steps.filter { $0.value >= Int64(goal) }.keys), calendar: calendar)
    }

    /// This month's average against last month's, in whole percent, or nil
    /// when there is nothing to compare with.
    static func change(current: Int64, previous: Int64) -> Int? {
        guard previous > 0, current > 0 else { return nil }
        return Int((Double(current - previous) * 100 / Double(previous)).rounded())
    }
}

/// Number and duration formatting the sport screens share.
enum SportFormat {

    /// "1 h 05" or "45 min". Never seconds — nobody logs a session to the second.
    static func duration(seconds: Int64) -> String {
        let minutes = seconds / 60
        if minutes >= 60 {
            return String(format: "%d h %02d", minutes / 60, minutes % 60)
        }
        return "\(minutes) min"
    }

    /// "5,0 km" or "800 m". Kilometres from one on, which is where people switch.
    static func distance(metres: Double) -> String {
        if metres >= 1_000 {
            let f = NumberFormatter()
            f.locale = Locale(identifier: "fr_FR")
            f.minimumFractionDigits = 1
            f.maximumFractionDigits = 1
            return (f.string(from: NSNumber(value: metres / 1_000)) ?? "\(metres / 1_000)") + " km"
        }
        return "\(Int(metres)) m"
    }

    /// "5 230", grouped the French way.
    static func integer(_ value: Int) -> String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.numberStyle = .decimal
        return f.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    /// "mar. 2 sept., 18:30".
    static func when(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    /// The `YYYY-MM-DD` key the core stores steps under, in local time. Built
    /// here because only this side knows the user's timezone.
    static func dayKey(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func date(fromDayKey key: String, calendar: Calendar = .current) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        var c = DateComponents()
        c.year = parts[0]; c.month = parts[1]; c.day = parts[2]
        return calendar.date(from: c).map { calendar.startOfDay(for: $0) }
    }
}

/// The sport module's data access, one thin layer over `VaultService` — the
/// rules live in the Rust core (`transition-core/src/sport.rs`). What this side
/// genuinely owns is turning local dates into the `YYYY-MM-DD` keys the core
/// stores steps under.
enum SportStore {

    static let sourceManual = "manual"
    static let sourceWatch = "watch"
    /// Imported from Santé (HealthKit). The same string Android writes for a
    /// Health Connect import, so a backup moved between platforms reads alike.
    static let sourceHealth = "health"

    static func activities(_ session: VaultService, includeArchived: Bool = false) async -> [SportActivity] {
        (try? await session.listSportActivities(includeArchived: includeArchived)) ?? []
    }

    static func sessions(_ session: VaultService, limit: Int64 = 1_000) async -> [SportSession] {
        (try? await session.listSportSessions(offset: 0, limit: limit)) ?? []
    }

    static func stepDays(_ session: VaultService, from: Date, to: Date) async -> [Date: Int64] {
        let days = (try? await session.listStepDays(
            fromKey: SportFormat.dayKey(from), toKey: SportFormat.dayKey(to))) ?? []
        var out: [Date: Int64] = [:]
        for d in days {
            if let date = SportFormat.date(fromDayKey: d.dayKey) { out[date] = d.steps }
        }
        return out
    }

    static func todaySteps(_ session: VaultService) async -> Int64 {
        (try? await session.getStepDay(dayKey: SportFormat.dayKey(Date())))?.steps ?? 0
    }
}
