import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen: medication detail. Shows header (kind/route via MedCatalog),
// schedules (pause/resume + delete), dose history (with injection site),
// and the treatment-change timeline. Buttons: Modifier, Noter une prise,
// Ajouter planning, Archiver. Parity with Android MedicationDetailScreen.
// All UI strings in French.
// ===========================================================================

@MainActor
final class MedicationDetailViewModel: ObservableObject {
    @Published var loading = true
    @Published var med: Medication?
    @Published var schedules: [DoseSchedule] = []
    @Published var doses: [DoseEvent] = []
    @Published var changes: [TreatmentChange] = []
    @Published var error: String?

    let medId: Int64

    init(medId: Int64) {
        self.medId = medId
    }

    func load(_ session: VaultService) async {
        loading = true
        do {
            med = try await session.getMedication(medId)
            schedules = try await session.listSchedulesForMedication(medId, includeInactive: true)
            doses = try await session.listDoses(medicationId: medId, limit: 50)
                .sorted { $0.takenAtMs > $1.takenAtMs }
            // Treatment changes over a wide window (~10 years back → now).
            let now = Time.nowMs()
            let tenYears: Int64 = 10 * 365 * 24 * 60 * 60 * 1000
            changes = try await session.listTreatmentChanges(fromMs: now - tenYears, toMs: now)
                .filter { $0.medicationId == medId }
                .sorted { $0.atMs > $1.atMs }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func toggleActive(_ schedule: DoseSchedule, session: VaultService, app: AppState) async {
        do {
            try await session.setScheduleActive(schedule.id, !schedule.active)
            await app.refreshNotifications()
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    func deleteSchedule(_ schedule: DoseSchedule, session: VaultService, app: AppState) async {
        do {
            try await session.deleteSchedule(schedule.id)
            await app.refreshNotifications()
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    func archive(_ session: VaultService, app: AppState) async -> Bool {
        do {
            try await session.setMedicationArchived(medId, true)
            await app.refreshNotifications()
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    func deleteDose(_ d: DoseEvent, session: VaultService) async {
        do {
            try await session.deleteDose(d.id)
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    /// Hard-delete the medication: the core cascades its doses/schedules/changes,
    /// then we rebuild the pending reminders from what's left (the deleted med's
    /// schedules are gone, so its notifications drop out). Only returns true when
    /// the delete actually succeeded, so the caller doesn't navigate away on a
    /// half-done delete.
    func deleteMedication(_ session: VaultService, app: AppState) async -> Bool {
        do {
            try await session.deleteMedication(medId)
            await app.refreshNotifications()
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }
}

struct MedicationDetailView: View {
    let medId: Int64

    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm: MedicationDetailViewModel
    @State private var confirmDelete = false
    @State private var doseToDelete: DoseEvent?

    init(medId: Int64) {
        self.medId = medId
        _vm = StateObject(wrappedValue: MedicationDetailViewModel(medId: medId))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.m) {
                if vm.loading {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else {
                    headerCard
                    actionsCard
                    schedulesCard
                    historyCard
                    if !vm.changes.isEmpty { changesCard }
                }
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.m)
        }
        .navigationTitle(vm.med?.name ?? "Traitement")
        .alert("Supprimer ce traitement ?", isPresented: $confirmDelete) {
            Button("Annuler", role: .cancel) {}
            Button("Supprimer définitivement", role: .destructive) { deleteMedication() }
        } message: {
            Text("Le traitement, tout son historique de prises et ses rappels seront définitivement supprimés. Pour le masquer sans perdre l'historique, choisis plutôt « Archiver ».")
        }
        .alert("Supprimer cette prise ?", isPresented: Binding(
            get: { doseToDelete != nil },
            set: { if !$0 { doseToDelete = nil } }
        )) {
            Button("Annuler", role: .cancel) { doseToDelete = nil }
            Button("Supprimer", role: .destructive) {
                if let d = doseToDelete, let s = app.session {
                    Task { await vm.deleteDose(d, session: s) }
                }
                doseToDelete = nil
            }
        } message: {
            Text("Cette prise sera retirée de l'historique. Cette action est définitive.")
        }
        .overlay(alignment: .bottomTrailing) {
            Button {
                router.push(.logDose(medId: medId))
            } label: {
                Image(systemName: "plus").font(.title2.weight(.semibold)).frame(width: 60, height: 60)
            }
            .glassProminentButton().tint(palette.primary)
            .clipShape(Circle())
            .padding(Spacing.xl)
        }
        .task { if let s = app.session { await vm.load(s) } }
    }

    // MARK: - Header

    private var headerCard: some View {
        SectionCard {
            Text("Informations").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            if let m = vm.med {
                HStack(spacing: Spacing.s) {
                    Pill(text: MedCatalog.kindLabel(m.kind))
                    Pill(text: MedCatalog.routeLabel(m.route))
                }
                if let dose = m.defaultDose {
                    Text("Dose par défaut : \(doseLabel(dose, m.defaultDoseUnit))")
                        .font(.eggCallout).foregroundStyle(palette.onSurface)
                }
                if let notes = m.notes, !notes.isEmpty {
                    Text(notes).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
                }
            } else {
                Text("Traitement introuvable").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
            }
        }
    }

    // MARK: - Actions

    private var actionsCard: some View {
        SectionCard {
            Text("Actions").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            NavigationLink(value: Route.editMedication(id: medId)) {
                Label("Modifier", systemImage: "pencil").frame(maxWidth: .infinity)
            }
            .glassButton().tint(palette.primary)

            Button {
                router.push(.logDose(medId: medId))
            } label: {
                Label("Noter une prise", systemImage: "checkmark.circle").frame(maxWidth: .infinity)
            }
            .glassButton().tint(palette.primary)

            Button {
                router.push(.addSchedule(medId: medId))
            } label: {
                Label("Ajouter un planning", systemImage: "clock").frame(maxWidth: .infinity)
            }
            .glassButton().tint(palette.primary)

            Button(role: .destructive) {
                archive()
            } label: {
                Label("Archiver", systemImage: "archivebox").frame(maxWidth: .infinity)
            }
            .glassButton().tint(palette.error)

            Button(role: .destructive) {
                confirmDelete = true
            } label: {
                Label("Supprimer définitivement", systemImage: "trash").frame(maxWidth: .infinity)
            }
            .glassButton().tint(palette.error)
        }
    }

    // MARK: - Schedules

    private var schedulesCard: some View {
        SectionCard {
            Text("Plannings").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            if vm.schedules.isEmpty {
                Text("Aucun planning").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
            } else {
                ForEach(vm.schedules, id: \.id) { s in
                    scheduleRow(s)
                    if s.id != vm.schedules.last?.id {
                        Divider().overlay(palette.outlineVariant)
                    }
                }
            }
        }
    }

    private func scheduleRow(_ s: DoseSchedule) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(NextDueCalculator.describe(s)).font(.eggCallout).foregroundStyle(palette.onSurface)
            Text("Prochaine : \(formatDateTime(s.nextDueAtMs))")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            if !s.active {
                Text("En pause").font(.eggCaption).foregroundStyle(palette.error)
            }
            HStack(spacing: Spacing.s) {
                Button(s.active ? "Pause" : "Reprendre") {
                    if let session = app.session {
                        Task { await vm.toggleActive(s, session: session, app: app) }
                    }
                }
                .glassButton().tint(palette.primary)

                Button("Supprimer", role: .destructive) {
                    if let session = app.session {
                        Task { await vm.deleteSchedule(s, session: session, app: app) }
                    }
                }
                .glassButton().tint(palette.error)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - History

    private var historyCard: some View {
        SectionCard {
            Text("Historique des prises").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            if vm.doses.isEmpty {
                Text("Aucune prise enregistrée").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
            } else {
                ForEach(vm.doses, id: \.id) { d in
                    doseRow(d)
                    if d.id != vm.doses.last?.id {
                        Divider().overlay(palette.outlineVariant)
                    }
                }
            }
        }
    }

    private func doseRow(_ d: DoseEvent) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(formatDateTime(d.takenAtMs)).font(.eggCallout).foregroundStyle(palette.onSurface)
                Spacer()
                if let dose = d.dose {
                    Text(doseLabel(dose, d.doseUnit)).font(.eggCallout).foregroundStyle(palette.primary)
                }
                Button {
                    doseToDelete = d
                } label: {
                    Image(systemName: "trash").font(.eggCaption)
                }
                .buttonStyle(.borderless)
                .tint(palette.error)
                .accessibilityLabel("Supprimer cette prise")
            }
            if let detail = doseDetail(d) {
                Text(detail).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
            }
            if let notes = d.notes, !notes.isEmpty {
                Text(notes).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            }
        }
    }

    // MARK: - Treatment changes timeline

    private var changesCard: some View {
        SectionCard {
            Text("Historique des modifications").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            ForEach(vm.changes, id: \.id) { c in
                changeRow(c)
                if c.id != vm.changes.last?.id {
                    Divider().overlay(palette.outlineVariant)
                }
            }
        }
    }

    private func changeRow(_ c: TreatmentChange) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(formatDateTime(c.atMs)).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            Text(changeDescription(c)).font(.eggCallout).foregroundStyle(palette.onSurface)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Formatting helpers

    private func archive() {
        guard let session = app.session else { return }
        Task {
            if await vm.archive(session, app: app) { dismiss() }
        }
    }

    private func deleteMedication() {
        guard let session = app.session else { return }
        Task {
            if await vm.deleteMedication(session, app: app) { dismiss() }
        }
    }

    private func formatDateTime(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr")
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: date)
    }

    private func doseLabel(_ dose: Double, _ unit: String?) -> String {
        let value = dose.truncatingRemainder(dividingBy: 1) == 0
            ? String(Int(dose))
            : String(format: "%g", dose)
        if let unit, !unit.isEmpty {
            return "\(value) \(unit)"
        }
        return value
    }

    private func doseDetail(_ d: DoseEvent) -> String? {
        let routeText = d.route.map { MedCatalog.routeLabel($0) }
        let siteText = d.injectionSite.map { MedCatalog.injectionSiteLabel($0) }
        switch (routeText, siteText) {
        case let (r?, s?): return "\(r) · \(s)"
        case let (r?, nil): return r
        case let (nil, s?): return s
        default: return nil
        }
    }

    private func changeDescription(_ c: TreatmentChange) -> String {
        let field: String
        switch c.field {
        case "dose": field = "Dose"
        case "unit": field = "Unité"
        case "route": field = "Voie"
        default: field = c.field
        }
        let old = c.oldValue ?? "—"
        let new = c.newValue ?? "—"
        return "\(field) : \(old) → \(new)"
    }
}
