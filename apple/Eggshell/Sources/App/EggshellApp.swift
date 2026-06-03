import SwiftUI

@main
struct EggshellApp: App {
    @StateObject private var app = AppState()
    @StateObject private var features = FeaturesStore()
    @StateObject private var theme = ThemeStore()
    @StateObject private var hormoneUnits = HormoneUnitStore()
    @StateObject private var security = SecurityFlags()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ThemedRoot {
                AppRootView()
            }
            .environmentObject(app)
            .environmentObject(features)
            .environmentObject(theme)
            .environmentObject(hormoneUnits)
            .environmentObject(security)
            .task { await app.bootstrap() }
            .onChange(of: scenePhase) { _, phase in
                // Lock when backgrounded (PARANOID/biometric re-auth on return).
                if phase == .background { app.lock() }
            }
        }
    }
}

/// Resolves the active palette from the system color scheme and injects it.
struct ThemedRoot<Content: View>: View {
    @Environment(\.colorScheme) private var colorScheme
    @ViewBuilder var content: () -> Content

    var body: some View {
        let palette = colorScheme == .dark ? Palette.lavenderDark : Palette.lavenderLight
        content()
            .environment(\.palette, palette)
            .tint(palette.primary)
    }
}
