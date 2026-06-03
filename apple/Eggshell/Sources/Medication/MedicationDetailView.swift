import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen: medication detail. Shows header, schedules (with pause/resume
// + add), and dose history. Follows the reference VM/View shape from
// Today/TodayView.swift. All UI strings in French.
// ===========================================================================

@MainActor
final class MedicationDetailViewModel: ObservableObject {
    @Published var loading = true
    @Published var med: Medication?
    @Published var schedules: [DoseSchedule] = []
    @Published var doses: [DoseEvent] = []
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
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func toggleActive(_ schedule: DoseSchedule, session: VaultService) async {
        do {
            try await session.setScheduleActive(schedule.id, !schedule.active)
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }
}

struct MedicationDetailView: View {
    let medId: Int64

    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm: MedicationDetailViewModel

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
                    schedulesCard
                    historyCard
                }
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.m)
        }
        .navigationTitle(vm.med?.name ?? "Médicament")
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
                    Pill(text: kindLabel(m.kind))
                    Pill(text: routeLabel(m.route))
                }
                if let dose = m.defaultDose {
                    Text("Dose par défaut : \(doseLabel(dose, m.defaultDoseUnit))")
                        .font(.eggCallout).foregroundStyle(palette.onSurface)
                }
                if let notes = m.notes, !notes.isEmpty {
                    Text(notes).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
                }
            } else {
                Text("Médicament introuvable").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
            }
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
            Button("Ajouter un planning") {
                router.push(.addSchedule(medId: medId))
            }
            .glassButton().tint(palette.primary)
        }
    }

    private func scheduleRow(_ s: DoseSchedule) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(NextDueCalculator.describe(s)).font(.eggCallout).foregroundStyle(palette.onSurface)
                Text("Prochaine : \(formatDateTime(s.nextDueAtMs))")
                    .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                if !s.active {
                    Text("En pause").font(.eggCaption).foregroundStyle(palette.error)
                }
            }
            Spacer()
            Button(s.active ? "Pause" : "Reprendre") {
                if let session = app.session {
                    Task { await vm.toggleActive(s, session: session) }
                }
            }
            .glassButton().tint(palette.primary)
        }
    }

    // MARK: - History

    private var historyCard: some View {
        SectionCard {
            Text("Historique").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
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
            }
            if let site = injectionDescription(d) {
                Text(site).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
            }
            if let notes = d.notes, !notes.isEmpty {
                Text(notes).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            }
        }
    }

    // MARK: - Formatting helpers

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

    private func injectionDescription(_ d: DoseEvent) -> String? {
        let routeText = d.route.map { routeLabel($0) }
        let siteText = d.injectionSite
        switch (routeText, siteText) {
        case let (r?, s?):
            return "\(r) · \(s)"
        case let (r?, nil):
            return r
        case let (nil, s?):
            return s
        default:
            return nil
        }
    }

    private func kindLabel(_ kind: String) -> String {
        switch kind {
        case "hrt": return "THS"
        case "blocker": return "Anti-androgène"
        case "supplement": return "Complément"
        case "other": return "Autre"
        default: return kind
        }
    }

    private func routeLabel(_ route: String) -> String {
        switch route {
        case "oral": return "Orale"
        case "injection_im": return "Injection IM"
        case "injection_sc": return "Injection SC"
        case "transdermal": return "Transdermique"
        case "topical": return "Topique"
        case "sublingual": return "Sublinguale"
        case "other": return "Autre"
        default: return route
        }
    }
}
