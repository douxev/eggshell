import SwiftUI
import TransitionCore

// The wording and the glyphs of Médics, in one place.
//
// The list, the detail card and the history pills all read this table, so an
// intake can never be called « à l'heure » on one screen and « +18 min » on the
// next. Colours are always tokens: the app ships 14 palettes.

enum MedFormat {
    /// Separator of the refonte: a middle dot, breathing on both sides.
    static let sep = " · "

    /// Trims the trailing `.0` a Double picks up on its way to the screen.
    static func dose(_ value: Double) -> String {
        value == value.rounded() ? String(Int(value)) : String(format: "%g", value)
    }

    /// « 2 mg », or just « 2 » when the treatment carries no unit.
    static func doseWithUnit(_ value: Double?, _ unit: String?) -> String? {
        guard let value else { return nil }
        let amount = dose(value)
        guard let unit, !unit.trimmingCharacters(in: .whitespaces).isEmpty else { return amount }
        return "\(amount) \(unit)"
    }

    /// A percentage with the no-break space French typography asks for.
    static func percent(_ value: Int) -> String { "\(value)\u{00A0}%" }

    /// Tile glyph of a treatment: a syringe for anything that goes through a
    /// needle, a pill otherwise (§6.4, in SF Symbols).
    static func routeIcon(_ route: String) -> String {
        if MedCatalog.isInjection(route) { return "syringe.fill" }
        switch route {
        case "transdermal": return "bandage.fill"
        case "topical":     return "drop.fill"
        case "suppository": return "capsule.fill"
        default:            return "pills.fill"
        }
    }

    /// « Chaque jour à 08:00 » and its two siblings — the three kinds the core
    /// supports. Written out here rather than reusing `NextDueCalculator
    /// .describe`, which speaks the terser language of a reminder row.
    static func cadence(_ s: DoseSchedule) -> String {
        cadence(
            kind: s.kind,
            intervalMinutes: s.intervalMinutes.map { Int($0) },
            dailyHour: s.dailyHour.map { Int($0) },
            dailyMinute: s.dailyMinute.map { Int($0) },
            intervalDays: s.intervalDays.map { Int($0) })
    }

    /// The same sentence from loose parts, so the reminder form can preview the
    /// cadence it is about to save in the very words the detail card will use.
    static func cadence(
        kind: String,
        intervalMinutes: Int?,
        dailyHour: Int?,
        dailyMinute: Int?,
        intervalDays: Int?
    ) -> String {
        switch kind {
        case "interval":
            let minutes = intervalMinutes ?? 0
            return minutes % 60 == 0
                ? "Toutes les \(minutes / 60) h"
                : "Toutes les \(minutes) min"
        case "daily":
            return String(format: "Chaque jour à %02d:%02d", dailyHour ?? 0, dailyMinute ?? 0)
        case "days_interval":
            return String(format: "Tous les %d jours à %02d:%02d",
                          intervalDays ?? 1, dailyHour ?? 0, dailyMinute ?? 0)
        default:
            return "Planning"
        }
    }

    /// « Aujourd'hui · 08:04 », « Hier · 21:47 », « Dimanche · 08:00 », then the
    /// plain date. A relative day is what you actually remember.
    static func dayAndTime(_ atMs: Int64, now: Date = Date()) -> String {
        let locale = Locale(identifier: "fr_FR")
        let date = Date(timeIntervalSince1970: Double(atMs) / 1000)
        let cal = Calendar.current
        let days = cal.dateComponents(
            [.day], from: cal.startOfDay(for: date), to: cal.startOfDay(for: now)).day ?? 0

        let day: String
        switch days {
        case 0:
            day = "Aujourd'hui"
        case 1:
            day = "Hier"
        case 2...6:
            let f = DateFormatter()
            f.locale = locale
            f.dateFormat = "EEEE"
            day = f.string(from: date).capitalizedFirst
        default:
            let f = DateFormatter()
            f.locale = locale
            f.dateStyle = .medium
            f.timeStyle = .none
            day = f.string(from: date)
        }

        let t = DateFormatter()
        t.locale = locale
        t.dateFormat = "HH:mm"
        return day + sep + t.string(from: date)
    }

    /// Short wall-clock time, the way the reminder rows spell it.
    static func time(hour: Int, minute: Int) -> String {
        String(format: "%02d:%02d", hour, minute)
    }
}

private extension String {
    /// French weekday names come back lowercase; a sentence starts upright.
    var capitalizedFirst: String {
        guard let first else { return self }
        return String(first).uppercased() + dropFirst()
    }
}

/// How an intake sits against its prescribed time. `unlinked` is an intake with
/// no planned time at all — every dose recorded before punctuality existed, and
/// every ad-hoc one. We never guess which occurrence it belonged to (D2).
enum MedTiming {
    case onTime, late, missed, skipped, unlinked
}

/// The « glyphe **et** couleur **et** mot » triplet of §10, resolved once.
/// Nothing here is ever carried by colour alone.
struct MedTimingStyle {
    let systemImage: String
    let glyph: Color
    let container: Color
    let content: Color
    let word: String

    static func of(_ timing: MedTiming, deltaMin: Int?, palette: Palette) -> MedTimingStyle {
        switch timing {
        case .onTime:
            return MedTimingStyle(
                systemImage: "checkmark.circle.fill",
                glyph: palette.tertiary,
                container: palette.surfaceContainerHighest,
                content: palette.onSurfaceVariant,
                word: Punctuality.text(Punctuality.exactLabel(deltaMin)))
        case .late:
            return MedTimingStyle(
                systemImage: "checkmark.circle.fill",
                glyph: palette.secondary,
                container: palette.secondaryContainer,
                content: palette.onSecondaryContainer,
                word: Punctuality.text(Punctuality.exactLabel(deltaMin)))
        case .missed:
            return MedTimingStyle(
                systemImage: "xmark.circle",
                glyph: palette.error,
                container: palette.errorContainer,
                content: palette.onErrorContainer,
                word: "manquée")
        case .skipped:
            return MedTimingStyle(
                systemImage: "xmark.circle",
                glyph: palette.error,
                container: palette.errorContainer,
                content: palette.onErrorContainer,
                word: "passée")
        case .unlinked:
            return MedTimingStyle(
                systemImage: "checkmark.circle.fill",
                glyph: palette.onSurfaceVariant,
                container: palette.surfaceContainerHighest,
                content: palette.onSurfaceVariant,
                word: "notée")
        }
    }
}

/// Preset accent colours, stored as opaque ARGB (0xFFRRGGBB) so the swatch the
/// user picked renders identically on Android from the shared vault. These are
/// *data*, not theme: they are the one place a literal colour is correct.
enum MedSwatch {
    static let all: [Int64] = [
        0xFFE5_7373, 0xFFBA_68C8, 0xFF95_75CD, 0xFF79_86CB, 0xFF4F_C3F7,
        0xFF4D_B6AC, 0xFF81_C784, 0xFFFF_D54F, 0xFFFF_B74D, 0xFF90_A4AE,
    ]
}
