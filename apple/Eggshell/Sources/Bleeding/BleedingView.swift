import SwiftUI
import TransitionCore

// ===========================================================================
// TAB ROOT — bleeding / cycle log. Lists every BleedingEntry (most recent
// first), one card per entry: French date, a Pill telling "Saignement" vs
// "Spotting" (or "Non précisé" when unspecified), and a free-text excerpt.
// The FAB pushes a fresh add screen; tapping a card edits that entry.
// Slider values live in the "bleeding" metric domain, edited on the add/edit
// screen. Mirrors android BleedingScreen.
// ===========================================================================

@MainActor
final class BleedingViewModel: ObservableObject {
    @Published var loading = true
    @Published var entries: [BleedingEntry] = []
    @Published var error: String?

    func load(_ session: VaultService) async {
        loading = true
        do {
            entries = try await session.listBleedingEntries(limit: 200)
        } catch {
            self.error = describe(error)
        }
        loading = false
    }
}

struct BleedingView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = BleedingViewModel()

    var body: some View {
        TabScaffold(title: "Saignements") {
            Text("Suis tes saignements et ton spotting au fil du cycle.")
                .font(.eggCaption)
                .foregroundStyle(palette.onSurface.opacity(0.6))

            if vm.loading {
                ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
            } else if vm.entries.isEmpty {
                EmptyStateCard(text: "Aucune entrée", systemImage: "drop")
            } else {
                ForEach(vm.entries, id: \.id) { entry in
                    Button {
                        router.push(.addBleeding(id: entry.id))
                    } label: {
                        entryCard(entry)
                    }
                    .buttonStyle(.plain)
                }
            }
            if let e = vm.error { ErrorBanner(message: e) }
        }
        .overlay(alignment: .bottomTrailing) {
            Button { router.push(.addBleeding(id: nil)) } label: {
                Image(systemName: "plus").font(.title2.weight(.semibold)).frame(width: 60, height: 60)
            }
            .glassProminentButton().tint(palette.primary)
            .clipShape(Circle())
            .padding(Spacing.xl)
        }
        .task { if let s = app.session { await vm.load(s) } }
    }

    private func entryCard(_ entry: BleedingEntry) -> some View {
        SectionCard {
            HStack {
                Text(dateLabel(entry.atMs)).font(.eggHeadline).foregroundStyle(palette.onSurface)
                Spacer()
                Pill(text: kindLabel(entry.isSpotting))
            }
            if let freeText = entry.freeText, !freeText.isEmpty {
                Text(freeText)
                    .font(.eggCallout)
                    .foregroundStyle(palette.onSurface.opacity(0.8))
                    .lineLimit(2)
            }
        }
    }

    private func kindLabel(_ isSpotting: Bool?) -> String {
        switch isSpotting {
        case .some(true): return "Spotting"
        case .some(false): return "Saignement"
        case .none: return "Non précisé"
        }
    }

    private func dateLabel(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr")
        f.dateStyle = .medium
        return f.string(from: date)
    }
}
