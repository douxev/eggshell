import SwiftUI

// The four pips and the 3 × 4 pad of §6.13, shared by the lock screen and by
// the step of the first run that sets the access / decoy pair.
//
// Every colour is injected instead of read from `\.palette`. That is not
// ceremony: when a decoy PIN exists the lock screen re-dresses as a plain notes
// app, and a single themed pixel escaping into the pad would give the real app
// away. Callers pass either the themed set or the neutral one.

/// Four 14 pt pips, 15 apart: filled behind each digit typed, a 1.5 pt ring for
/// the ones still to come.
struct PinPips: View {
    let count: Int
    var total: Int = 4
    let filled: Color
    let empty: Color

    var body: some View {
        HStack(spacing: 15) {
            ForEach(0..<total, id: \.self) { i in
                Circle()
                    .fill(i < count ? filled : Color.clear)
                    .frame(width: 14, height: 14)
                    .overlay {
                        if i >= count {
                            Circle().strokeBorder(empty, lineWidth: 1.5)
                        }
                    }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("\(count) chiffre\(count > 1 ? "s" : "") sur \(total)"))
        .accessibilityAddTraits(.updatesFrequently)
    }
}

/// The keypad: three columns, 12 apart, keys 62 high with a 22 radius. The two
/// special keys carry no background so they read as accessories, but they keep
/// the full 62 pt height — the touch target must not shrink (§10).
struct PinKeypad: View {
    let keyContainer: Color
    let digitColor: Color
    /// Ink of the biometric key.
    let accentColor: Color
    /// Ink of the backspace key.
    let mutedColor: Color
    var enabled: Bool = true
    /// SF Symbol of the biometric key, or nil to leave that slot empty.
    var biometricSymbol: String? = nil
    var biometricLabel: String = "Déverrouiller avec la biométrie"
    let onDigit: (String) -> Void
    let onBackspace: () -> Void
    var onBiometric: (() -> Void)? = nil

    private enum Key: Hashable {
        case digit(String)
        case biometric
        case backspace
        case blank
    }

    private static let keyHeight: CGFloat = 62
    private static let keyRadius: CGFloat = 22
    private static let gap: CGFloat = 12

    private var rows: [[Key]] {
        [
            [.digit("1"), .digit("2"), .digit("3")],
            [.digit("4"), .digit("5"), .digit("6")],
            [.digit("7"), .digit("8"), .digit("9")],
            [biometricSymbol != nil ? .biometric : .blank, .digit("0"), .backspace],
        ]
    }

    var body: some View {
        VStack(spacing: Self.gap) {
            ForEach(Array(rows.enumerated()), id: \.offset) { row in
                HStack(spacing: Self.gap) {
                    ForEach(row.element, id: \.self) { key in
                        keyView(key)
                    }
                }
            }
        }
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }

    @ViewBuilder
    private func keyView(_ key: Key) -> some View {
        switch key {
        case .digit(let d):
            Button { onDigit(d) } label: {
                Text(d)
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(digitColor)
                    .frame(maxWidth: .infinity)
                    .frame(height: Self.keyHeight)
                    .background(
                        RoundedRectangle(cornerRadius: Self.keyRadius, style: .continuous)
                            .fill(keyContainer)
                    )
                    .contentShape(RoundedRectangle(cornerRadius: Self.keyRadius, style: .continuous))
            }
            .buttonStyle(.plain)

        case .biometric:
            Button { onBiometric?() } label: {
                Image(systemName: biometricSymbol ?? "faceid")
                    .font(.system(size: 27, weight: .regular))
                    .foregroundStyle(accentColor)
                    .frame(maxWidth: .infinity)
                    .frame(height: Self.keyHeight)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(biometricLabel))

        case .backspace:
            Button(action: onBackspace) {
                Image(systemName: "delete.left")
                    .font(.system(size: 25, weight: .regular))
                    .foregroundStyle(mutedColor)
                    .frame(maxWidth: .infinity)
                    .frame(height: Self.keyHeight)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Effacer le dernier chiffre"))

        case .blank:
            Color.clear
                .frame(maxWidth: .infinity)
                .frame(height: Self.keyHeight)
                .accessibilityHidden(true)
        }
    }
}

/// « 5 s », « 2 min 30 s », « 1 h » — the countdown the lock screen prints while
/// the rate limiter holds the door shut.
func frenchDelay(_ seconds: Int) -> String {
    let s = max(0, seconds)
    if s >= 3600 {
        let h = s / 3600
        let m = (s % 3600) / 60
        return m == 0 ? "\(h) h" : "\(h) h \(m) min"
    }
    if s >= 60 {
        let m = s / 60
        let rest = s % 60
        return rest == 0 ? "\(m) min" : "\(m) min \(rest) s"
    }
    return "\(s) s"
}
