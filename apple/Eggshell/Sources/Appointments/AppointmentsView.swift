import SwiftUI
import TransitionCore

// ===========================================================================
// TAB ROOT — appointments / notes ("RDV"). Lists every Appointment (soonest
// or most recent first), one card per entry: date+time, professional name +
// role, place, and a to-do/notes excerpt. The FAB pushes a fresh add screen;
// tapping a card edits it. Mirrors android AppointmentsScreen.
// ===========================================================================

@MainActor
final class AppointmentsViewModel: ObservableObject {
    @Published var loading = true
    @Published var entries: [Appointment] = []
    @Published var error: String?

    func load(_ session: VaultService) async {
        loading = true
        do {
            entries = try await session.listAppointments(limit: 500)
        } catch {
            self.error = describe(error)
        }
        loading = false
    }
}

struct AppointmentsView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = AppointmentsViewModel()

    var body: some View {
        TabScaffold(title: "Rendez-vous") {
            Text("Note tes rendez-vous, les professionnel·les et ce qu'il y a à faire.")
                .font(.eggCaption)
                .foregroundStyle(palette.onSurface.opacity(0.6))

            if vm.loading {
                ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
            } else if vm.entries.isEmpty {
                EmptyStateCard(text: "Aucun rendez-vous pour l'instant. Ajoute ton prochain RDV avec le bouton +.", systemImage: "calendar")
            } else {
                ForEach(vm.entries, id: \.id) { entry in
                    Button {
                        router.push(.addAppointment(id: entry.id))
                    } label: {
                        entryCard(entry)
                    }
                    .buttonStyle(.plain)
                }
            }
            if let e = vm.error { ErrorBanner(message: e) }
        }
        .overlay(alignment: .bottomTrailing) {
            Button { router.push(.addAppointment(id: nil)) } label: {
                Image(systemName: "plus").font(.title2.weight(.semibold)).frame(width: 60, height: 60)
            }
            .glassProminentButton().tint(palette.primary)
            .clipShape(Circle())
            .padding(Spacing.xl)
        }
        // onAppear (not just .task) so the list also reloads when returning from
        // the pushed add/edit/delete screen — a NavigationStack root's .task is
        // not guaranteed to re-fire on pop-back, which would leave a stale list.
        .onAppear { if let s = app.session { Task { await vm.load(s) } } }
    }

    private func entryCard(_ entry: Appointment) -> some View {
        SectionCard {
            Text(dateTimeLabel(entry.atMs)).font(.eggHeadline).foregroundStyle(palette.onSurface)
            let pro = [entry.professionalName, entry.professionalRole]
                .compactMap { $0?.isEmpty == false ? $0 : nil }
                .joined(separator: " · ")
            if !pro.isEmpty {
                Text(pro).font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.8))
            }
            if let place = entry.place, !place.isEmpty {
                Text(place).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            }
            if let todo = entry.todo, !todo.isEmpty {
                Text(todo).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6)).lineLimit(3)
            }
        }
    }

    private func dateTimeLabel(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr")
        f.dateStyle = .full
        f.timeStyle = .short
        return f.string(from: date)
    }
}
