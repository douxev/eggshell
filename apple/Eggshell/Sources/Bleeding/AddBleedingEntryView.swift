import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — create or edit a bleeding / cycle entry. Fields: a date
// picker, the kind (Saignement vs Spotting, both deselectable → "non précisé"),
// a free-text note, and the customizable "bleeding" metric sliders. The scalar
// fields live on the BleedingEntry; the slider values live in the "bleeding"
// metric domain keyed by the (stable) entry id, so editing updates in place and
// the values are replaced wholesale. Mirrors android AddBleedingEntryScreen.
// ===========================================================================

@MainActor
final class AddBleedingEntryViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?

    // Scalar fields
    @Published var date = Date()
    // nil = non précisé, true = spotting, false = saignement complet.
    @Published var isSpotting: Bool?
    @Published var freeText = ""

    // Customizable metric sliders for the "bleeding" domain.
    @Published var definitions: [MetricDefinition] = []
    @Published var values: [Int64: UInt32] = [:]

    private var existingAtMs: Int64?

    func load(_ session: VaultService, entryId: Int64?) async {
        loading = true
        do {
            let defs = try await session.listMetricDefinitions(domain: "bleeding")
                .filter { $0.enabled }
            definitions = defs

            if let id = entryId {
                // Seed slider values from the stored ones before reading the
                // entry, then fall back to each definition's midpoint.
                let stored = try await session.listMetricValues(entryDomain: "bleeding", entryId: id)
                var seeded: [Int64: UInt32] = [:]
                for v in stored { seeded[v.metricId] = v.value }
                values = seeded

                if let e = try await session.getBleedingEntry(id) {
                    existingAtMs = e.atMs
                    date = Date(timeIntervalSince1970: Double(e.atMs) / 1000.0)
                    isSpotting = e.isSpotting
                    freeText = e.freeText ?? ""
                }
            }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func save(_ session: VaultService, entryId: Int64?) async -> Bool {
        do {
            let text = freeText.trimmingCharacters(in: .whitespacesAndNewlines)
            let atMs = existingAtMs ?? Int64(date.timeIntervalSince1970 * 1000)
            let entry = NewBleedingEntry(
                atMs: atMs,
                isSpotting: isSpotting,
                freeText: text.isEmpty ? nil : text)

            // Non-destructive: update keeps the id (and its slider values) stable.
            let saved: BleedingEntry
            if let id = entryId {
                saved = try await session.updateBleedingEntry(id, entry)
            } else {
                saved = try await session.addBleedingEntry(entry)
            }

            let metricValues = definitions.compactMap { def -> MetricValue? in
                guard let v = values[def.id] else { return nil }
                return MetricValue(metricId: def.id, value: v)
            }
            try await session.replaceMetricValues(entryDomain: "bleeding", entryId: saved.id, values: metricValues)
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    func delete(_ session: VaultService, entryId: Int64) async -> Bool {
        do {
            try await session.deleteBleedingEntry(entryId)
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }
}

struct AddBleedingEntryView: View {
    let entryId: Int64?

    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = AddBleedingEntryViewModel()

    init(entryId: Int64?) {
        self.entryId = entryId
    }

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                if vm.loading {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else {
                    dateCard
                    kindCard
                    if !vm.definitions.isEmpty { metricsCard }
                    metricEditorLink
                    notesCard
                }
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle(entryId == nil ? "Nouvelle entrée" : "Modifier l'entrée")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Enregistrer") {
                    if let session = app.session {
                        Task { if await vm.save(session, entryId: entryId) { dismiss() } }
                    }
                }
                .disabled(vm.loading)
            }
            if let id = entryId {
                ToolbarItem(placement: .destructiveAction) {
                    Button("Supprimer", role: .destructive) {
                        if let session = app.session {
                            Task { if await vm.delete(session, entryId: id) { dismiss() } }
                        }
                    }
                }
            }
        }
        .task { if let s = app.session { await vm.load(s, entryId: entryId) } }
    }

    private var dateCard: some View {
        SectionCard {
            Text("Date").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            DatePicker("Date", selection: $vm.date, displayedComponents: [.date])
                .labelsHidden()
                .tint(palette.primary)
                .environment(\.locale, Locale(identifier: "fr"))
        }
    }

    private var kindCard: some View {
        SectionCard {
            Text("Type").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            HStack(spacing: Spacing.s) {
                ChoiceChip(label: "Saignement", selected: vm.isSpotting == false) {
                    vm.isSpotting = (vm.isSpotting == false) ? nil : false
                }
                ChoiceChip(label: "Spotting", selected: vm.isSpotting == true) {
                    vm.isSpotting = (vm.isSpotting == true) ? nil : true
                }
            }
        }
    }

    private var metricsCard: some View {
        SectionCard {
            Text("Métriques").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            MetricSlidersView(definitions: vm.definitions, values: $vm.values)
        }
    }

    private var metricEditorLink: some View {
        Button {
            router.push(.metricEditor(domain: "bleeding"))
        } label: {
            HStack {
                Image(systemName: "slider.horizontal.3").foregroundStyle(palette.primary)
                Text("Personnaliser les métriques").font(.eggCallout).foregroundStyle(palette.primary)
                Spacer()
                Image(systemName: "chevron.right").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.4))
            }
            .padding(Spacing.l)
            .frame(maxWidth: .infinity)
            .glassCard(cornerRadius: Corner.large)
        }
        .buttonStyle(.plain)
    }

    private var notesCard: some View {
        SectionCard {
            Text("Note").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("Notes libres…", text: $vm.freeText, axis: .vertical)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .lineLimit(3...8)
        }
    }
}
