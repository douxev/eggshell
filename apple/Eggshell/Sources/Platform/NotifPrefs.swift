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
    private static let keyGroupMeds = "group_med_reminders"
    private static func aliasKey(_ medId: Int64) -> String { "alias_\(medId)" }
    private static func medPriorityKey(_ scheduleId: Int64) -> String { "priority_med_\(scheduleId)" }

    /// Global "what does a medication reminder show?" mode.
    static var contentMode: NotifContentMode {
        get { NotifContentMode.from(d.string(forKey: keyMode)) }
        set { d.set(newValue.rawValue, forKey: keyMode) }
    }

    /// When on, reminders fire as heads-up (time-sensitive); off = passive shade.
    /// The default for every reminder that has no priority of its own.
    static var highPriority: Bool {
        get { d.bool(forKey: keyHighPriority) }
        set { d.set(newValue, forKey: keyHighPriority) }
    }

    // MARK: Per-reminder priority (android PriorityPrefs)
    //
    // Three states, not two: an explicit « Prioritaire » on or off for one
    // medication schedule, or nothing — in which case the global switch above
    // decides. Nothing stored is what every schedule had before this existed,
    // so they all keep following the global switch exactly as they did.

    /// The schedule's own choice, or nil when it follows `highPriority`.
    static func medPriorityOverride(_ scheduleId: Int64) -> Bool? {
        d.object(forKey: medPriorityKey(scheduleId)) as? Bool
    }

    /// Set, or with nil forget, a schedule's own priority.
    static func setMedPriority(_ value: Bool?, for scheduleId: Int64) {
        if let value { d.set(value, forKey: medPriorityKey(scheduleId)) }
        else { d.removeObject(forKey: medPriorityKey(scheduleId)) }
    }

    /// What a reminder for this schedule actually uses.
    static func isMedPriority(_ scheduleId: Int64) -> Bool {
        medPriorityOverride(scheduleId) ?? highPriority
    }

    /// « Grouper les rappels de traitement »: medication reminders due within
    /// the same 15 minutes arrive as one notification (see
    /// `NotificationManager.reschedule`). Off by default — an unset key reads
    /// false — so nothing changes for anyone who has not asked for it.
    /// Mirrors android `group_med_reminders`.
    static var groupMedReminders: Bool {
        get { d.bool(forKey: keyGroupMeds) }
        set { d.set(newValue, forKey: keyGroupMeds) }
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
