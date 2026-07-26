import SwiftUI
import TransitionCore

// Reached from **Apparence & langue** (§2.4): the unit each analyte is *displayed*
// in. Every measurement stays stored in the unit it was typed in — this only
// decides how history is read back, so a lab report in pmol/L and one in pg/mL can
// sit on the same curve.

struct HormoneUnitsView: View {
    @EnvironmentObject private var units: HormoneUnitStore
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                Text("Choisis l'unité d'affichage de chaque analyse. « Par défaut » prend l'unité conventionnelle ; « Telle que saisie » n'applique aucune conversion.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                // Vitals (the blood-pressure pair, the NFS values) carry exactly one
                // clinical unit — there is no display preference to offer.
                ForEach(HormoneCatalog.kinds.filter { !Self.vitals.contains($0) }, id: \.self) { hormone in
                    card(hormone)
                }
                Color.clear.frame(height: Spacing.s)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Unités d'affichage")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func card(_ hormone: String) -> some View {
        let asRecorded = units.isAsRecorded(for: hormone)
        let explicit = units.unit(for: hormone)
        let isDefault = !asRecorded && explicit == nil
        let defaultUnit = units.defaultUnit(for: hormone)

        return EggCard(variant: .low, spacing: Spacing.m) {
            Text(HormoneCatalog.kindLabel(hormone))
                .font(EggFont.titleS)
                .foregroundStyle(palette.onSurface)

            ChipFlowLayout(spacing: 7, lineSpacing: 7) {
                PillView(Self.defaultLabel(defaultUnit), selected: isDefault) {
                    units.setUnit(nil, for: hormone)
                }
                PillView("Telle que saisie", selected: asRecorded) {
                    units.setAsRecorded(for: hormone)
                }
                ForEach(Self.options(for: hormone), id: \.self) { unit in
                    PillView(unit, selected: explicit == unit) {
                        units.setUnit(unit, for: hormone)
                    }
                }
            }
        }
    }

    private static func defaultLabel(_ defaultUnit: String?) -> String {
        guard let unit = defaultUnit, !unit.isEmpty else { return "Par défaut" }
        return "Par défaut · \(unit)"
    }

    private static let vitals: Set<String> = [
        "bp_systolic", "bp_diastolic", "hemoglobin", "hematocrit",
    ]

    /// Clinically meaningful units per analyte. « other » exposes the full
    /// catalogue minus the catch-all.
    private static func options(for hormone: String) -> [String] {
        switch hormone {
        case "estradiol":    return ["pg/mL", "pmol/L"]
        case "testosterone": return ["ng/dL", "nmol/L", "ng/mL"]
        case "progesterone": return ["ng/mL", "nmol/L"]
        case "lh":           return ["mIU/mL"]
        case "fsh":          return ["mIU/mL"]
        case "prolactin":    return ["ng/mL"]
        case "shbg":         return ["nmol/L"]
        default:             return HormoneCatalog.units.filter { $0 != "other" }
        }
    }
}
