import SwiftUI

// Liquid Glass with a graceful fallback.
//
// The glass APIs (`glassEffect`, `buttonStyle(.glass)`) are iOS-26-SDK symbols:
// they don't even exist when compiling with an older Xcode, so a plain runtime
// `if #available` is not enough — the *compiler* must also have the SDK. We gate
// on `#if compiler(>=6.2)` (Swift 6.2 ships with Xcode 26) and fall back to
// `.ultraThinMaterial` everywhere else (iOS < 26, or older Xcode).
//
// CI pins Xcode 26 so real glass ships; this just keeps the project buildable
// on any toolchain.
extension View {
    /// A floating "functional layer" surface — use for cards, FABs, bars.
    @ViewBuilder
    func glassCard(cornerRadius: CGFloat = 20, interactive: Bool = false) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        } else {
            self.legacyGlass(shape)
        }
        #else
        self.legacyGlass(shape)
        #endif
    }

    @ViewBuilder
    private func legacyGlass(_ shape: some Shape) -> some View {
        self.background(shape.fill(.ultraThinMaterial))
            .overlay(shape.stroke(.white.opacity(0.14), lineWidth: 1))
    }
}

extension View {
    /// Primary action button styled as prominent glass on iOS 26, bordered
    /// prominent otherwise.
    @ViewBuilder
    func glassProminentButton() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glassProminent)
        } else {
            self.buttonStyle(.borderedProminent)
        }
        #else
        self.buttonStyle(.borderedProminent)
        #endif
    }

    /// Secondary / translucent action button.
    @ViewBuilder
    func glassButton() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glass)
        } else {
            self.buttonStyle(.bordered)
        }
        #else
        self.buttonStyle(.bordered)
        #endif
    }
}
