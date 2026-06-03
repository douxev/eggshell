import SwiftUI
import TransitionCore

@MainActor
final class RemindersViewModel: ObservableObject {
    @Published var loading = true
    @Published var schedules: [DoseSchedule] = []
    @Published var medsById: [Int64: Medication] = [:]
    @Published var error: String?

    func load(_ session: VaultService) async {
        loading = true
        do {
            let meds = try await session.listMedications(includeArchived: true)
            medsById = Dictionary(uniqueKeysWithValues: meds.map { ($0.id, $0) })
            schedules = try await session.listActiveSchedules().sorted { $0.nextDueAtMs < $1.nextDueAtMs }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func medName(_ id: Int64) -> String { medsById[id]?.name ?? "Médicament" }

    func suspend(_ schedule: DoseSchedule, session: VaultService) async {
        do {
            try await session.setScheduleActive(schedule.id, false)
            await load(session)
        } catch { self.error = describe(error) }
    }
}

struct RemindersView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @StateObject private var vm = RemindersViewModel()

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                if vm.loading {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else {
                    medicationRemindersCard
                    comingSoonNote
                }
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Rappels")
        .task { if let s = app.session { await vm.load(s) } }
    }

    private var medicationRemindersCard: some View {
        SectionCard {
            Text("Rappels de médicaments").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            if vm.schedules.isEmpty {
                Text("Aucun planning actif").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
            } else {
                ForEach(vm.schedules, id: \.id) { s in
                    HStack(spacing: Spacing.m) {
                        Image(systemName: "bell.badge").font(.title3).foregroundStyle(palette.primary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(vm.medName(s.medicationId)).font(.eggCallout).foregroundStyle(palette.onSurface)
                            Text(NextDueCalculator.describe(s)).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                        }
                        Spacer()
                        Button("Suspendre") {
                            if let session = app.session { Task { await vm.suspend(s, session: session) } }
                        }
                        .glassButton().tint(palette.primary)
                    }
                }
            }
        }
    }

    private var comingSoonNote: some View {
        SectionCard {
            HStack(spacing: Spacing.m) {
                Image(systemName: "clock.badge").font(.title3).foregroundStyle(palette.tertiary)
                Text("Les rappels labo, photo et voix ainsi que les modes de notification arrivent bientôt.")
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
            }
        }
    }
}
