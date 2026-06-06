import SwiftUI
import UIKit

// iOS has no exact FLAG_SECURE equivalent (you can't block the screenshot
// gesture), but the threats that flag addresses — the app-switcher snapshot and
// screen recording / mirroring — we CAN cover:
//   • when the app is not active (backgrounded / in the switcher) we draw an
//     opaque cover so the snapshot leaks nothing;
//   • when the screen is being captured (recording, AirPlay, mirroring) we draw
//     the same cover so the content never lands in the capture.
// Gated by SecurityFlags.blockScreenshots so the user toggle is real, not
// decorative. Mirrors the intent of android MainActivity FLAG_SECURE.

@MainActor
final class ScreenCaptureMonitor: ObservableObject {
    @Published var isCaptured: Bool = UIScreen.main.isCaptured
    private var observer: NSObjectProtocol?

    init() {
        observer = NotificationCenter.default.addObserver(
            forName: UIScreen.capturedDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.isCaptured = UIScreen.main.isCaptured }
        }
    }
    deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }
}

private struct PrivacyShieldModifier: ViewModifier {
    let enabled: Bool
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.palette) private var palette
    @StateObject private var capture = ScreenCaptureMonitor()

    private var shouldCover: Bool {
        guard enabled else { return false }
        return scenePhase != .active || capture.isCaptured
    }

    func body(content: Content) -> some View {
        content.overlay {
            if shouldCover {
                ZStack {
                    palette.surface.ignoresSafeArea()
                    VStack(spacing: Spacing.m) {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 40))
                            .foregroundStyle(palette.primary)
                        if capture.isCaptured && scenePhase == .active {
                            Text("Contenu masqué pendant l'enregistrement d'écran.")
                                .font(.eggCallout)
                                .foregroundStyle(palette.onSurface.opacity(0.7))
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, Spacing.xl)
                        }
                    }
                }
                .transition(.opacity)
            }
        }
    }
}

extension View {
    /// Cover sensitive content in the app switcher and during screen capture.
    func privacyShield(enabled: Bool) -> some View { modifier(PrivacyShieldModifier(enabled: enabled)) }
}
