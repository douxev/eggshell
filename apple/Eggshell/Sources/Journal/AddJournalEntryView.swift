import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — create or edit a journal entry. The gauges are driven by
// the user-configurable journal MetricDefinitions (built-ins backed by entry
// columns + custom metrics stored in the metric_values table). A free-text
// note and a side-effects field round it out. Saving is NON-destructive:
// addJournalEntry for new entries, updateJournalEntry when editing (never
// delete+add), then replaceMetricValues for the custom sliders. A link opens
// the metric editor. Mirrors android AddJournalEntryScreen.
// ===========================================================================

@MainActor
final class AddJournalEntryViewModel: ObservableObject {
    @Published var loading = true
    @Published var saving = false
    @Published var error: String?

    // Existing entry context (when editing).
    @Published var existingAtMs: Int64?

    // Journal metric definitions to render (built-in + custom, enabled only).
    @Published var definitions: [MetricDefinition] = []
    // Current slider values keyed by metric id.
    @Published var values: [Int64: UInt32] = [:]

    @Published var freeText = ""
    @Published var sideEffects = ""

    func load(_ session: VaultService, entryId: Int64?) async {
        loading = true
        do {
            let defs = try await session.listMetricDefinitions(domain: "journal")
                .filter { $0.enabled && !$0.archived }
                .sorted { $0.sortOrder < $1.sortOrder }
            definitions = defs

            var seeded: [Int64: UInt32] = [:]

            if let id = entryId, let e = try await session.getJournalEntry(id) {
                existingAtMs = e.atMs
                freeText = e.freeText ?? ""
                sideEffects = e.sideEffects ?? ""

                // Custom (non-column) metric values from the metric_values table.
                let stored = try await session.listMetricValues(entryDomain: "journal", entryId: id)
                let storedById = Dictionary(uniqueKeysWithValues: stored.map { ($0.metricId, $0.value) })

                for def in defs {
                    if let column = def.columnName {
                        if let v = columnValue(e, column) { seeded[def.id] = v }
                    } else if let v = storedById[def.id] {
                        seeded[def.id] = v
                    }
                }
            } else {
                // New entry: pre-fill each gauge at its midpoint so the slider
                // starts somewhere sensible.
                for def in defs {
                    seeded[def.id] = UInt32((def.minValue + def.maxValue) / 2)
                }
            }

            values = seeded
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func save(_ session: VaultService, entryId: Int64?) async -> Bool {
        saving = true
        defer { saving = false }
        do {
            let text = freeText.trimmingCharacters(in: .whitespacesAndNewlines)
            let effects = sideEffects.trimmingCharacters(in: .whitespacesAndNewlines)

            let entry = NewJournalEntry(
                atMs: existingAtMs ?? Time.nowMs(),
                mood: columnValueFor("mood"),
                dysphoria: columnValueFor("dysphoria"),
                euphoria: columnValueFor("euphoria"),
                libido: columnValueFor("libido"),
                energy: columnValueFor("energy"),
                freeText: text.isEmpty ? nil : text,
                sideEffects: effects.isEmpty ? nil : effects)

            let savedId: Int64
            if let id = entryId {
                let saved = try await session.updateJournalEntry(id, entry)
                savedId = saved.id
            } else {
                let saved = try await session.addJournalEntry(entry)
                savedId = saved.id
            }

            // Persist the custom (non-column-backed) sliders.
            let customDefs = definitions.filter { $0.columnName == nil }
            let metricValues: [MetricValue] = customDefs.compactMap { def in
                guard let v = values[def.id] else { return nil }
                return MetricValue(metricId: def.id, value: v)
            }
            try await session.replaceMetricValues(
                entryDomain: "journal", entryId: savedId, values: metricValues)

            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    func delete(_ session: VaultService, entryId: Int64) async -> Bool {
        do {
            try await session.deleteJournalEntry(entryId)
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    /// Read a built-in gauge value off a loaded entry by its backing column.
    private func columnValue(_ entry: JournalEntry, _ column: String) -> UInt32? {
        switch column {
        case "mood": return entry.mood
        case "dysphoria": return entry.dysphoria
        case "euphoria": return entry.euphoria
        case "libido": return entry.libido
        case "energy": return entry.energy
        default: return nil
        }
    }

    /// Current value to persist into a built-in journal column, or nil when no
    /// definition is backed by that column (gauge hidden → leave column null).
    private func columnValueFor(_ column: String) -> UInt32? {
        guard let def = definitions.first(where: { $0.columnName == column }) else { return nil }
        return values[def.id]
    }
}

struct AddJournalEntryView: View {
    let entryId: Int64?

    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = AddJournalEntryViewModel()

    init(entryId: Int64?) {
        self.entryId = entryId
    }

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                if vm.loading {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else {
                    gaugesCard
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
                .disabled(vm.loading || vm.saving)
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

    private var gaugesCard: some View {
        SectionCard {
            Text("Ressenti").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))

            if vm.definitions.isEmpty {
                Text("Aucune mesure activée")
                    .font(.eggCallout)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
            } else {
                MetricSlidersView(definitions: vm.definitions, values: $vm.values)
            }

            NavigationLink(value: Route.metricEditor(domain: "journal")) {
                HStack(spacing: Spacing.s) {
                    Image(systemName: "slider.horizontal.3")
                    Text("Personnaliser les mesures").font(.eggCallout)
                    Spacer()
                    Image(systemName: "chevron.right").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.4))
                }
                .foregroundStyle(palette.primary)
            }
            .buttonStyle(.plain)
        }
    }

    private var notesCard: some View {
        SectionCard {
            Text("Notes").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("Comment s'est passée ta journée ?", text: $vm.freeText, axis: .vertical)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .lineLimit(3...8)
            Text("Effets indésirables").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("nausée, fatigue…", text: $vm.sideEffects)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
        }
    }
}
