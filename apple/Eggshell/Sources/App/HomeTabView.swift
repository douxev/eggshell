import SwiftUI

// Bottom tab bar (adopts Liquid Glass automatically on iOS 26). Tabs are gated by
// FeaturesStore, mirroring the Android bottom-nav. Each tab is its own
// NavigationStack and shares the Route destination table. Selection is bound to
// TabRouter so the Today quick-log can jump to the Photos/Voice tabs.
struct HomeTabView: View {
    @EnvironmentObject private var features: FeaturesStore
    @EnvironmentObject private var tabRouter: TabRouter

    var body: some View {
        TabView(selection: $tabRouter.selection) {
            Tab("Accueil", systemImage: "sun.max.fill", value: HomeTab.today) {
                TabStack { TodayView() }
            }
            if features.medications {
                Tab("Médics", systemImage: "pills.fill", value: HomeTab.medications) {
                    TabStack { MedicationListView() }
                }
            }
            if features.journal {
                Tab("Journal", systemImage: "book.fill", value: HomeTab.journal) {
                    TabStack { JournalView() }
                }
            }
            if features.hormones {
                Tab("Courbes", systemImage: "chart.xyaxis.line", value: HomeTab.hormones) {
                    TabStack { HormonesView() }
                }
            }
            if features.bleeding {
                Tab("Menstruations", systemImage: "drop.fill", value: HomeTab.bleeding) {
                    TabStack { BleedingView() }
                }
            }
            if features.appointments {
                Tab("RDV", systemImage: "calendar", value: HomeTab.appointments) {
                    TabStack { AppointmentsView() }
                }
            }
            if features.photos {
                Tab("Photos", systemImage: "photo.fill", value: HomeTab.photos) {
                    TabStack { PhotosView() }
                }
            }
            if features.voice {
                Tab("Voix", systemImage: "waveform", value: HomeTab.voice) {
                    TabStack { VoiceView() }
                }
            }
        }
    }
}

/// A NavigationStack with its own Router (path) + the shared Route destination
/// table. Each tab gets one so screens can push programmatically via @EnvironmentObject Router.
struct TabStack<Content: View>: View {
    @StateObject private var router = Router()
    @ViewBuilder var content: () -> Content
    var body: some View {
        NavigationStack(path: $router.path) {
            content()
                .navigationDestination(for: Route.self) { routeDestination($0) }
        }
        .environmentObject(router)
    }
}
