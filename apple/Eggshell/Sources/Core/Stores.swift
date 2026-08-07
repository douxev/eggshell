import Foundation
import SwiftUI

// Feature toggles — gate which tabs/quick-log tiles show. Mirrors FeaturesPrefs.
@MainActor
final class FeaturesStore: ObservableObject {
    private let d = UserDefaults(suiteName: "com.douxev.eggshell.features") ?? .standard

    @Published var medications: Bool { didSet { d.set(medications, forKey: "medications") } }
    @Published var journal: Bool     { didSet { d.set(journal, forKey: "journal") } }
    @Published var hormones: Bool    { didSet { d.set(hormones, forKey: "hormones") } }
    @Published var weight: Bool      { didSet { d.set(weight, forKey: "weight") } }
    @Published var photos: Bool      { didSet { d.set(photos, forKey: "photos") } }
    @Published var voice: Bool       { didSet { d.set(voice, forKey: "voice") } }
    /// Bleeding/cycle tracking — opt-in, mirrors android feature_bleeding default-off.
    @Published var bleeding: Bool    { didSet { d.set(bleeding, forKey: "bleeding") } }
    /// Appointments ("RDV") — on by default since the refonte: the doctor
    /// report is reached from « Préparer ma consultation » on this screen and
    /// nowhere else, so leaving it off would strand a fresh install with no path
    /// to the PDF export. Mirrors android feature_appointments.
    @Published var appointments: Bool { didSet { d.set(appointments, forKey: "appointments") } }
    /// Notes — on by default, on updated installs as well as fresh ones. The
    /// key only exists once someone has moved the switch themselves, so one
    /// default covers both. Unlike photos and voice, an empty notebook says
    /// nothing about its owner. Mirrors android feature `show_notes`.
    @Published var notes: Bool { didSet { d.set(notes, forKey: "notes") } }
    /// Dream journal — on by default, for the same reason as Notes: an empty
    /// dream journal says nothing about its owner, and the key only exists once
    /// someone has moved the switch themselves, so one default covers a fresh
    /// install and an updated one.
    @Published var dreams: Bool { didSet { d.set(dreams, forKey: "dreams") } }

    /// A value that changes whenever any module toggle does.
    ///
    /// `onChange` needs something `Equatable` to compare, and there is no
    /// single published property that moves when, say, only `voice` flips.
    /// Used to republish the Home-Screen quick actions the moment a module is
    /// hidden — the whole point of turning one off is that it stops being
    /// visible, and the app icon's long-press menu is the most visible place it
    /// could possibly remain.
    var enabledSignature: String {
        [medications, journal, hormones, weight, photos, voice, bleeding, appointments, notes, dreams]
            .map { $0 ? "1" : "0" }
            .joined()
    }

    private static func read(_ d: UserDefaults, _ k: String, _ def: Bool) -> Bool {
        d.object(forKey: k) == nil ? def : d.bool(forKey: k)
    }

    init() {
        // Pass `d` explicitly: a nested closure capturing self can't be called
        // before all stored properties are initialized.
        medications = Self.read(d, "medications", true)
        journal     = Self.read(d, "journal", true)
        hormones    = Self.read(d, "hormones", true)
        weight      = Self.read(d, "weight", true)
        photos      = Self.read(d, "photos", false)
        voice       = Self.read(d, "voice", false)
        bleeding    = Self.read(d, "bleeding", false)
        appointments = Self.read(d, "appointments", true)
        notes        = Self.read(d, "notes", true)
        dreams       = Self.read(d, "dreams", true)
    }
}

// Per-hormone display unit override. Mirrors HormoneUnitPrefs.
//
// Each measurement is stored in the unit the user typed. This remembers which
// unit to *display* historic values in: explicit user choice, else the
// conventional default per hormone, else nil (show as recorded). A user can
// also opt into "show as recorded" which disables conversion entirely.
@MainActor
final class HormoneUnitStore: ObservableObject {
    private let d = UserDefaults(suiteName: "com.douxev.eggshell.hormones") ?? .standard
    private static let asRecorded = "__as_recorded__"
    private func key(_ h: String) -> String { "unit_\(h)" }

    /// The unit the user explicitly picked, or nil. Most callers want
    /// `effectiveUnit(for:)`.
    func unit(for hormone: String) -> String? {
        let raw = d.string(forKey: key(hormone))
        if raw == Self.asRecorded { return nil }
        return raw
    }

    /// True iff the user opted into "show as recorded" (no conversion).
    func isAsRecorded(for hormone: String) -> Bool {
        d.string(forKey: key(hormone)) == Self.asRecorded
    }

    /// Unit to display in: explicit choice → conventional default → nil.
    func effectiveUnit(for hormone: String) -> String? {
        if isAsRecorded(for: hormone) { return nil }
        if let explicit = unit(for: hormone) { return explicit }
        return HormoneCatalog.defaultUnit(hormone)
    }

    func defaultUnit(for hormone: String) -> String? { HormoneCatalog.defaultUnit(hormone) }

    /// Set the explicit unit. Pass nil/empty to clear back to the default.
    func setUnit(_ unit: String?, for hormone: String) {
        objectWillChange.send()
        if let unit, !unit.isEmpty { d.set(unit, forKey: key(hormone)) }
        else { d.removeObject(forKey: key(hormone)) }
    }

    /// Explicitly disable conversion for this hormone.
    func setAsRecorded(for hormone: String) {
        objectWillChange.send()
        d.set(Self.asRecorded, forKey: key(hormone))
    }
}

// Privacy / security UI flags. Mirrors SecurityPrefs.
@MainActor
final class SecurityFlags: ObservableObject {
    private let d = UserDefaults(suiteName: "com.douxev.eggshell.security") ?? .standard
    @Published var blockScreenshots: Bool { didSet { d.set(blockScreenshots, forKey: "block_screenshots") } }
    init() {
        blockScreenshots = d.object(forKey: "block_screenshots") == nil ? true : d.bool(forKey: "block_screenshots")
    }
}

// "What's new" gate. Mirrors WhatsNewPrefs. The catalog of releases lives in
// WhatsNewCatalog (Settings feature); this only tracks the highest version the
// user has already seen.
@MainActor
final class WhatsNewStore: ObservableObject {
    private let d = UserDefaults(suiteName: "com.douxev.eggshell.whatsnew") ?? .standard
    @Published private(set) var lastSeen: Int
    init() { lastSeen = d.integer(forKey: "last_seen") }

    /// True when `latestVersion` is newer than what the user has seen.
    func shouldShow(latestVersion: Int) -> Bool { latestVersion > lastSeen }

    func markSeen(_ version: Int) {
        guard version > lastSeen else { return }
        lastSeen = version
        d.set(version, forKey: "last_seen")
    }
}

// Selected theme. The actual palette is resolved by Palette.resolve(themeId:dark:)
// in Palettes.swift. `themeId` is read by ThemedRoot to pick the active palette.
@MainActor
final class ThemeStore: ObservableObject {
    private let d = UserDefaults(suiteName: "com.douxev.eggshell.theme") ?? .standard
    @Published var themeId: String { didSet { d.set(themeId, forKey: "theme_id") } }
    init() { themeId = d.string(forKey: "theme_id") ?? "lavender" }
}
