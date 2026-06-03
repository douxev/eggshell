import SwiftUI

// Top-level phase switch, mirroring Android's AppRoot.
struct AppRootView: View {
    @EnvironmentObject private var app: AppState

    var body: some View {
        switch app.route {
        case .launching:  LaunchView()
        case .onboarding: OnboardingView()
        case .unlock:     UnlockView()
        case .home:       HomeTabView()
        case .decoy:      DecoyNotesView()
        }
    }
}

struct LaunchView: View {
    @Environment(\.palette) private var palette
    var body: some View {
        ZStack {
            palette.surface.ignoresSafeArea()
            VStack(spacing: Spacing.m) {
                Image(systemName: "leaf.fill")
                    .font(.system(size: 48))
                    .foregroundStyle(palette.primary)
                ProgressView().tint(palette.primary)
            }
        }
    }
}
