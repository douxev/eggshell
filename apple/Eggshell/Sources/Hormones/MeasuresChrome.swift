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

    /// « 128 », « 0,42 ». A recorded reading is written at full precision and
    /// only loses a trailing « ,0 »: rounding it here would put a digit the
    /// sheet never said in front of a doctor.
    static func value(_ v: Double) -> String {
        // Kill the binary tail a unit conversion leaves behind — 128 pg/mL
        // becomes 469,88800000000003 pmol/L — without touching a digit a sheet
        // could plausibly carry: six decimals is far past clinical precision.
        let cleaned = abs(v) < 1e9 ? (v * 1_000_000).rounded() / 1_000_000 : v
        var s = String(cleaned)
        if s.hasSuffix(".0") { s = String(s.dropLast(2)) }
        return s.replacingOccurrences(of: ".", with: ",")
    }

    /// A difference between two readings, rounded to two decimals. Unlike a
    /// reading, a delta is something we computed — and binary subtraction
    /// otherwise prints « 0,10000000000000003 » in the pill.
    static func delta(_ v: Double) -> String {
        value((v * 100).rounded() / 100)
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
