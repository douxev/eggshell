import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — per-hormone preferred display unit.
//
// Each hormone gets a SectionCard with its French label and a row of choice
// chips: "Par défaut" (clears the override → HormoneUnitStore returns nil and
// callers fall back to the conventional unit) plus the explicit units that
// make clinical sense for that hormone (same table as AddHormoneMeasurementView).
// Selecting a chip calls HormoneUnitStore.setUnit(_:for:); the current choice
// is read back via HormoneUnitStore.unit(for:).
// ===========================================================================

struct HormoneUnitsView: View {
    @EnvironmentObject private var units: HormoneUnitStore
    @Environment(\.palette) private var palette

    // Hormones surfaced here, in display order.
    private let hormones = ["estradiol", "testosterone", "progesterone", "lh", "fsh", "prolactin", "shbg"]

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.m) {
                Text("Choisis l'unité d'affichage pour chaque hormone. « Par défaut » utilise l'unité conventionnelle.")
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Spacing.xs)

                ForEach(hormones, id: \.self) { hormone in
                    hormoneCard(hormone)
                }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Unités")
    }

    private func hormoneCard(_ hormone: String) -> some View {
        let current = units.unit(for: hormone)
        return SectionCard {
            Text(Self.label(for: hormone))
                .font(.eggHeadline)
                .foregroundStyle(palette.onSurface)

            HormoneUnitsFlow(spacing: Spacing.xs) {
                ChoiceChip(label: "Par défaut", selected: current == nil) {
                    units.setUnit(nil, for: hormone)
                }
                ForEach(Self.units(for: hormone), id: \.self) { unit in
                    ChoiceChip(label: unit, selected: current == unit) {
                        units.setUnit(unit, for: hormone)
                    }
                }
            }
        }
    }

    // French display labels for the known hormones.
    private static func label(for hormone: String) -> String {
        switch hormone {
        case "estradiol":    return "Œstradiol"
        case "testosterone": return "Testostérone"
        case "progesterone": return "Progestérone"
        case "lh":           return "LH"
        case "fsh":          return "FSH"
        case "prolactin":    return "Prolactine"
        case "shbg":         return "SHBG"
        default:             return hormone.capitalized
        }
    }

    // Clinically meaningful units per hormone — same table as the entry screen.
    private static func units(for hormone: String) -> [String] {
        switch hormone {
        case "estradiol":    return ["pg/mL", "pmol/L"]
        case "testosterone": return ["ng/dL", "nmol/L", "ng/mL"]
        case "progesterone": return ["ng/mL", "nmol/L"]
        case "lh":           return ["mIU/mL"]
        case "fsh":          return ["mIU/mL"]
        case "prolactin":    return ["ng/mL"]
        case "shbg":         return ["nmol/L"]
        default:             return ["pg/mL", "pmol/L", "ng/dL", "nmol/L", "ng/mL", "mIU/mL"]
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
