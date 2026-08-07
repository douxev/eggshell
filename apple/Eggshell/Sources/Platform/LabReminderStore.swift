import Foundation

// Lab / photo / voice / journal reminders, mirroring android LabReminderManager
// + LabReminderPrefs. These are recurring "every N days" reminders that are NOT
// tied to a medication schedule. Stored as JSON in UserDefaults (UI preference
// + due dates, not medical content). Shared by RemindersView (CRUD + scheduling)
// and TodayView (upcoming aggregation), so it lives in the foundation.

struct LabReminder: Codable, Identifiable, Hashable {
    var id: String
    var kind: String          // "lab" | "photo" | "voice" | "journal"
    var label: String
    var intervalDays: Int
    var nextDueMs: Int64
    var enabled: Bool
}

enum LabReminderKind {
    static let lab = "lab"
    static let photo = "photo"
    static let voice = "voice"
    static let journal = "journal"
    /// Dream journal. Recall collapses within minutes of waking, so this is
    /// the one reminder whose *time* is the whole feature.
    static let dream = "dream"

    static func label(_ kind: String) -> String {
        switch kind {
        case lab:     return "Bilan sanguin"
        case photo:   return "Photo de suivi"
        case voice:   return "Clip vocal"
        case journal: return "Journal du jour"
        case dream:   return "Au réveil"
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
        default:      return "bell"
        }
    }
}

@MainActor
final class LabReminderStore: ObservableObject {
    private let d = UserDefaults(suiteName: "com.douxev.eggshell.labreminders") ?? .standard
    private let key = "items"
    @Published private(set) var items: [LabReminder] = []

    init() { items = load() }

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
    /// Advance a reminder's next-due by its interval (after it fired / was done).
    func advance(id: String, from: Date = Date()) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        let days = max(1, items[i].intervalDays)
        let next = Calendar.current.date(byAdding: .day, value: days, to: from) ?? from
        items[i].nextDueMs = Int64(next.timeIntervalSince1970 * 1000)
        persist()
    }

    /// Upcoming enabled reminders (sorted by due date) for the Today aggregation.
    func upcoming() -> [LabReminder] {
        items.filter { $0.enabled }.sorted { $0.nextDueMs < $1.nextDueMs }
    }
}
