import SwiftUI
import TransitionCore

@MainActor
final class LogDoseViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?

    @Published var med: Medication?
    @Published var isInjection = false
    @Published var sites: [String] = []
    @Published var suggestedSite: String?

    // Editable fields
    @Published var doseText = ""
    @Published var unit = ""
    @Published var selectedSite: String?
    @Published var notes = ""

    @Published var saving = false

    func load(_ session: VaultService, medId: Int64) async {
        loading = true
        do {
            let m = try await session.getMedication(medId)
            med = m
            if let m {
                if let dose = m.defaultDose { doseText = formatDose(dose) }
                unit = m.defaultDoseUnit ?? ""
                if m.route.hasPrefix("injection") {
                    isInjection = true
                    sites = standardInjectionSites()
                    let suggestion = try await session.suggestNextInjectionSite(medicationId: medId)
                    suggestedSite = suggestion
                    selectedSite = suggestion
                }
            }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    private func formatDose(_ value: Double) -> String {
        if value == value.rounded() {
            return String(Int(value))
        }
        return String(value)
    }

    func save(_ session: VaultService, medId: Int64) async -> Bool {
        saving = true
        defer { saving = false }
        let parsed = Double(doseText.replacingOccurrences(of: ",", with: "."))
        do {
            _ = try await session.logDose(NewDoseEvent(
                medicationId: medId,
                takenAtMs: Time.nowMs(),
                dose: parsed,
                doseUnit: unit.isEmpty ? nil : unit,
                route: med?.route,
                injectionSite: selectedSite,
                notes: notes.isEmpty ? nil : notes))
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }
}

struct LogDoseView: View {
    let medId: Int64

    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = LogDoseViewModel()

    init(medId: Int64) {
        self.medId = medId
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                if vm.loading {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else {
                    if let med = vm.med {
                        Text(med.name).font(.eggTitle).foregroundStyle(palette.onSurface)
                    }
                    doseCard
                    if vm.isInjection { siteCard }
                    notesCard
                    saveButton
                }
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Enregistrer une prise")
        .task { if let s = app.session { await vm.load(s, medId: medId) } }
    }

    private var doseCard: some View {
        SectionCard {
            Text("Dose").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            HStack(spacing: Spacing.m) {
                TextField("0", text: $vm.doseText)
                    .keyboardType(.decimalPad)
                    .font(.eggHeadline)
                    .foregroundStyle(palette.onSurface)
                    .padding(Spacing.m)
                    .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: Corner.medium, style: .continuous))
                TextField("unité", text: $vm.unit)
                    .font(.eggHeadline)
                    .foregroundStyle(palette.onSurface)
                    .padding(Spacing.m)
                    .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: Corner.medium, style: .continuous))
                    .frame(maxWidth: 120)
            }
        }
    }

    private var siteCard: some View {
        SectionCard {
            Text("Site d'injection").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            if let suggested = vm.suggestedSite {
                Text("Suggéré : \(suggested)").font(.eggCaption).foregroundStyle(palette.primary)
            }
            FlowChips(items: vm.sites, selected: vm.selectedSite, suggested: vm.suggestedSite) { site in
                vm.selectedSite = site
            }
        }
    }

    private var notesCard: some View {
        SectionCard {
            Text("Notes").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("Remarques (optionnel)", text: $vm.notes, axis: .vertical)
                .lineLimit(3...6)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .padding(Spacing.m)
                .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: Corner.medium, style: .continuous))
        }
    }

    private var saveButton: some View {
        Button {
            guard let session = app.session else { return }
            Task {
                if await vm.save(session, medId: medId) { dismiss() }
            }
        } label: {
            Text("Enregistrer")
                .font(.eggHeadline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.s)
        }
        .glassProminentButton()
        .tint(palette.primary)
        .disabled(vm.saving)
    }
}

// Wrapping row of ChoiceChips for injection sites.
private struct FlowChips: View {
    let items: [String]
    let selected: String?
    let suggested: String?
    let onSelect: (String) -> Void

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: Spacing.s)], alignment: .leading, spacing: Spacing.s) {
            ForEach(items, id: \.self) { site in
                ChoiceChip(
                    label: site == suggested ? "\(site) ★" : site,
                    selected: selected == site
                ) {
                    onSelect(site)
                }
            }
        }
    }
}
