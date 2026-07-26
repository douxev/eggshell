import SwiftUI

// Top-level phase switch, mirroring Android's AppRoot.
struct AppRootView: View {
    @EnvironmentObject private var app: AppState

    var body: some View {
        switch app.route {
        case .launching:  LaunchView()
        case .onboarding: OnboardingView()
        case .unlock:     UnlockView()
        case .home:       AppShell()
        case .decoy:      DecoyNotesView()
        }
    }
}

/// The sub-second placeholder shown while the vault decides whether we are
/// onboarding, locked or open.
///
/// It carries **no mark and no accent** when a decoy PIN is set. §6.13 requires
/// that path to be a notes app with « aucun flash lavande », and this view
/// renders *before* the lock screen — so a lavender frame here defeats the
/// re-skin one screen earlier, in front of whoever asked to see the phone.
/// The glyph is gone in both modes: it was a leaf, which is neither the
/// eggshell mark nor anything a notes app would show.
struct LaunchView: View {
    @Environment(\.palette) private var palette
    /// A synchronous UserDefaults read, so the very first frame is already right.
    private let decoyConfigured = DecoyVerifier().isConfigured
    /// Same neutral teal the decoy notes app and the re-skinned lock screen use.
    private static let neutralSurface = Color(hex: 0xFAFDFC)
    private static let neutralAccent = Color(hex: 0x006A6A)

    var body: some View {
        ZStack {
            (decoyConfigured ? Self.neutralSurface : palette.surface).ignoresSafeArea()
            ProgressView()
                .tint(decoyConfigured ? Self.neutralAccent : palette.primary)
        }
    }
}
