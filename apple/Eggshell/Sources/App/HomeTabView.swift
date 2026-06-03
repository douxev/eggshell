import SwiftUI

// Bottom tab bar (adopts Liquid Glass automatically on iOS 26). Tabs are gated by
// FeaturesStore, mirroring the Android bottom-nav. Each tab is its own
// NavigationStack and shares the Route destination table.
struct HomeTabView: View {
    @EnvironmentObject private var features: FeaturesStore

    var body: some View {
        TabView {
            Tab("Aujourd'hui", systemImage: "sun.max.fill") {
                TabStack { TodayView() }
            }
            if features.medications {
                Tab("Médicaments", systemImage: "pills.fill") {
                    TabStack { MedicationListView() }
                }
            }
            if features.journal {
                Tab("Journal", systemImage: "book.fill") {
                    TabStack { JournalView() }
                }
            }
            if features.hormones {
                Tab("Hormones", systemImage: "chart.xyaxis.line") {
                    TabStack { HormonesView() }
                }
            }
            if features.photos {
                Tab("Photos", systemImage: "photo.fill") {
                    TabStack { PhotosView() }
                }
            }
            if features.voice {
                Tab("Voix", systemImage: "waveform") {
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
