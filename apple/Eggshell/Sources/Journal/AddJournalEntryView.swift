import SwiftUI
import TransitionCore

// « Journal complet » (§6.2) — the detailed entry, new or edited.
//
// The form *is* the content: it has no empty state, only resting positions. The
// indicators come from the catalogue, so a hidden one simply is not drawn and
// the values it already holds are never touched (`replaceMetricValues` only ever
// writes the axes on screen, and the built-ins keep their own columns).
//
// Saving is non-destructive: `updateJournalEntry` when editing, never
// delete-then-add, so the entry id — and everything keyed on it — survives.

@MainActor
final class AddJournalEntryViewModel: ObservableObject {
    @Published var loading = true
    @Published var saving = false
    @Published var error: String?

    /// When the entry happened. Back-datable, never in the future.
    @Published var at = Date()
    /// True once an existing entry actually loaded. Saving before that would
    /// move it to now and reset its axes.
    @Published var entryLoaded = false

    /// Enabled journal indicators, in catalogue order.
    @Published var definitions: [MetricDefinition] = []
    /// Current slider values, keyed by metric id.
    @Published var values: [Int64: UInt32] = [:]

    @Published var freeText = ""
    /// The side-effect field, as the chips it is drawn as. Stored back as the
    /// comma-separated text the rest of the app (and the report) already reads.
    @Published var effects: [String] = []
    /// Effects noted before, offered as chips so the same word stays the same
    /// word instead of drifting into four spellings.
    @Published var suggestions: [String] = []

    /// How far back the suggestion list looks.
    private let suggestionDepth = 200
    /// How many suggestions to offer. Past a dozen, chips stop being a shortcut.
    private let suggestionLimit = 12

    func load(_ session: VaultService, entryId: Int64?) async {
        loading = true
        error = nil
        do {
            let defs = try await session.listMetricDefinitions(domain: "journal")
                .filter { $0.enabled && !$0.archived }
                .sorted { $0.sortOrder < $1.sortOrder }
            definitions = defs

            var seeded: [Int64: UInt32] = [:]

            if let id = entryId, let entry = try await session.getJournalEntry(id) {
                at = Date(timeIntervalSince1970: Double(entry.atMs) / 1000)
                freeText = entry.freeText ?? ""
                effects = JournalView.effects(entry.sideEffects)

                let stored = try await session.listMetricValues(
                    entryDomain: "journal", entryId: id)
                let byId = Dictionary(uniqueKeysWithValues: stored.map { ($0.metricId, $0.value) })
                for def in defs {
                    if let column = def.columnName {
                        if let value = Self.columnValue(entry, column) { seeded[def.id] = value }
                    } else if let value = byId[def.id] {
                        seeded[def.id] = value
                    }
                }
                entryLoaded = true
            } else if entryId != nil {
                error = "Cette entrée n'existe plus."
            } else {
                // A fresh entry rests at the midpoint of each axis: the middle
                // claims nothing, and the form has to start somewhere.
                for def in defs {
                    seeded[def.id] = (def.minValue + def.maxValue) / 2
                }
            }

            values = seeded
            suggestions = await Self.knownEffects(
                session, excluding: effects, depth: suggestionDepth, limit: suggestionLimit)
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
            let sideEffects = effects.joined(separator: ", ")

            let entry = NewJournalEntry(
                atMs: Int64(at.timeIntervalSince1970 * 1000),
                mood: value(forColumn: "mood"),
                dysphoria: value(forColumn: "dysphoria"),
                euphoria: value(forColumn: "euphoria"),
                libido: value(forColumn: "libido"),
                energy: value(forColumn: "energy"),
                freeText: text.isEmpty ? nil : text,
                sideEffects: sideEffects.isEmpty ? nil : sideEffects)

            let saved: JournalEntry
            if let id = entryId {
                saved = try await session.updateJournalEntry(id, entry)
            } else {
                saved = try await session.addJournalEntry(entry)
            }

            // Only the axes that are not backed by a column go to the values
            // table; the five built-ins live on the entry itself.
            let custom = definitions.filter { $0.columnName == nil }
            let metricValues: [MetricValue] = custom.compactMap { def in
                guard let raw = values[def.id] else { return nil }
                return MetricValue(metricId: def.id, value: raw)
            }
            try await session.replaceMetricValues(
                entryDomain: "journal", entryId: saved.id, values: metricValues)
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
            try await session.deleteJournalEntry(entryId)
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    func toggle(_ effect: String) {
        if let index = effects.firstIndex(where: { Self.same($0, effect) }) {
            let removed = effects.remove(at: index)
            if !suggestions.contains(where: { Self.same($0, removed) }) {
                suggestions.insert(removed, at: 0)
            }
        } else {
            effects.append(effect)
            suggestions.removeAll { Self.same($0, effect) }
        }
    }

    /// Adds a hand-typed effect, unless it is one already on the entry under
    /// another spelling.
    func add(_ raw: String) {
        let effect = raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            // A comma is the field's own separator: one entry, one chip.
            .replacingOccurrences(of: ",", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !effect.isEmpty else { return }
        guard !effects.contains(where: { Self.same($0, effect) }) else { return }
        effects.append(effect)
        suggestions.removeAll { Self.same($0, effect) }
    }

    /// Current value of a built-in axis, or nil when that axis is hidden — in
    /// which case the column stays as it was rather than being overwritten.
    private func value(forColumn column: String) -> UInt32? {
        guard let def = definitions.first(where: { $0.columnName == column }) else { return nil }
        return values[def.id]
    }

    private static func columnValue(_ entry: JournalEntry, _ column: String) -> UInt32? {
        switch column {
        case "mood":      return entry.mood
        case "dysphoria": return entry.dysphoria
        case "euphoria":  return entry.euphoria
        case "libido":    return entry.libido
        case "energy":    return entry.energy
        default:          return nil
        }
    }

    /// Distinct effects already noted, most frequent first. Compared without
    /// case or accents, as the report does (D3), so « Fatigue » and « fatigué »
    /// do not both end up in the list.
    private static func knownEffects(
        _ session: VaultService, excluding: [String], depth: Int, limit: Int
    ) async -> [String] {
        let entries = (try? await session.listJournalEntries(limit: Int64(depth))) ?? []
        var counts: [String: Int] = [:]
        var display: [String: String] = [:]
        for entry in entries {
            for effect in JournalView.effects(entry.sideEffects) {
                let key = fold(effect)
                counts[key, default: 0] += 1
                if display[key] == nil { display[key] = effect }
            }
        }
        for effect in excluding { counts.removeValue(forKey: fold(effect)) }
        return counts
            .sorted { lhs, rhs in
                lhs.value == rhs.value ? lhs.key < rhs.key : lhs.value > rhs.value
            }
            .prefix(limit)
            .compactMap { display[$0.key] }
    }

    private static func fold(_ text: String) -> String {
        text.folding(
            options: [.diacriticInsensitive, .caseInsensitive],
            locale: Locale(identifier: "fr_FR"))
    }

    private static func same(_ a: String, _ b: String) -> Bool { fold(a) == fold(b) }
}

// MARK: - Écran

struct AddJournalEntryView: View {
    let entryId: Int64?

    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = AddJournalEntryViewModel()

    @State private var editingDate = false
    @State private var addingEffect = false
    @State private var draftEffect = ""
    @State private var confirmDelete = false
    /// Bumped once on a successful save — drives both the haptic and the
    /// confirmation, so neither can fire without the other.
    @State private var savedTick = 0

    init(entryId: Int64?) {
        self.entryId = entryId
    }

    private var canSave: Bool {
        !vm.loading && !vm.saving && (entryId == nil || vm.entryLoaded)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                dateLine

                if vm.loading {
                    SkeletonBlock(height: 232, cornerRadius: Radius.card)
                    SkeletonBlock(height: 92, cornerRadius: Radius.field)
                } else {
                    slidersCard
                    noteBox
                    effectsBlock
                    customizeLink
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
        .navigationTitle("Ton ressenti")
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
        .sheet(isPresented: $editingDate) { datePickerSheet }
        .alert("Ajouter un effet", isPresented: $addingEffect) {
            TextField("Nausée, tension, bouffées…", text: $draftEffect)
            Button("Ajouter") {
                vm.add(draftEffect)
                draftEffect = ""
            }
            Button("Annuler", role: .cancel) { draftEffect = "" }
        } message: {
            Text("Un mot ou deux suffisent. Tu le retrouveras la prochaine fois.")
        }
        .alert("Supprimer cette entrée ?", isPresented: $confirmDelete) {
            Button("Supprimer", role: .destructive) { delete() }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("Ce que tu avais noté ce jour-là sera effacé. C'est définitif.")
        }
        .task { if let session = app.session { await vm.load(session, entryId: entryId) } }
    }

    // MARK: Ligne de date

    private var dateLine: some View {
        HStack(spacing: 10) {
            Image(systemName: "clock")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(palette.onSurfaceVariant)
            Text(Self.dateLabel(vm.at))
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
            Spacer(minLength: Spacing.s)
            Button("Modifier") { editingDate = true }
                .font(EggFont.micro)
                .foregroundStyle(palette.primary)
                .buttonStyle(.plain)
        }
        .padding(.horizontal, Metrics.screenMargin)
        .padding(.vertical, Spacing.m)
        .frame(minHeight: Metrics.touchTarget)
        .background(
            RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                .fill(palette.surfaceContainer))
    }

    private var datePickerSheet: some View {
        NavigationStack {
            VStack(spacing: Spacing.m) {
                // An entry can be back-dated but never post-dated: a journal
                // records what happened, not what will.
                DatePicker(
                    "Quand", selection: $vm.at, in: ...Date(),
                    displayedComponents: [.date, .hourAndMinute])
                    .datePickerStyle(.graphical)
                    .tint(palette.primary)
                    .environment(\.locale, Locale(identifier: "fr_FR"))
                Spacer(minLength: 0)
            }
            .padding(Metrics.screenMargin)
            .background(palette.surface.ignoresSafeArea())
            .navigationTitle("Quand ?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Terminé") { editingDate = false }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: Curseurs

    private var slidersCard: some View {
        EggCard(variant: .low, paddingH: 18, paddingV: 18, spacing: Spacing.m) {
            if vm.definitions.isEmpty {
                Text("Tous tes indicateurs sont masqués. Réactive ceux qui te parlent — "
                        + "les valeurs déjà notées n'ont pas bougé.")
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                MetricSliderColumn(definitions: vm.definitions, values: $vm.values)
            }
        }
    }

    // MARK: Note libre

    private var noteBox: some View {
        VStack(alignment: .leading, spacing: 6) {
            MicroLabel("NOTE LIBRE")
            TextField("Un mot sur ta journée…", text: $vm.freeText, axis: .vertical)
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

    // MARK: Effets

    private var effectsBlock: some View {
        VStack(alignment: .leading, spacing: 9) {
            MicroLabel("EFFETS RESSENTIS")
            ChipFlowLayout(spacing: 7, lineSpacing: 7) {
                ForEach(vm.effects, id: \.self) { effect in
                    EffectChip(effect, selected: true) { vm.toggle(effect) }
                }
                ForEach(vm.suggestions, id: \.self) { effect in
                    EffectChip(effect, selected: false) { vm.toggle(effect) }
                }
                addEffectChip
            }
        }
    }

    private var addEffectChip: some View {
        Button {
            draftEffect = ""
            addingEffect = true
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "plus").font(.system(size: 12, weight: .bold))
                Text("Ajouter").font(.system(size: 13.5, weight: .semibold))
            }
            .foregroundStyle(palette.primary)
            .padding(.horizontal, 15)
            .frame(height: 36)
            .overlay(Capsule().stroke(palette.outlineVariant, lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Ajouter un effet")
    }

    // MARK: Personnalisation

    private var customizeLink: some View {
        Button {
            router.push(.metricEditor(domain: "journal"))
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
        .padding(.top, Spacing.xs)
    }

    // MARK: Actions

    private func save() {
        guard let session = app.session else { return }
        Task {
            guard await vm.save(session, entryId: entryId) else { return }
            withAnimation(.easeOut(duration: 0.18)) { savedTick += 1 }
            // Long enough to be read, short enough not to be a wait. The
            // confirmation is the haptic *and* the word.
            try? await Task.sleep(nanoseconds: 750_000_000)
            dismiss()
        }
    }

    private func delete() {
        guard let session = app.session, let id = entryId else { return }
        Task { if await vm.delete(session, entryId: id) { dismiss() } }
    }

    // MARK: Formatting

    /// « Aujourd'hui · 21:34 », « Hier · 08:10 », « 24 juillet · 08:10 ».
    static func dateLabel(_ date: Date, now: Date = Date()) -> String {
        let cal = Calendar.current
        let time = DateFormatter()
        time.locale = Locale(identifier: "fr_FR")
        time.dateFormat = "HH:mm"

        let day: String
        if cal.isDate(date, inSameDayAs: now) {
            day = "Aujourd'hui"
        } else if let yesterday = cal.date(byAdding: .day, value: -1, to: now),
                  cal.isDate(date, inSameDayAs: yesterday) {
            day = "Hier"
        } else {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "fr_FR")
            formatter.dateFormat = cal.isDate(date, equalTo: now, toGranularity: .year)
                ? "d MMMM" : "d MMMM yyyy"
            day = formatter.string(from: date)
        }
        return "\(day) · \(time.string(from: date))"
    }
}

// MARK: - Chip d'effet

/// A selectable effect. `PillView` geometry, plus a leading check when the chip
/// is on: the fill and the glyph say the same thing, so the state never rests on
/// colour alone (§10).
private struct EffectChip: View {
    @Environment(\.palette) private var palette

    let label: String
    let selected: Bool
    let action: () -> Void

    init(_ label: String, selected: Bool, action: @escaping () -> Void) {
        self.label = label
        self.selected = selected
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if selected {
                    Image(systemName: "checkmark").font(.system(size: 11, weight: .bold))
                }
                Text(label).font(.system(size: 13.5, weight: .semibold))
            }
            .foregroundStyle(selected ? palette.onSecondaryContainer : palette.onSurfaceVariant)
            .padding(.horizontal, 15)
            .frame(height: 36)
            .background(
                selected ? palette.secondaryContainer : palette.surfaceContainerLow,
                in: Capsule())
            .overlay {
                if !selected {
                    Capsule().stroke(palette.outlineVariant, lineWidth: 1)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}
