import SwiftUI
import TransitionCore

// « Noter mes menstruations » — one day or a whole span, new or edited.
//
// The kind chips are deselectable on purpose: tapping the selected one again
// clears it back to « non précisé », because not knowing is an honest answer and
// the form must not force a category. « Plusieurs jours » writes one entry per
// day at noon local — noon keeps each entry inside its calendar day across every
// DST shift — and each day stays individually editable afterwards.

@MainActor
final class AddBleedingEntryViewModel: ObservableObject {
    @Published var loading = true
    /// True while a save/delete is in flight — gates the action bar so a
    /// double-tap can't commit a multi-day span twice.
    @Published var saving = false
    /// Edit mode only: true once the entry (and its slider values) actually
    /// loaded. Saving an unseeded form would move the entry to today and wipe
    /// its indicators.
    @Published var entryLoaded = false
    @Published var error: String?

    @Published var date = Date()
    /// nil = non précisé, true = spotting, false = menstruations.
    @Published var isSpotting: Bool?
    @Published var freeText = ""

    /// Span mode (create only): log « cette semaine = menstruations » in one action.
    @Published var rangeMode = false
    @Published var rangeStart = Date()
    @Published var rangeEnd = Date()

    /// The configurable indicators of the « bleeding » domain.
    @Published var definitions: [MetricDefinition] = []
    @Published var values: [Int64: UInt32] = [:]

    /// Days in the selected span, inclusive; 0 when the span is inverted.
    var rangeDayCount: Int {
        let cal = Calendar.current
        let start = cal.startOfDay(for: rangeStart)
        let end = cal.startOfDay(for: rangeEnd)
        guard let days = cal.dateComponents([.day], from: start, to: end).day, days >= 0 else {
            return 0
        }
        return days + 1
    }

    func load(_ session: VaultService, entryId: Int64?) async {
        loading = true
        error = nil
        do {
            definitions = try await session.listMetricDefinitions(domain: "bleeding")
                .filter { $0.enabled && !$0.archived }
                .sorted { $0.sortOrder < $1.sortOrder }

            if let id = entryId {
                // Seed the sliders from what was stored before reading the entry,
                // then let `MetricSliderColumn` rest the untouched ones.
                let stored = try await session.listMetricValues(entryDomain: "bleeding", entryId: id)
                values = Dictionary(uniqueKeysWithValues: stored.map { ($0.metricId, $0.value) })

                if let entry = try await session.getBleedingEntry(id) {
                    date = Date(timeIntervalSince1970: Double(entry.atMs) / 1000)
                    isSpotting = entry.isSpotting
                    freeText = entry.freeText ?? ""
                    entryLoaded = true
                } else {
                    error = "Cette entrée n'existe plus."
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
            let metricValues: [MetricValue] = definitions.compactMap { def in
                guard let raw = values[def.id] else { return nil }
                return MetricValue(metricId: def.id, value: raw)
            }

            if rangeMode && entryId == nil {
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
                    day = cal.date(byAdding: .day, value: 1, to: day)
                        ?? day.addingTimeInterval(86_400)
                }
                let saved = try await session.addBleedingEntries(entries)
                // The indicator writes are best effort: the days are already
                // committed, and surfacing an error here would invite a retry
                // that duplicates the whole span.
                do {
                    for entry in saved {
                        try await session.replaceMetricValues(
                            entryDomain: "bleeding", entryId: entry.id, values: metricValues)
                    }
                } catch { /* non-fatal, see above */ }
                return true
            }

            let entry = NewBleedingEntry(
                atMs: Int64(date.timeIntervalSince1970 * 1000),
                isSpotting: isSpotting,
                freeText: text.isEmpty ? nil : text)

            // Non-destructive: an update keeps the id, and with it the values.
            let saved: BleedingEntry
            if let id = entryId {
                saved = try await session.updateBleedingEntry(id, entry)
            } else {
                saved = try await session.addBleedingEntry(entry)
            }
            try await session.replaceMetricValues(
                entryDomain: "bleeding", entryId: saved.id, values: metricValues)
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

// MARK: - Écran

struct AddBleedingEntryView: View {
    let entryId: Int64?

    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = AddBleedingEntryViewModel()

    @State private var confirmDelete = false
    @State private var savedTick = 0

    init(entryId: Int64?) {
        self.entryId = entryId
    }

    /// The segmented selector speaks in indices; the model speaks in a mode.
    private var modeIndex: Binding<Int> {
        Binding(
            get: { vm.rangeMode ? 1 : 0 },
            set: { vm.rangeMode = $0 == 1 })
    }

    private var canSave: Bool {
        guard !vm.loading, !vm.saving else { return false }
        if entryId != nil { return vm.entryLoaded }
        return !vm.rangeMode || vm.rangeDayCount >= 1
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                if entryId == nil {
                    SegmentedSelector(
                        options: ["Un jour", "Plusieurs jours"],
                        selection: modeIndex,
                        accessibilityLabel: "Ce que tu notes")
                }

                if vm.loading {
                    SkeletonBlock(height: 88, cornerRadius: Radius.card)
                    SkeletonBlock(height: 164, cornerRadius: Radius.card)
                } else {
                    if vm.rangeMode && entryId == nil { rangeCard } else { dateCard }
                    kindBlock
                    if !vm.definitions.isEmpty { slidersCard }
                    customizeLink
                    noteBox
                }

                if let message = vm.error {
                    ErrorCardView(message)
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
            .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle(entryId == nil ? "Noter mes menstruations" : "Modifier")
        .navigationBarTitleDisplayMode(.inline)
        .eggActionBar {
            ActionBarButton("Enregistrer", systemImage: "checkmark", enabled: canSave) { save() }
        }
        .overlay(alignment: .bottom) {
            if savedTick > 0 {
                SnackbarView(message: "Enregistré ✓")
                    .padding(.bottom, Metrics.actionBarHeight + Spacing.m)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .sensoryFeedback(.success, trigger: savedTick)
        .toolbar {
            if entryId != nil {
                ToolbarItem(placement: .destructiveAction) {
                    Button("Supprimer", role: .destructive) { confirmDelete = true }
                        .disabled(vm.saving)
                }
            }
        }
        .alert("Supprimer ce jour ?", isPresented: $confirmDelete) {
            Button("Supprimer", role: .destructive) { delete() }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("Ce jour disparaîtra de ton calendrier. C'est définitif.")
        }
        .task { if let session = app.session { await vm.load(session, entryId: entryId) } }
    }

    // MARK: Quand

    private var dateCard: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            MicroLabel("QUAND")
            // Back-datable, never post-dated: the log records what happened.
            DatePicker(
                "Quand", selection: $vm.date, in: ...Date(),
                displayedComponents: [.date, .hourAndMinute])
                .labelsHidden()
                .tint(palette.primary)
                .environment(\.locale, Locale(identifier: "fr_FR"))
        }
    }

    private var rangeCard: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            MicroLabel("DU PREMIER AU DERNIER JOUR")
            DatePicker("Début", selection: $vm.rangeStart, in: ...Date(),
                       displayedComponents: [.date])
                .font(.eggBody)
                .tint(palette.primary)
                .environment(\.locale, Locale(identifier: "fr_FR"))
            DatePicker("Fin", selection: $vm.rangeEnd, in: ...Date(),
                       displayedComponents: [.date])
                .font(.eggBody)
                .tint(palette.primary)
                .environment(\.locale, Locale(identifier: "fr_FR"))
            if vm.rangeDayCount >= 1 {
                Text(vm.rangeDayCount == 1
                        ? "Un jour sera noté."
                        : "\(vm.rangeDayCount) jours seront notés, un par jour. "
                            + "Tu pourras retoucher chacun.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text("Le dernier jour arrive avant le premier — inverse-les.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.error)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: Type

    private var kindBlock: some View {
        VStack(alignment: .leading, spacing: 9) {
            MicroLabel("CE QUE C'EST")
            HStack(spacing: 7) {
                PillView("Menstruations", selected: vm.isSpotting == false) {
                    vm.isSpotting = vm.isSpotting == false ? nil : false
                }
                PillView("Spotting", selected: vm.isSpotting == true) {
                    vm.isSpotting = vm.isSpotting == true ? nil : true
                }
            }
            if vm.isSpotting == nil {
                Text("Tu peux laisser les deux de côté : ce jour restera « non précisé ».")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: Curseurs

    private var slidersCard: some View {
        EggCard(variant: .low, paddingH: 18, paddingV: 18, spacing: Spacing.m) {
            MetricSliderColumn(definitions: vm.definitions, values: $vm.values)
        }
    }

    private var customizeLink: some View {
        Button {
            router.push(.metricEditor(domain: "bleeding"))
        } label: {
            HStack(spacing: 9) {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 15, weight: .semibold))
                Text("Personnaliser les indicateurs")
                    .font(EggFont.micro)
                    .tracking(0.5)
                Spacer(minLength: 0)
            }
            .foregroundStyle(palette.primary)
            .frame(minHeight: Metrics.touchTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Note

    private var noteBox: some View {
        VStack(alignment: .leading, spacing: 6) {
            MicroLabel("NOTE LIBRE")
            TextField("Ce que tu veux garder en tête…", text: $vm.freeText, axis: .vertical)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .tint(palette.primary)
                .lineLimit(3...8)
        }
        .padding(.horizontal, Metrics.screenMargin)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(
            RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                .stroke(palette.outlineVariant, lineWidth: 1))
    }

    // MARK: Actions

    private func save() {
        guard let session = app.session else { return }
        Task {
            guard await vm.save(session, entryId: entryId) else { return }
            withAnimation(.easeOut(duration: 0.18)) { savedTick += 1 }
            try? await Task.sleep(nanoseconds: 750_000_000)
            dismiss()
        }
    }

    private func delete() {
        guard let session = app.session, let id = entryId else { return }
        Task { if await vm.delete(session, entryId: id) { dismiss() } }
    }
}
