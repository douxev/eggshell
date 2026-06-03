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

    init() {
        func read(_ k: String, _ def: Bool) -> Bool { d.object(forKey: k) == nil ? def : d.bool(forKey: k) }
        medications = read("medications", true)
        journal     = read("journal", true)
        hormones    = read("hormones", true)
        weight      = read("weight", true)
        photos      = read("photos", false)
        voice       = read("voice", false)
    }
}

// Per-hormone display unit override. Mirrors HormoneUnitPrefs.
@MainActor
final class HormoneUnitStore: ObservableObject {
    private let d = UserDefaults(suiteName: "com.douxev.eggshell.hormones") ?? .standard
    func unit(for hormone: String) -> String? { d.string(forKey: "unit_\(hormone)") }
    func setUnit(_ unit: String?, for hormone: String) {
        objectWillChange.send()
        if let unit { d.set(unit, forKey: "unit_\(hormone)") } else { d.removeObject(forKey: "unit_\(hormone)") }
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

// "What's new" gate. Mirrors WhatsNewPrefs.
@MainActor
enum WhatsNewStore {
    private static let d = UserDefaults(suiteName: "com.douxev.eggshell.whatsnew") ?? .standard
    static var lastSeen: Int { get { d.integer(forKey: "last_seen") } set { d.set(newValue, forKey: "last_seen") } }
}

// Selected theme (only Lavender ships first; the other palettes get ported later).
@MainActor
final class ThemeStore: ObservableObject {
    private let d = UserDefaults(suiteName: "com.douxev.eggshell.theme") ?? .standard
    @Published var themeId: String { didSet { d.set(themeId, forKey: "theme_id") } }
    init() { themeId = d.string(forKey: "theme_id") ?? "lavender" }
}
