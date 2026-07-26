import SwiftUI

/// The shell of the app — one root, no tab bar.
///
/// The refonte replaced eight tab roots with a single Accueil that carries the
/// launcher: enabling a module adds a tile, never a destination (§1.3, §2.2).
/// Everything else is pushed onto this one stack, and every back button leads
/// home. One `Router`, and `navigationDestination` registered exactly once.
struct AppShell: View {
    @StateObject private var router = Router()

    var body: some View {
        NavigationStack(path: $router.path) {
            HomeView()
                .navigationDestination(for: Route.self) { routeDestination($0) }
        }
        .environmentObject(router)
    }
}
