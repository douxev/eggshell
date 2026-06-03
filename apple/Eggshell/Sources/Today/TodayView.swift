import SwiftUI
import TransitionCore

// ===========================================================================
// REFERENCE SCREEN — every feature screen follows this shape:
//   • a @MainActor ObservableObject ViewModel with @Published state + a
//     `load(_ session: VaultService)` async method that calls VaultService.
//   • the View owns the VM as @StateObject, reads `app.session`, and calls
//     `await vm.load(session)` inside `.task`.
//   • UI is built from TabScaffold / SectionCard / glassCard + the Palette.
//   • navigation pushes use NavigationLink(value: Route.xxx).
// ===========================================================================

@MainActor
final class TodayViewModel: ObservableObject {
    @Published var loading = true
    @Published var medsById: [Int64: Medication] = [:]
    @Published var schedules: [DoseSchedule] = []
    @Published var error: String?

    func load(_ session: VaultService) async {
        loading = true
        do {
            let meds = try await session.listMedications()
            medsById = Dictionary(uniqueKeysWithValues: meds.map { ($0.id, $0) })
            schedules = try await session.listActiveSchedules().sorted { $0.nextDueAtMs < $1.nextDueAtMs }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    var nextSchedule: DoseSchedule? { schedules.first }
    func medName(_ id: Int64) -> String { medsById[id]?.name ?? "Médicament" }

    func markTaken(_ schedule: DoseSchedule, session: VaultService) async {
        do {
            let med = medsById[schedule.medicationId]
            _ = try await session.logDose(NewDoseEvent(
                medicationId: schedule.medicationId,
                takenAtMs: Time.nowMs(),
                dose: med?.defaultDose,
                doseUnit: med?.defaultDoseUnit,
                route: med?.route,
                injectionSite: nil,
                notes: nil))
            let next = NextDueCalculator.advance(schedule)
            try await session.setScheduleNextDue(schedule.id, next)
            await load(session)
        } catch { self.error = describe(error) }
    }
}

struct TodayView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var features: FeaturesStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = TodayViewModel()
    @State private var showQuickLog = false

    var body: some View {
        TabScaffold(title: "Aujourd'hui") {
            if vm.loading {
                ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
            } else {
                if features.medications { heroCard }
                if features.journal { journalCTA }
                remindersCard
            }
            if let e = vm.error { ErrorBanner(message: e) }
        }
        .overlay(alignment: .bottomTrailing) {
            Button { showQuickLog = true } label: {
                Image(systemName: "plus").font(.title2.weight(.semibold)).frame(width: 60, height: 60)
            }
            .glassProminentButton().tint(palette.primary)
            .clipShape(Circle())
            .padding(Spacing.xl)
        }
        .sheet(isPresented: $showQuickLog) { QuickLogSheet() }
        .task { if let s = app.session { await vm.load(s) } }
    }

    private var heroCard: some View {
        SectionCard {
            Text("Prochaine prise").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            if let s = vm.nextSchedule {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(vm.medName(s.medicationId)).font(.eggHeadline).foregroundStyle(palette.onSurface)
                        Text(NextDueCalculator.describe(s)).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                        Text(dueLabel(s.nextDueAtMs)).font(.eggCaption).foregroundStyle(palette.primary)
                    }
                    Spacer()
                    Button("Pris") {
                        if let session = app.session { Task { await vm.markTaken(s, session: session) } }
                    }
                    .glassButton().tint(palette.primary)
                }
            } else {
                HStack {
                    Text("Aucun planning actif").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
                    Spacer()
                    NavigationLink("Configurer", value: Route.medicationList)
                }
            }
        }
    }

    private var journalCTA: some View {
        NavigationLink(value: Route.addJournal(id: nil)) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Comment te sens-tu ?").font(.eggHeadline).foregroundStyle(palette.onSurface)
                    Text("Note ton humeur & tes effets").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                }
                Spacer()
                Image(systemName: "face.smiling").font(.title2).foregroundStyle(palette.tertiary)
            }
            .padding(Spacing.l)
            .frame(maxWidth: .infinity)
            .background(palette.tertiaryContainer, in: RoundedRectangle(cornerRadius: Corner.large, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private var remindersCard: some View {
        SectionCard {
            Text("Rappels à venir").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            if vm.schedules.isEmpty {
                Text("Aucun rappel").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
            } else {
                ForEach(vm.schedules.prefix(4), id: \.id) { s in
                    HStack {
                        Image(systemName: "pills").foregroundStyle(palette.primary)
                        Text(vm.medName(s.medicationId)).font(.eggCallout).foregroundStyle(palette.onSurface)
                        Spacer()
                        Text(dueLabel(s.nextDueAtMs)).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                    }
                }
            }
        }
    }

    private func dueLabel(_ ms: Int64) -> String {
        let date = NextDueCalculator.date(ms)
        let f = RelativeDateTimeFormatter(); f.locale = Locale(identifier: "fr")
        return f.localizedString(for: date, relativeTo: Date())
    }
}

// Quick-log bottom sheet (Today FAB). Tiles dismiss the sheet and push onto the
// presenting tab's stack via the inherited Router.
struct QuickLogSheet: View {
    @EnvironmentObject private var features: FeaturesStore
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Saisie rapide").font(.eggTitle).foregroundStyle(palette.onSurface)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: Spacing.m) {
                if features.journal { tile("Ressenti", "face.smiling", .addJournal(id: nil)) }
                if features.medications { tile("Dose", "pills.fill", .medicationList) }
                tile("Labo", "chart.xyaxis.line", .addHormone)
                if features.photos { tile("Photo", "camera.fill", .medicationList) }
                if features.voice { tile("Voix", "waveform", .medicationList) }
            }
        }
        .padding(Spacing.xl)
        .presentationDetents([.medium])
    }

    private func tile(_ label: String, _ icon: String, _ route: Route) -> some View {
        Button {
            dismiss()
            router.push(route)
        } label: {
            VStack(spacing: Spacing.s) {
                Image(systemName: icon).font(.title2).foregroundStyle(palette.primary)
                Text(label).font(.eggLabel).foregroundStyle(palette.onSurface)
            }
            .frame(maxWidth: .infinity, minHeight: 84)
            .glassCard(cornerRadius: Corner.medium)
        }
        .buttonStyle(.plain)
    }
}
