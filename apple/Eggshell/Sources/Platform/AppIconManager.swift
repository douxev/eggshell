import UIKit

// App-icon disguise, mirroring android AppAliasManager (Notes / Calculatrice /
// Météo). On iOS this uses alternate app icons (UIApplication.setAlternateIconName).
//
// ⚠️ Requires icon image assets + an Info.plist `CFBundleIcons.CFBundleAlternateIcons`
// entry per variant (see apple/WidgetEnable/README if present, or the project
// docs). Until those PNGs are bundled, `available` is false and the picker is
// shown disabled — the code path is complete and will work as soon as the assets
// land. No entitlement or provisioning change is required for alternate icons.
enum AppIconVariant: String, CaseIterable, Identifiable {
    case `default` = "default"
    case notes
    case calculator
    case weather

    var id: String { rawValue }

    /// The alternate-icon name registered in Info.plist, or nil for the primary.
    var iconName: String? {
        switch self {
        case .default:    return nil
        case .notes:      return "AppIcon-Notes"
        case .calculator: return "AppIcon-Calculator"
        case .weather:    return "AppIcon-Weather"
        }
    }

    var label: String {
        switch self {
        case .default:    return "eggshell"
        case .notes:      return "Notes"
        case .calculator: return "Calculatrice"
        case .weather:    return "Météo"
        }
    }
    var systemImage: String {
        switch self {
        case .default:    return "leaf.fill"
        case .notes:      return "note.text"
        case .calculator: return "plus.forwardslash.minus"
        case .weather:    return "cloud.sun.fill"
        }
    }
}

@MainActor
enum AppIconManager {
    /// True when the device + bundle support switching icons.
    static var available: Bool { UIApplication.shared.supportsAlternateIcons }

    static var current: AppIconVariant {
        let name = UIApplication.shared.alternateIconName
        return AppIconVariant.allCases.first { $0.iconName == name } ?? .default
    }

    static func set(_ variant: AppIconVariant) async throws {
        guard available else { return }
        guard variant != current else { return }
        try await UIApplication.shared.setAlternateIconName(variant.iconName)
    }
}
