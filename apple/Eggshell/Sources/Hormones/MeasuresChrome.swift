import SwiftUI

// Pieces shared by the three screens of the Mesures zone (§6.8, §6.9) and by
// the manual-entry form: the pushed-screen chrome, the analyte filter chip and
// the number/date formatting the curve, the readings and the OCR preview all
// have to agree on.
//
// Nothing here reaches for a literal colour: the app ships 14 palettes.

/// The pushed-screen chrome of §4 on iOS: inline title and a « ‹ Retour »
/// leading item.
///
/// The written word is deliberate. Accueil hides its navigation bar, so the
/// system back item has no previous title to inherit and would render as a
/// bare chevron — a 44 pt target with nothing to read on it.
struct MeasuresScreenChrome: ViewModifier {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette

    let title: String

    func body(content: Content) -> some View {
        content
            .background(palette.surface.ignoresSafeArea())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(true)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: {
                        HStack(spacing: 3) {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 15, weight: .semibold))
                            Text("Retour")
                                .font(.system(size: 16))
                        }
                        .foregroundStyle(palette.primary)
                        .contentShape(Rectangle())
                    }
                    .accessibilityLabel("Retour")
                }
            }
    }
}

extension View {
    /// Applies the pushed-screen chrome. A screen that also needs a bar action
    /// adds its own `.toolbar { … }` — toolbars compose.
    func measuresScreen(_ title: String) -> some View {
        modifier(MeasuresScreenChrome(title: title))
    }
}

/// Analyte / unit selector chip. This is the **filter** species of chip
/// (radius 10, a leading check when selected), not the period pill of radius
/// 100 — the two are distinct on purpose, so a filter never reads as a period.
struct AnalyteChip: View {
    @Environment(\.palette) private var palette

    let label: String
    var selected: Bool
    let action: () -> Void

    init(_ label: String, selected: Bool, action: @escaping () -> Void) {
        self.label = label
        self.selected = selected
        self.action = action
    }

    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 12, weight: .bold))
                }
                Text(label)
                    .font(.system(size: 14, weight: .semibold))
                    .tracking(0.1)
                    .lineLimit(1)
            }
            .foregroundStyle(selected ? palette.onSecondaryContainer : palette.onSurfaceVariant)
            .padding(.leading, selected ? 12 : 16)
            .padding(.trailing, 16)
            .frame(height: 36)
            .background(
                shape.fill(selected ? palette.secondaryContainer : palette.surfaceContainerLow))
            .overlay { if !selected { shape.stroke(palette.outlineVariant, lineWidth: 1) } }
            // The pill stays 36 tall; the target around it is a full 44 (§10).
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// Number and date shapes used across Mesures. One place, so the curve header,
/// the readings list and the OCR preview can never disagree about how a value
/// is written.
enum MeasureFormat {
    private static let locale = Locale(identifier: "fr")

    /// Figures a displayed measurement is quoted to.
    static let significantDigits = 5

    /// « 128,00 », « 0,42000 » — a reading at five significant figures.
    ///
    /// The previous rule was "full precision, minus a trailing ,0", with a
    /// six-decimal round to kill the binary tail a unit conversion leaves
    /// behind. It did not go far enough: the tail is not the only problem.
    /// 128 pg/mL converted to pmol/L is 469,888 — and six decimals of that is
    /// still five digits past anything the analyser measured, presented to a
    /// doctor as though it had.
    ///
    /// **Significant figures, not decimal places.** Analytes span six orders of
    /// magnitude between a TSH in mIU/L and a platelet count, so a fixed
    /// decimal count is wrong at one end or the other: two decimals flatten
    /// 0,001234 to 0,00.
    ///
    /// Trailing zeros are kept. 0,30000 and 0,3 are the same number but not the
    /// same claim, and a column of readings quoted to one width is the one a
    /// reader can scan for a change.
    static func value(_ v: Double, digits: Int = significantDigits) -> String {
        guard v.isFinite else { return "—" }
        if v == 0 { return comma(String(format: "%.\(digits - 1)f", 0.0)) }

        // More integer digits than we quote: show it whole. Rounding here would
        // *destroy* measured digits rather than hide unmeasured ones — a
        // platelet count of 123456 reported as 123460.
        if Int(floor(log10(abs(v)))) >= digits {
            return comma(String(format: "%.0f", v))
        }

        // Round to the significant run first, then measure where it landed.
        // Measuring the raw value instead puts 0,0999999 one decade too low and
        // renders it "0,100000" — six figures, because the rounding that
        // carried it over 0,1 happened after the width was already decided.
        let firstPass = String(format: "%.\(scale(of: v, digits: digits))f", v)
        let rounded = Double(firstPass) ?? v
        if rounded == 0 { return comma(String(format: "%.\(digits - 1)f", 0.0)) }
        return comma(String(format: "%.\(scale(of: rounded, digits: digits))f", rounded))
    }

    /// Decimal places needed for `digits` significant figures of `v`.
    private static func scale(of v: Double, digits: Int) -> Int {
        max(digits - 1 - Int(floor(log10(abs(v)))), 0)
    }

    /// The value as recorded, with a bare « ,0 » dropped.
    ///
    /// This is what seeds an editable field, and it deliberately does NOT
    /// round: saving the sheet writes back whatever the field holds, so
    /// quoting a rounded value there would let a save silently overwrite the
    /// stored reading with the display's approximation of it.
    static func plain(_ v: Double) -> String {
        guard v.isFinite else { return "—" }
        var s = String(v)
        if s.hasSuffix(".0") { s = String(s.dropLast(2)) }
        return comma(s)
    }

    /// A difference between two readings.
    ///
    /// An unchanged reading is « 0 », not « 0,0000 »: padding a difference of
    /// exactly nothing out to five figures quotes a precision that has no
    /// measurement behind it at all.
    static func delta(_ v: Double) -> String {
        v == 0 ? "0" : value(v)
    }

    private static func comma(_ s: String) -> String {
        s.replacingOccurrences(of: ".", with: ",")
    }

    /// « 18 juillet 2026 » — the readings list and the sample-date card.
    static func fullDate(_ ms: Int64) -> String { formatted(ms, "d MMMM yyyy") }

    /// « 18 juillet » — the curve header, uppercased by its caller.
    static func dayMonth(_ ms: Int64) -> String { formatted(ms, "d MMMM") }

    /// « juil. 26 » — the two ends of the X axis.
    static func monthYear(_ ms: Int64) -> String { formatted(ms, "MMM yy") }

    static func upper(_ s: String) -> String { s.uppercased(with: locale) }

    private static func formatted(_ ms: Int64, _ pattern: String) -> String {
        let f = DateFormatter()
        f.locale = locale
        f.dateFormat = pattern
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}
