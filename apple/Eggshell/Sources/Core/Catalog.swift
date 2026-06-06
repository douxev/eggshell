import Foundation

// Canonical medication / hormone identifiers + their French labels.
//
// IMPORTANT — shared-DB contract: these identifier lists MUST stay byte-for-byte
// identical to the Android `MedicationCatalog` / `HormoneCatalog` (same Rust
// core + same `vault.db` schema). A medication created on one platform is read
// on the other; diverging identifiers silently corrupt cross-platform display.
// Identifiers are stable; the labels are presentation-only.

enum MedCatalog {
    /// Clinical medication kinds stored in `medications.kind`. Matches
    /// android MedicationCatalog.KINDS exactly.
    static let kinds = [
        "estrogen",
        "progesterone",
        "testosterone",
        "anti_androgen",
        "gnrh_agonist",
        "supplement",
        "other",
    ]

    /// Administration routes stored in `medications.route`. Matches
    /// android MedicationCatalog.ROUTES exactly (note: includes "suppository").
    static let routes = [
        "oral",
        "sublingual",
        "topical",
        "transdermal",
        "injection_im",
        "injection_sc",
        "suppository",
        "other",
    ]

    static func kindLabel(_ id: String) -> String {
        switch id {
        case "estrogen":      return "Œstrogène"
        case "progesterone":  return "Progestérone"
        case "testosterone":  return "Testostérone"
        case "anti_androgen": return "Anti-androgène"
        case "gnrh_agonist":  return "Agoniste GnRH"
        case "supplement":    return "Supplément"
        default:              return "Autre"
        }
    }

    static func routeLabel(_ id: String) -> String {
        switch id {
        case "oral":         return "Oral"
        case "sublingual":   return "Sublingual"
        case "topical":      return "Topique (gel/crème)"
        case "transdermal":  return "Patch"
        case "injection_im": return "Injection IM"
        case "injection_sc": return "Injection SC"
        case "suppository":  return "Suppositoire"
        default:             return "Autre"
        }
    }

    /// Maps a canonical injection-site identifier (as returned by the Rust core's
    /// `standardInjectionSites()` and stored on dose events) to a French label.
    static func injectionSiteLabel(_ id: String) -> String {
        switch id {
        case "thigh_left":          return "Cuisse gauche"
        case "thigh_right":         return "Cuisse droite"
        case "abdomen_left_upper":  return "Ventre haut gauche"
        case "abdomen_right_upper": return "Ventre haut droit"
        case "abdomen_left_lower":  return "Ventre bas gauche"
        case "abdomen_right_lower": return "Ventre bas droit"
        case "glute_left":          return "Fesse gauche"
        case "glute_right":         return "Fesse droite"
        case "deltoid_left":        return "Épaule gauche"
        case "deltoid_right":       return "Épaule droite"
        default:                    return "Autre"
        }
    }

    static func isInjection(_ route: String) -> Bool {
        route == "injection_im" || route == "injection_sc"
    }
}

enum HormoneCatalog {
    /// Hormones surfaced in the Hormones tab. Weight uses the same storage
    /// backend but lives in its own UI, so it is not in this list.
    static let kinds = [
        "estradiol", "progesterone", "testosterone",
        "lh", "fsh", "prolactin", "shbg", "other",
    ]

    /// Stable identifier used in `hormone_measurements` to store weight entries.
    static let weight = "weight"

    static let units = [
        "pg/mL", "pmol/L", "ng/dL", "nmol/L", "ng/mL", "mIU/mL", "other",
    ]

    static let weightUnits = ["kg", "lb"]

    static func kindLabel(_ id: String) -> String {
        switch id {
        case "estradiol":    return "Œstradiol"
        case "progesterone": return "Progestérone"
        case "testosterone": return "Testostérone"
        case "lh":           return "LH"
        case "fsh":          return "FSH"
        case "prolactin":    return "Prolactine"
        case "shbg":         return "SHBG"
        case weight:         return "Poids"
        default:             return "Autre"
        }
    }

    /// Local kg ↔ lb conversion — the Rust core's `convertHormoneValue` doesn't
    /// know about weight. Returns nil for unrecognised units (caller falls back
    /// to the raw value). Mirrors android HormoneCatalog.convertWeight.
    static func convertWeight(_ value: Double, from: String, to: String) -> Double? {
        switch (from, to) {
        case let (f, t) where f == t: return value
        case ("kg", "lb"): return value * 2.20462
        case ("lb", "kg"): return value / 2.20462
        default: return nil
        }
    }

    /// Conventional per-hormone display-unit defaults for trans HRT monitoring.
    /// Mirrors android HormoneUnitPrefs.DEFAULTS. Returns nil when there is no
    /// conventional default (the UI then shows the value as recorded).
    static func defaultUnit(_ hormone: String) -> String? {
        switch hormone {
        case "estradiol":    return "pg/mL"
        case "testosterone": return "ng/dL"
        case "progesterone": return "ng/mL"
        case "lh":           return "mIU/mL"
        case "fsh":          return "mIU/mL"
        case "prolactin":    return "ng/mL"
        case "shbg":         return "nmol/L"
        default:             return nil
        }
    }
}
