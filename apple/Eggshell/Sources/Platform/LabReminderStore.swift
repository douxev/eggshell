import Foundation
import UIKit

// Lab / photo / voice / journal / dream / sport reminders, mirroring android
// LabReminderManager + LabReminderPrefs. Recurring reminders that are NOT tied
// to a medication schedule: "every N days", or "every day at HH:MM". Stored as
// JSON in UserDefaults (UI preference + due dates, not medical content).
// Shared by RemindersView (CRUD + scheduling), the sport module's reminder
// screen and the home's upcoming aggregation, so it lives in the foundation.

struct LabReminder: Codable, Identifiable, Hashable {
    var id: String
    var kind: String          // "lab" | "photo" | "voice" | "journal" | "dream" | "sport"
    var label: String
    /// Every N days. A daily reminder keeps 1 here, so a build that predates
    /// the daily variant still reads it as "every day" rather than garbage.
    var intervalDays: Int
    var nextDueMs: Int64
    var enabled: Bool
    /// « Chaque jour à HH:MM » — Android's `kind = "daily"`. Both nil for an
    /// every-N-days reminder.
    ///
    /// Optional and defaulted on purpose: every reminder stored before this
    /// existed has neither key, and synthesized `Codable` reads a missing
    /// optional as nil — so they decode exactly as they always did, and are
    /// re-encoded without the keys. Purely additive, no migration.
    var dailyHour: Int? = nil
    var dailyMinute: Int? = nil
    /// « Prioritaire » for this reminder alone (android PriorityPrefs), or nil
    /// to follow the global `NotifPrefs.highPriority`. Optional for the same
    /// reason as the daily fields: older reminders decode unchanged.
    var highPriority: Bool? = nil

    /// What this reminder's notification actually uses.
    var isPriority: Bool { highPriority ?? NotifPrefs.highPriority }

    var isDaily: Bool { dailyHour != nil && dailyMinute != nil }

    /// The first occurrence strictly after `now`, keeping the reminder's phase.
    ///
    /// Every N days steps forward from the stored due date in whole calendar
    /// days, so a reminder set for 09:00 stays at 09:00 across a DST change
    /// and across weeks of the app not being opened. Daily is the next HH:MM.
    func nextOccurrence(after now: Date, calendar: Calendar = .current) -> Date {
        if let h = dailyHour, let m = dailyMinute {
            return calendar.nextDate(
                after: now, matching: DateComponents(hour: h, minute: m),
                matchingPolicy: .nextTime) ?? now.addingTimeInterval(86_400)
        }
        let due = Date(timeIntervalSince1970: Double(nextDueMs) / 1000)
        guard due <= now else { return due }
        let step = max(1, intervalDays)
        // Jump close in one go rather than walking day by day: a reminder left
        // alone for a year is one call, not hundreds.
        let elapsed = calendar.dateComponents([.day], from: due, to: now).day ?? 0
        var steps = max(0, elapsed / step)
        var candidate = calendar.date(byAdding: .day, value: steps * step, to: due) ?? due
        while candidate <= now {
            steps += 1
            guard let next = calendar.date(byAdding: .day, value: steps * step, to: due) else {
                return now.addingTimeInterval(Double(step) * 86_400)
            }
            candidate = next
        }
        return candidate
    }

    /// « Tous les 7 j » or « Chaque jour à 09:00 », plus « · désactivé ».
    var cadenceLabel: String {
        let base: String
        if let h = dailyHour, let m = dailyMinute {
            base = String(format: "Chaque jour à %02d:%02d", h, m)
        } else {
            base = "Tous les \(max(1, intervalDays)) j"
        }
        return enabled ? base : base + " · désactivé"
    }
}

enum LabReminderKind {
    static let lab = "lab"
    static let photo = "photo"
    static let voice = "voice"
    static let journal = "journal"
    /// Dream journal. Recall collapses within minutes of waking, so this is
    /// the one reminder whose *time* is the whole feature.
    static let dream = "dream"
    /// Session reminders for the sport module. A kind rather than a store of
    /// its own: this file already IS the generic non-medication reminder
    /// store, and the only thing a kind changes is which innocuous title the
    /// lock screen shows. Mirrors Android's CATEGORY_SPORT.
    static let sport = "sport"

    static func label(_ kind: String) -> String {
        switch kind {
        case lab:     return "Bilan sanguin"
        case photo:   return "Photo de suivi"
        case voice:   return "Clip vocal"
        case journal: return "Journal du jour"
        case dream:   return "Au réveil"
        case sport:   return "Séance de sport"
        default:      return "Rappel"
        }
    }
    static func systemImage(_ kind: String) -> String {
        switch kind {
        case lab:     return "drop.triangle"
        case photo:   return "camera"
        case voice:   return "waveform"
        case journal: return "square.and.pencil"
        case dream:   return "moon.zzz"
        case sport:   return "figure.run"
        default:      return "bell"
        }
    }
    /// What « Tous les N jours » starts at for a new reminder. A blood test is
    /// a quarterly thing; a training session is a weekly one, and starting a
    /// session reminder at 90 days would be a default nobody keeps.
    static func defaultIntervalDays(_ kind: String) -> Int {
        kind == sport ? 7 : 90
    }
}

@MainActor
final class LabReminderStore: ObservableObject {
    /// The app's one store, for the unlock path in `AppState`, which has no
    /// environment to read it from. Weak, like `AppState.shared`.
    static weak var shared: LabReminderStore?

    private let d = UserDefaults(suiteName: "com.douxev.eggshell.labreminders") ?? .standard
    private let key = "items"
    @Published private(set) var items: [LabReminder] = []
    private var foregroundObserver: NSObjectProtocol?

    init() {
        items = load()
        Self.shared = self
        // Back from the background is when a reminder that fired while the app
        // was away needs its next date. The store outlives every screen (it is
        // an app-level object), so the observer is never removed.
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    private func load() -> [LabReminder] {
        guard let data = d.data(forKey: key),
              let all = try? JSONDecoder().decode([LabReminder].self, from: data) else { return [] }
        return all
    }
    private func persist() {
        if let data = try? JSONEncoder().encode(items) { d.set(data, forKey: key) }
    }

    func upsert(_ r: LabReminder) {
        if let i = items.firstIndex(where: { $0.id == r.id }) { items[i] = r }
        else { items.append(r) }
        persist()
    }
    func delete(id: String) {
        items.removeAll { $0.id == id }
        persist()
    }
    /// Move one reminder's next-due to its first occurrence after `from`,
    /// keeping its phase (see `LabReminder.nextOccurrence`).
    func advance(id: String, from: Date = Date()) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        items[i].nextDueMs = Self.ms(items[i].nextOccurrence(after: from))
        persist()
    }

    /// Move every reminder whose due date has passed to its next occurrence,
    /// and persist. Returns whether anything moved.
    ///
    /// iOS pre-schedules notifications and never tells the app one fired, so
    /// nothing else ever moves these dates. Without this, an every-N-days
    /// reminder fired once and was then stuck in the past for good — and the
    /// next reschedule re-fired it a minute later, every time.
    @discardableResult
    func rearmOverdue(now: Date = Date()) -> Bool {
        let nowMs = Self.ms(now)
        var moved = false
        for i in items.indices where items[i].nextDueMs <= nowMs {
            items[i].nextDueMs = Self.ms(items[i].nextOccurrence(after: now))
            moved = true
        }
        if moved { persist() }
        return moved
    }

    /// Re-arm overdue dates, then rebuild the pending notifications from them.
    /// Called on a real unlock, on return to the foreground, and after any
    /// edit — the one path every change goes through.
    func refresh() {
        rearmOverdue()
        let all = items
        Task { await NotificationManager.scheduleLabReminders(all) }
    }

    private static func ms(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }

    /// Upcoming enabled reminders (sorted by due date) for the Today aggregation.
    func upcoming() -> [LabReminder] {
        items.filter { $0.enabled }.sorted { $0.nextDueMs < $1.nextDueMs }
    }
}
