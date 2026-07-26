import SwiftUI
import TransitionCore

// The configurable indicator sliders of §6.2, shared by « Journal complet » and
// « Noter mes règles ».
//
// Each axis carries its own accent and the current value is written `n/10` in
// that same accent: the number and the colour say the same thing, so nothing is
// ever carried by colour alone (§10). The catalogue is user-editable — hidden,
// reordered, extended — and a hidden indicator never destroys the values that
// were already recorded, which is why the accent is looked up from the metric
// key rather than from the row's position.

enum MetricAccents {
    /// The accent of an axis, from the §6.2 map. Anything the user created is
    /// `--primary`: it is a curve of their own, and the five reserved accents
    /// keep meaning what they mean everywhere else in the app.
    static func color(_ def: MetricDefinition, _ palette: Palette) -> Color {
        switch def.metricKey {
        case "mood":      return palette.primary
        case "dysphoria": return palette.error
        case "euphoria":  return palette.tertiary
        case "libido":    return palette.secondary
        case "energy":    return palette.success
        // Règles. `flow` is the saignement of §5.1; the two pain axes take the
        // secondary and tertiary slots so no two sliders of the domain share a
        // colour.
        case "flow":      return palette.error
        case "pain":      return palette.secondary
        case "cramps":    return palette.tertiary
        default:          return palette.primary
        }
    }
}

/// One slider per enabled indicator, two-way bound on `metricId → value`.
struct MetricSliderColumn: View {
    @Environment(\.palette) private var palette

    let definitions: [MetricDefinition]
    @Binding var values: [Int64: UInt32]

    init(definitions: [MetricDefinition], values: Binding<[Int64: UInt32]>) {
        self.definitions = definitions
        self._values = values
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(definitions, id: \.id) { def in
                row(def)
            }
        }
    }

    private func row(_ def: MetricDefinition) -> some View {
        let accent = MetricAccents.color(def, palette)
        let name = MetricCatalog.displayLabel(def)
        let emojis = MetricCatalog.emojis(def)
        let current = value(def)

        return VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline) {
                Text(name)
                    .font(EggFont.titleS)
                    .foregroundStyle(palette.onSurface)
                Spacer(minLength: Spacing.s)
                Text("\(current)/\(def.maxValue)")
                    .font(EggFont.label)
                    .monospacedDigit()
                    .foregroundStyle(accent)
            }
            HStack(spacing: 10) {
                if let low = emojis.0, !low.isEmpty {
                    Text(low).font(.system(size: 17)).accessibilityHidden(true)
                }
                Slider(
                    value: binding(def),
                    in: Double(def.minValue)...Double(def.maxValue),
                    step: 1)
                    .tint(accent)
                    .accessibilityLabel(name)
                    .accessibilityValue("\(current) sur \(def.maxValue)")
                if let high = emojis.1, !high.isEmpty {
                    Text(high).font(.system(size: 17)).accessibilityHidden(true)
                }
            }
            .padding(.top, 2)
        }
    }

    /// The midpoint is the resting position of an untouched slider — a fresh
    /// entry has to start somewhere, and the middle claims nothing.
    private func value(_ def: MetricDefinition) -> UInt32 {
        values[def.id] ?? (def.minValue + def.maxValue) / 2
    }

    private func binding(_ def: MetricDefinition) -> Binding<Double> {
        Binding(
            get: { Double(value(def)) },
            set: { raw in
                let clamped = min(Double(def.maxValue), max(Double(def.minValue), raw.rounded()))
                values[def.id] = UInt32(clamped)
            })
    }
}
