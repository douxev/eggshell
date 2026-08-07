import UIKit

/// The modules that advertise themselves outside the app.
///
/// One table, read by the shortcut publisher and by the launch router, so a
/// module cannot end up with a shortcut that opens the wrong screen — or, far
/// worse, keep publishing one after the module was switched off.
enum AppModule: String, CaseIterable {
    case meds
    case journal
    case labs
    case appointments
    case notes
    case weight
    case bleeding
    case photos
    case voice

    var title: String {
        switch self {
        case .meds: return "Médics"
        case .journal: return "Journal"
        case .labs: return "Analyses"
        case .appointments: return "RDV"
        case .notes: return "Notes"
        case .weight: return "Poids"
        case .bleeding: return "Menstruations"
        case .photos: return "Photos"
        case .voice: return "Voix"
        }
    }

    /// SF Symbols chosen to match the launcher tiles inside the app.
    var symbol: String {
        switch self {
        case .meds: return "pills.fill"
        case .journal: return "face.smiling"
        case .labs: return "testtube.2"
        case .appointments: return "calendar"
        case .notes: return "doc.text.fill"
        case .weight: return "ruler.fill"
        case .bleeding: return "drop.fill"
        case .photos: return "camera.fill"
        case .voice: return "waveform"
        }
    }

    /// Order the shortcuts appear in, most-used first. iOS shows at most four.
    var rank: Int { Self.allCases.firstIndex(of: self) ?? 0 }

    /// `FeaturesStore` is `@MainActor`, so reading its toggles has to be too.
    /// Both callers already are — the shell and the publisher.
    @MainActor
    func isEnabled(_ features: FeaturesStore) -> Bool {
        switch self {
        case .meds: return features.medications
        case .journal: return features.journal
        case .labs: return features.hormones
        case .appointments: return features.appointments
        case .notes: return features.notes
        // Weight is a sub-feature of Courbes: its own toggle governs it, but it
        // cannot outlive the module that hosts its screen.
        case .weight: return features.hormones && features.weight
        case .bleeding: return features.bleeding
        case .photos: return features.photos
        case .voice: return features.voice
        }
    }
}

/// Publishes one Home-Screen quick action per enabled module — and publishes
/// none at all while a decoy PIN or a disguised icon is in play.
///
/// **Why the gate is the whole design.** A long-press on the app icon shows its
/// quick actions to whoever is holding the phone, with no unlock of any kind. A
/// list reading « Médics · Analyses · Menstruations » under an icon claiming to
/// be a calculator answers, in one gesture, the exact question the decoy exists
/// to refuse — the facade would survive the PIN prompt and fall at the Home
/// Screen.
///
/// So the rule is not "hide the labels" but "publish nothing": an empty
/// `shortcutItems` leaves the icon with the bare system menu any app without
/// quick actions has. Absence is the only state that reveals nothing, because a
/// list of generic entries is itself a tell — a plain notes app has no reason
/// to hide what its shortcuts do.
///
/// The disguised-icon case goes past the letter of "hide under decoy" but not
/// past its reason: an icon reading « Calculatrice » whose long-press menu
/// offers the real module list has not hidden the app, it has annotated it.
///
/// This is the iOS half of Android's `ModuleShortcuts`. The **widgets** half is
/// not here: a WidgetKit widget needs its own extension target, its own bundle
/// identifier registered with Apple and its own provisioning profile, none of
/// which can be added from the repository alone.
enum ModuleShortcuts {

    /// Set on the quick action so the launch router can tell one of ours from
    /// anything else the system hands us.
    static let typePrefix = "com.douxev.eggshell.module."

    @MainActor
    static func refresh(features: FeaturesStore) {
        guard !isHidden else {
            UIApplication.shared.shortcutItems = []
            return
        }
        UIApplication.shared.shortcutItems = AppModule.allCases
            .filter { $0.isEnabled(features) }
            .sorted { $0.rank < $1.rank }
            // iOS surfaces four at most and silently drops the rest; taking the
            // head of a ranked list means the user loses the least-used
            // modules rather than an arbitrary handful.
            .prefix(4)
            .map { module in
                UIApplicationShortcutItem(
                    type: typePrefix + module.rawValue,
                    localizedTitle: module.title,
                    localizedSubtitle: nil,
                    icon: UIApplicationShortcutIcon(systemImageName: module.symbol),
                    userInfo: nil)
            }
    }

    /// Drop every quick action, whatever the current settings say.
    @MainActor
    static func clear() {
        UIApplication.shared.shortcutItems = []
    }

    /// True when a decoy PIN is configured or the icon is wearing a disguise.
    ///
    /// `AppIconManager.current` reads `UIApplication`, so this has to be on the
    /// main actor — which every caller already is.
    @MainActor
    private static var isHidden: Bool {
        if VaultPrefs().hasDecoyPin { return true }
        return AppIconManager.current != .default
    }

    /// The module a launch shortcut names, or nil when it names none we know.
    ///
    /// Unknown types are dropped rather than guessed at: the shortcut type is
    /// the only thing travelling from outside the app, so it is the only thing
    /// that has to be validated.
    static func module(for item: UIApplicationShortcutItem) -> AppModule? {
        guard item.type.hasPrefix(typePrefix) else { return nil }
        return AppModule(rawValue: String(item.type.dropFirst(typePrefix.count)))
    }
}
