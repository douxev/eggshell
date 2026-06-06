import Foundation

// Notification content preferences, mirroring android NotifContentPrefs +
// MedAliasPrefs + (the priority half of) PriorityPrefs.
//
// Privacy-first default is `.generic`: a reminder reveals nothing about which
// medication is due. The user can opt into showing the real medication name
// (`.name`) or a per-medication *fake* alias (`.alias`, e.g. "Vitamines" for
// an estradiol prescription). An alias is a user-picked decoy label, so storing
// it in plain UserDefaults leaks nothing — that is the whole point — and it lets
// the reminder path render something while the vault is locked. The real name
// only ever lands in plain storage / on the lock screen when the user explicitly
// asks for `.name`.
//
// `highPriority` mirrors android's two-channel split: on iOS we translate it to
// the notification `interruptionLevel` (.timeSensitive heads-up vs .passive).

/// What a medication reminder reveals. NEW enum (distinct from the legacy
/// `NotificationContentMode`) so the two can coexist without symbol collisions.
enum NotifContentMode: String, CaseIterable, Identifiable {
    case generic
    case name
    case alias

    var id: String { rawValue }

    /// French label shown in the picker.
    var label: String {
        switch self {
        case .generic: return "Générique"
        case .name:    return "Nom du traitement"
        case .alias:   return "Alias"
        }
    }

    /// French one-line description of the privacy tradeoff.
    var detail: String {
        switch self {
        case .generic: return "Ne révèle rien (« C'est l'heure de votre prise »)."
        case .name:    return "Affiche le vrai nom du traitement."
        case .alias:   return "Affiche un surnom de ton choix par traitement."
        }
    }

    static func from(_ raw: String?) -> NotifContentMode {
        NotifContentMode(rawValue: raw ?? "") ?? .generic
    }
}

/// Persistent notification-content settings. Plain UserDefaults (no medical
/// content: a mode flag, a priority flag, and user-picked decoy aliases).
enum NotifPrefs {
    private static let d = UserDefaults(suiteName: "com.douxev.eggshell.notif") ?? .standard
    private static let keyMode = "content_mode"
    private static let keyHighPriority = "high_priority"
    private static func aliasKey(_ medId: Int64) -> String { "alias_\(medId)" }

    /// Global "what does a medication reminder show?" mode.
    static var contentMode: NotifContentMode {
        get { NotifContentMode.from(d.string(forKey: keyMode)) }
        set { d.set(newValue.rawValue, forKey: keyMode) }
    }

    /// When on, reminders fire as heads-up (time-sensitive); off = passive shade.
    static var highPriority: Bool {
        get { d.bool(forKey: keyHighPriority) }
        set { d.set(newValue, forKey: keyHighPriority) }
    }

    /// The decoy alias for a medication, or nil when none/blank.
    static func alias(for medId: Int64) -> String? {
        guard let raw = d.string(forKey: aliasKey(medId)) else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// Set (or clear, when nil/blank) the decoy alias for a medication.
    static func setAlias(_ alias: String?, for medId: Int64) {
        let trimmed = alias?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if trimmed.isEmpty { d.removeObject(forKey: aliasKey(medId)) }
        else { d.set(trimmed, forKey: aliasKey(medId)) }
    }
}
