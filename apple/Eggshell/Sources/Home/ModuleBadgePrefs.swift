import Foundation

/// The eight tiles of the launcher, in reading order (§6.1.6, §7.2).
enum LauncherModule: String, CaseIterable, Hashable {
    case meds, appointments, journal, bleeding, labs, weight, photos, voice
}

/// When each launcher module was last opened.
///
/// A badge clears as soon as its module is opened, so all we need to persist is
/// the timestamp of the last visit; the home compares it against the newest
/// item of the module. Timestamps only — nothing that would tell a forensic
/// reader of the preferences directory anything about the app's content.
///
/// A static namespace like `NotifPrefs`, not an `ObservableObject`: the home
/// view model re-reads it on every refresh, and nothing else consumes it.
enum ModuleBadgePrefs {
    private static let d = UserDefaults(suiteName: "com.douxev.eggshell.modules") ?? .standard

    static func lastOpened(_ module: LauncherModule) -> Int64 {
        Int64(d.integer(forKey: module.rawValue))
    }

    static func markOpened(_ module: LauncherModule, atMs: Int64 = Time.nowMs()) {
        d.set(Int(atMs), forKey: module.rawValue)
    }
}
