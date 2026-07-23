import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — per-hormone preferred display unit. Mirrors
// HormoneUnitsScreen.kt.
//
// Each hormone (HormoneCatalog.kinds) gets a SectionCard with its French label
// (HormoneCatalog.kindLabel) and a row of choice chips:
//   • "Par défaut"      → units.setUnit(nil, for:) ; the chip shows the
//                         conventional default unit as a suffix when there is
//                         one (HormoneCatalog.defaultUnit).
//   • "Telle que saisie" → units.setAsRecorded(for:) (no conversion).
//   • each explicit unit → units.setUnit(u, for:).
// The current choice is highlighted by reading units.unit(for:) /
// units.isAsRecorded(for:).
// ===========================================================================

struct HormoneUnitsView: View {
    @EnvironmentObject private var units: HormoneUnitStore
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.m) {
                Text("Choisis l'unité d'affichage pour chaque hormone. « Par défaut » utilise l'unité conventionnelle ; « Telle que saisie » n'applique aucune conversion.")
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Spacing.xs)

                // Vitals (BP pair, NFS) carry exactly one clinical unit — no
                // display preference to offer, same as Android's screen
                // skipping kinds without a unit-choice entry.
                ForEach(HormoneCatalog.kinds.filter { !vitalsKinds.contains($0) }, id: \.self) { hormone in
                    hormoneCard(hormone)
                }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Unités")
    }

    private func hormoneCard(_ hormone: String) -> some View {
        let asRecorded = units.isAsRecorded(for: hormone)
        let explicit = units.unit(for: hormone)
        let isDefault = !asRecorded && explicit == nil
        let defaultUnit = units.defaultUnit(for: hormone)

        return SectionCard {
            Text(HormoneCatalog.kindLabel(hormone))
                .font(.eggHeadline)
                .foregroundStyle(palette.onSurface)

            HormoneUnitsFlow(spacing: Spacing.xs) {
                ChoiceChip(label: defaultLabel(defaultUnit), selected: isDefault) {
                    units.setUnit(nil, for: hormone)
                }
                ChoiceChip(label: "Telle que saisie", selected: asRecorded) {
                    units.setAsRecorded(for: hormone)
                }
                ForEach(unitOptions(for: hormone), id: \.self) { unit in
                    ChoiceChip(label: unit, selected: explicit == unit) {
                        units.setUnit(unit, for: hormone)
                    }
                }
            }
        }
    }

    private func defaultLabel(_ defaultUnit: String?) -> String {
        if let u = defaultUnit, !u.isEmpty { return "Par défaut · \(u)" }
        return "Par défaut"
    }

    private let vitalsKinds: Set<String> = [
        "bp_systolic", "bp_diastolic", "hemoglobin", "hematocrit",
    ]

    // Clinically meaningful units per hormone, from the shared catalog. "other"
    // exposes the full HormoneCatalog.units list (sans the catch-all "other").
    private func unitOptions(for hormone: String) -> [String] {
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

// Wrapping layout so chip rows flow onto multiple lines on narrow screens.
private struct HormoneUnitsFlow: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var totalHeight: CGFloat = 0
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth + size.width > maxWidth && rowWidth > 0 {
                totalHeight += rowHeight + spacing
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        totalHeight += rowHeight
        return CGSize(width: maxWidth == .infinity ? rowWidth : maxWidth, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        let maxX = bounds.maxX
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxX && x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
