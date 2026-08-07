import SwiftUI

/// The shell of the app — one root, no tab bar.
///
/// The refonte replaced eight tab roots with a single Accueil that carries the
/// launcher: enabling a module adds a tile, never a destination (§1.3, §2.2).
/// Everything else is pushed onto this one stack, and every back button leads
/// home. One `Router`, and `navigationDestination` registered exactly once.
struct AppShell: View {
    @StateObject private var router = Router()
    @EnvironmentObject private var features: FeaturesStore
    @EnvironmentObject private var app: AppState

    var body: some View {
        NavigationStack(path: $router.path) {
            HomeView()
                .navigationDestination(for: Route.self) { routeDestination($0) }
        }
        .environmentObject(router)
        // A Home-Screen quick action is delivered while the app may still be
        // locked. The shell only exists once the vault is open — AppRootView
        // shows the unlock screen instead — so by the time this runs the link
        // has already been through the lock, exactly as tapping the icon would
        // have been. There is no path from a shortcut to vault contents.
        .onReceive(NotificationCenter.default.publisher(for: .openModuleShortcut)) { note in
            guard let module = note.object as? AppModule else { return }
            router.popToRoot()
            router.push(module.route)
        }
        // Republish on every appearance: the set depends on which modules are
        // enabled and on whether a decoy or a disguised icon is active, and all
        // three can change while the app is running.
        .onAppear {
            ModuleShortcuts.refresh(features: features)
            // A cold launch parks its module before any view exists; drain it
            // now that there is a stack to push onto.
            if let pending = ShortcutSceneDelegate.pending {
                ShortcutSceneDelegate.pending = nil
                router.popToRoot()
                router.push(pending.route)
            }
        }
        .onChange(of: features.enabledSignature) { _, _ in
            ModuleShortcuts.refresh(features: features)
        }
    }
}

extension AppModule {
    /// Where this module's shortcut lands. Kept next to the shell rather than
    /// in the catalogue so the catalogue — which the publisher reads — does not
    /// have to know what a `Route` is.
    var route: Route {
        switch self {
        case .meds: return .medicationList
        case .journal: return .journal
        case .labs: return .labs
        case .weight: return .weight
        case .bleeding: return .bleeding
        case .appointments: return .appointments
        case .photos: return .photos
        case .voice: return .voice
        case .notes: return .notes
        case .dreams: return .dreams
        }
    }
}

extension Notification.Name {
    /// Raised by the scene delegate hook when a quick action launches the app.
    static let openModuleShortcut = Notification.Name("eggshell.openModuleShortcut")
}
