import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — create or edit a bleeding / cycle entry. Fields: a date+time
// picker (past only, editable when editing too), the kind (Saignement vs
// Spotting, both deselectable → "non précisé"), a free-text note, and the
// customizable "bleeding" metric sliders. The scalar fields live on the
// BleedingEntry; the slider values live in the "bleeding" metric domain keyed
// by the (stable) entry id, so editing updates in place and the values are
// replaced wholesale. Create only: a « Plusieurs jours » range mode logs one
// entry per day at 12:00 local in one action (addBleedingEntries), all sharing
// the same kind/note/slider values. Mirrors android AddBleedingEntryScreen.
// ===========================================================================

@MainActor
final class AddBleedingEntryViewModel: ObservableObject {
    @Published var loading = true
    /// True while a save/delete is in flight — gates the toolbar buttons so a
    /// double-tap can't commit a multi-day range twice.
    @Published var saving = false
    /// Edit mode only: true once the entry (and its slider values) actually
    /// loaded. Saving an unseeded form would move the entry to today and wipe
    /// its sliders.
    @Published var entryLoaded = false
    @Published var error: String?

    // Scalar fields
    @Published var date = Date()
    // nil = non précisé, true = spotting, false = saignement complet.
    @Published var isSpotting: Bool?
    @Published var freeText = ""

    // Range mode (create only): log « cette semaine = règles » in one action.
    @Published var rangeMode = false
    @Published var rangeStart = Date()
    @Published var rangeEnd = Date()

    // Customizable metric sliders for the "bleeding" domain.
    @Published var definitions: [MetricDefinition] = []
    @Published var values: [Int64: UInt32] = [:]

    /// Days in the selected span, inclusive; 0 when the range is inverted.
    var rangeDayCount: Int {
        let cal = Calendar.current
        let start = cal.startOfDay(for: rangeStart)
        let end = cal.startOfDay(for: rangeEnd)
        guard let days = cal.dateComponents([.day], from: start, to: end).day, days >= 0 else { return 0 }
        return days + 1
    }

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
                    date = Date(timeIntervalSince1970: Double(e.atMs) / 1000.0)
                    isSpotting = e.isSpotting
                    freeText = e.freeText ?? ""
                    entryLoaded = true
                } else {
                    self.error = "Entrée introuvable."
                }
            }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func save(_ session: VaultService, entryId: Int64?) async -> Bool {
        if saving { return false }
        saving = true
        defer { saving = false }
        do {
            let text = freeText.trimmingCharacters(in: .whitespacesAndNewlines)
            let metricValues = definitions.compactMap { def -> MetricValue? in
                guard let v = values[def.id] else { return nil }
                return MetricValue(metricId: def.id, value: v)
            }

            if rangeMode && entryId == nil {
                // One entry per day at 12:00 local — noon keeps each entry
                // inside its calendar day across every DST shift.
                let cal = Calendar.current
                let endDay = cal.startOfDay(for: rangeEnd)
                var day = cal.startOfDay(for: rangeStart)
                var entries: [NewBleedingEntry] = []
                while day <= endDay {
                    let noon = cal.date(bySettingHour: 12, minute: 0, second: 0, of: day) ?? day
                    entries.append(NewBleedingEntry(
                        atMs: Int64(noon.timeIntervalSince1970 * 1000),
                        isSpotting: isSpotting,
                        freeText: text.isEmpty ? nil : text))
                    day = cal.date(byAdding: .day, value: 1, to: day) ?? day.addingTimeInterval(86_400)
                }
                let saved = try await session.addBleedingEntries(entries)
                // Slider writes are best-effort: the days are committed, and
                // surfacing an error here would invite a retry that duplicates
                // the whole span. Each day stays individually editable.
                do {
                    for e in saved {
                        try await session.replaceMetricValues(entryDomain: "bleeding", entryId: e.id, values: metricValues)
                    }
                } catch { /* non-fatal, see above */ }
                return true
            }

            let entry = NewBleedingEntry(
                atMs: Int64(date.timeIntervalSince1970 * 1000),
                isSpotting: isSpotting,
                freeText: text.isEmpty ? nil : text)

            // Non-destructive: update keeps the id (and its slider values) stable.
            let saved: BleedingEntry
            if let id = entryId {
                saved = try await session.updateBleedingEntry(id, entry)
            } else {
                saved = try await session.addBleedingEntry(entry)
            }
            try await session.replaceMetricValues(entryDomain: "bleeding", entryId: saved.id, values: metricValues)
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    func delete(_ session: VaultService, entryId: Int64) async -> Bool {
        if saving { return false }
        saving = true
        defer { saving = false }
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
                    if entryId == nil { modeCard }
                    if vm.rangeMode && entryId == nil { rangeCard } else { dateCard }
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
                .disabled(vm.loading || vm.saving ||
                    (entryId != nil && !vm.entryLoaded) ||
                    (entryId == nil && vm.rangeMode && vm.rangeDayCount < 1))
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

    private var modeCard: some View {
        Picker("Mode", selection: $vm.rangeMode) {
            Text("Un jour").tag(false)
            Text("Plusieurs jours").tag(true)
        }
        .pickerStyle(.segmented)
    }

    private var dateCard: some View {
        SectionCard {
            Text("Date").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            // An entry can only be back-dated — cap the picker at now.
            DatePicker("Date", selection: $vm.date, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                .labelsHidden()
                .tint(palette.primary)
                .environment(\.locale, Locale(identifier: "fr"))
        }
    }

    private var rangeCard: some View {
        SectionCard {
            Text("Période").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            DatePicker("Début", selection: $vm.rangeStart, in: ...Date(), displayedComponents: [.date])
                .font(.eggBody)
                .tint(palette.primary)
                .environment(\.locale, Locale(identifier: "fr"))
            DatePicker("Fin", selection: $vm.rangeEnd, in: ...Date(), displayedComponents: [.date])
                .font(.eggBody)
                .tint(palette.primary)
                .environment(\.locale, Locale(identifier: "fr"))
            if vm.rangeDayCount >= 1 {
                Text("\(vm.rangeDayCount) jours seront enregistrés.")
                    .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            }
        }
    }

    private var kindCard: some View {
        SectionCard {
            Text("Type").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            HStack(spacing: Spacing.s) {
                ChoiceChip(label: "Règles", selected: vm.isSpotting == false) {
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
