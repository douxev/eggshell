import SwiftUI

/// Every reminder of one treatment, and everything that can be done to one.
///
/// Mirrors the Android screen of the same name. The gap it closes is not quite
/// the one Android had — this app already listed paused reminders on the
/// treatment page — but the same shape of problem: **delete lived only in a
/// `contextMenu`**, a long-press with nothing on screen to suggest it exists.
/// An action nobody can find is not meaningfully different from one that is
/// missing, and the reminder most likely to want deleting is a paused one the
/// user has already stopped thinking about.
///
/// So the operations are collected here, each with a control you can see:
/// create, pause and resume, edit, delete. The treatment page keeps one row
/// pointing at this screen instead of a stack of half-manageable cards.
@MainActor
final class MedicationRemindersViewModel: ObservableObject {
    @Published var loading = true
    @Published var med: Medication?
    @Published var schedules: [DoseSchedule] = []
    @Published var error: String?

    let medId: Int64

    init(medId: Int64) { self.medId = medId }

    func load(_ session: VaultService) async {
        loading = true
        error = nil
        do {
            med = try await session.getMedication(medId)
            schedules = try await session
                .listSchedulesForMedication(medId, includeInactive: true)
                // Live reminders first, paused ones after: the paused block is
                // where cleanup happens and should not be interleaved with the
                // reminders that are actually firing.
                .sorted {
                    $0.active == $1.active ? $0.nextDueAtMs < $1.nextDueAtMs : $0.active
                }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func setActive(_ schedule: DoseSchedule, _ active: Bool, session: VaultService, app: AppState) async {
        do {
            try await session.setScheduleActive(schedule.id, active)
            await app.refreshNotifications()
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    func delete(_ id: Int64, session: VaultService, app: AppState) async {
        do {
            try await session.deleteSchedule(id)
            await app.refreshNotifications()
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }
}

struct MedicationRemindersView: View {
    let medId: Int64

    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm: MedicationRemindersViewModel

    @State private var toDelete: DoseSchedule?
    /// `.task` does not re-fire when the editor pushed on top of this screen
    /// pops, so the stack depth says when to read again — otherwise a reminder
    /// just created or retimed would not be here on return.
    @State private var depth: Int?

    init(medId: Int64) {
        self.medId = medId
        _vm = StateObject(wrappedValue: MedicationRemindersViewModel(medId: medId))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                if let error = vm.error {
                    ErrorCardView(error, retryLabel: "Réessayer") { reload() }
                }

                EggCard(variant: .low) {
                    Text("Crée, modifie, mets en pause ou supprime les rappels de ce traitement. Une pause n’efface rien : le rappel reste ici, prêt à être réactivé ou supprimé.")
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurfaceVariant)
                }

                if vm.schedules.isEmpty {
                    if !vm.loading {
                        EmptyStateView(
                            "Aucun rappel programmé pour ce traitement.",
                            systemImage: "bell.slash",
                            actionLabel: "Ajouter un rappel") {
                                router.push(.addSchedule(medId: medId))
                            }
                    }
                } else {
                    ForEach(vm.schedules, id: \.id) { reminderCard($0) }
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle(vm.med.map { "Rappels · \($0.name)" } ?? "Rappels")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    router.push(.addSchedule(medId: medId))
                } label: {
                    Label("Ajouter un rappel", systemImage: "plus")
                }
            }
        }
        .task { reload() }
        .onChange(of: router.path.count) { _, count in
            if let depth, count == depth { reload() }
        }
        .onAppear { if depth == nil { depth = router.path.count } }
        .alert("Supprimer ce rappel ?", isPresented: Binding(
            get: { toDelete != nil },
            set: { if !$0 { toDelete = nil } }
        )) {
            Button("Annuler", role: .cancel) { toDelete = nil }
            Button("Supprimer", role: .destructive) {
                if let schedule = toDelete, let session = app.session {
                    Task { await vm.delete(schedule.id, session: session, app: app) }
                }
                toDelete = nil
            }
        } message: {
            Text("Le rappel sera supprimé définitivement. Ton historique de prises est conservé.")
        }
    }

    /// One reminder, with every operation on it in reach.
    ///
    /// Delete is a button on the card rather than a long-press, and it is there
    /// whether or not the reminder is running — a paused reminder is the single
    /// most likely thing on this screen to want deleting, and hiding the action
    /// behind an undiscoverable gesture is how it went missing before.
    private func reminderCard(_ schedule: DoseSchedule) -> some View {
        EggCard(variant: .low, paddingH: 18, paddingV: Spacing.l, spacing: 0) {
            HStack(spacing: Metrics.blockGap) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(MedFormat.cadence(schedule))
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                        .multilineTextAlignment(.leading)
                    // A paused reminder's next-due is a leftover, not a
                    // promise: stating a date next to a switch that is off
                    // reads as "it will still fire then".
                    Text(schedule.active
                        ? "Prochain : " + MedFormat.dayAndTime(schedule.nextDueAtMs)
                        : "Rappel en pause — il ne sonnera pas.")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if !schedule.active {
                    StatusPillView(
                        "En pause",
                        container: palette.surfaceContainerHighest,
                        content: palette.onSurfaceVariant)
                }
            }

            if let label = schedule.label, !label.isEmpty {
                Text("« \(label) »")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .padding(.top, Spacing.s)
            }

            CardRule().padding(.top, Spacing.m)

            Toggle(isOn: Binding(
                get: { schedule.active },
                set: { on in
                    guard let session = app.session else { return }
                    Task { await vm.setActive(schedule, on, session: session, app: app) }
                }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Activer le rappel").font(.eggBody)
                    Text("Coupe le rappel sans effacer son horaire ni ton historique.")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
            }
            .tint(palette.primary)
            .padding(.top, Spacing.m)

            CardRule().padding(.top, Spacing.m)

            HStack {
                Button {
                    router.push(.editSchedule(medId: medId, scheduleId: schedule.id))
                } label: {
                    Label("Modifier", systemImage: "pencil")
                        .font(.eggBody)
                }
                .buttonStyle(.plain)
                .foregroundStyle(palette.primary)

                Spacer(minLength: Spacing.s)

                Button(role: .destructive) {
                    toDelete = schedule
                } label: {
                    Label("Supprimer", systemImage: "trash")
                        .font(.eggBody)
                        .labelStyle(.iconOnly)
                }
                .buttonStyle(.plain)
                .foregroundStyle(palette.error)
                .accessibilityLabel("Supprimer ce rappel")
            }
            .padding(.top, Spacing.m)
        }
    }

    private func reload() {
        guard let session = app.session else { return }
        Task { await vm.load(session) }
    }
}
