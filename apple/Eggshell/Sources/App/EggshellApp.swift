import SwiftUI

@main
struct EggshellApp: App {
    @StateObject private var app = AppState()
    @StateObject private var features = FeaturesStore()
    @StateObject private var theme = ThemeStore()
    @StateObject private var hormoneUnits = HormoneUnitStore()
    @StateObject private var security = SecurityFlags()
    @StateObject private var whatsNew = WhatsNewStore()
    @StateObject private var labReminders = LabReminderStore()
    @StateObject private var tabRouter = TabRouter()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ThemedRoot(themeId: theme.themeId) {
                AppRootView()
                    .privacyShield(enabled: security.blockScreenshots)
            }
            .environmentObject(app)
            .environmentObject(features)
            .environmentObject(theme)
            .environmentObject(hormoneUnits)
            .environmentObject(security)
            .environmentObject(whatsNew)
            .environmentObject(labReminders)
            .environmentObject(tabRouter)
            .task {
                NotificationCoordinator.shared.configure()
                await app.bootstrap()
            }
            .onChange(of: scenePhase) { _, phase in
                // Lock when backgrounded (PARANOID/biometric re-auth on return).
                if phase == .background { app.lock() }
            }
        }
    }
}

/// Resolves the active palette from the selected theme + system color scheme and
/// injects it. Single-variant themes (e.g. Dracula) apply regardless of system.
struct ThemedRoot<Content: View>: View {
    let themeId: String
    @Environment(\.colorScheme) private var colorScheme
    @ViewBuilder var content: () -> Content

    var body: some View {
        let palette = Palette.resolve(themeId: themeId, dark: colorScheme == .dark)
        content()
            .environment(\.palette, palette)
            .tint(palette.primary)
    }
}
