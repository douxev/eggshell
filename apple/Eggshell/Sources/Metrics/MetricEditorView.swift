import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen: customizable-metric (slider) editor for a given `domain`
// ("journal" or "bleeding"). Lists the active MetricDefinitions, lets you add
// a custom slider, rename it / change its emojis, toggle it on/off, reorder it
// (up/down buttons that swap sortOrder), and archive (soft-delete) custom ones.
// Built-in gauges can only be toggled and reordered — never renamed or removed,
// matching the Android MetricEditorScreen behaviour. All UI strings in French.
// ===========================================================================

@MainActor
final class MetricEditorViewModel: ObservableObject {
    @Published var loading = true
    @Published var defs: [MetricDefinition] = []
    @Published var error: String?

    let domain: String

    init(domain: String) {
        self.domain = domain
    }

    func load(_ session: VaultService) async {
        loading = true
        do {
            defs = try await session.listMetricDefinitions(domain: domain, includeArchived: false)
                .sorted { $0.sortOrder < $1.sortOrder }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    /// Adds a new custom slider with a unique key and the next free sortOrder.
    func add(label: String, left: String?, right: String?, session: VaultService) async {
        let nextOrder = (defs.map(\.sortOrder).max() ?? -1) + 1
        do {
            _ = try await session.addMetricDefinition(
                NewMetricDefinition(
                    domain: domain,
                    metricKey: Self.makeKey(),
                    label: label,
                    emojiLeft: left,
                    emojiRight: right,
                    minValue: 0,
                    maxValue: 5,
                    sortOrder: nextOrder,
                    createdAtMs: Time.nowMs()
                )
            )
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    func rename(_ def: MetricDefinition, label: String, left: String?, right: String?, session: VaultService) async {
        await apply(def, label: label, left: left, right: right, sortOrder: def.sortOrder, enabled: def.enabled, session: session)
    }

    func setEnabled(_ def: MetricDefinition, _ enabled: Bool, session: VaultService) async {
        await apply(def, label: def.label, left: def.emojiLeft, right: def.emojiRight, sortOrder: def.sortOrder, enabled: enabled, session: session)
    }

    /// Moves the definition at `index` by `delta` (-1 up, +1 down) by swapping
    /// its sortOrder with the neighbour's, then persisting both rows.
    func move(_ index: Int, _ delta: Int, session: VaultService) async {
        let other = index + delta
        guard defs.indices.contains(index), defs.indices.contains(other) else { return }
        let a = defs[index]
        let b = defs[other]
        do {
            try await session.updateMetricDefinition(a.id, update(a, sortOrder: b.sortOrder))
            try await session.updateMetricDefinition(b.id, update(b, sortOrder: a.sortOrder))
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    func archive(_ def: MetricDefinition, session: VaultService) async {
        do {
            try await session.archiveMetricDefinition(def.id)
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    private func apply(_ def: MetricDefinition, label: String, left: String?, right: String?, sortOrder: Int64, enabled: Bool, session: VaultService) async {
        do {
            try await session.updateMetricDefinition(
                def.id,
                MetricDefinitionUpdate(label: label, emojiLeft: left, emojiRight: right, sortOrder: sortOrder, enabled: enabled)
            )
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    private func update(_ def: MetricDefinition, sortOrder: Int64) -> MetricDefinitionUpdate {
        MetricDefinitionUpdate(
            label: def.label,
            emojiLeft: def.emojiLeft,
            emojiRight: def.emojiRight,
            sortOrder: sortOrder,
            enabled: def.enabled
        )
    }

    private static func makeKey() -> String {
        "custom_\(Time.nowMs())_\(UUID().uuidString.prefix(8))"
    }
}

struct MetricEditorView: View {
    let domain: String

    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @StateObject private var vm: MetricEditorViewModel

    // Sheet/alert state.
    @State private var adding = false
    @State private var editTarget: MetricDefinition?
    @State private var confirmDelete: MetricDefinition?

    init(domain: String) {
        self.domain = domain
        _vm = StateObject(wrappedValue: MetricEditorViewModel(domain: domain))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.m) {
                Text("Personnalise tes jauges : renomme-les, change leurs emojis, réordonne-les ou ajoute les tiennes.")
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)

                if vm.loading {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else if vm.defs.isEmpty {
                    EmptyStateCard(text: "Aucune jauge", systemImage: "slider.horizontal.3")
                } else {
                    ForEach(Array(vm.defs.enumerated()), id: \.element.id) { index, def in
                        metricRow(def, index: index)
                    }
                }

                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Jauges")
        .overlay(alignment: .bottomTrailing) {
            Button {
                adding = true
            } label: {
                Image(systemName: "plus").font(.title2.weight(.semibold)).frame(width: 60, height: 60)
            }
            .glassProminentButton().tint(palette.primary)
            .clipShape(Circle())
            .padding(Spacing.xl)
        }
        .task { if let s = app.session { await vm.load(s) } }
        .sheet(isPresented: $adding) {
            MetricEditorSheet(initial: nil) { label, left, right in
                adding = false
                if let s = app.session {
                    Task { await vm.add(label: label, left: left, right: right, session: s) }
                }
            } onCancel: {
                adding = false
            }
        }
        .sheet(isPresented: editBinding) {
            if let target = editTarget {
                MetricEditorSheet(initial: target) { label, left, right in
                    editTarget = nil
                    if let s = app.session {
                        Task { await vm.rename(target, label: label, left: left, right: right, session: s) }
                    }
                } onCancel: {
                    editTarget = nil
                }
            }
        }
        .alert("Supprimer la jauge ?", isPresented: deleteBinding, presenting: confirmDelete) { target in
            Button("Supprimer", role: .destructive) {
                if let s = app.session {
                    Task { await vm.archive(target, session: s) }
                }
                confirmDelete = nil
            }
            Button("Annuler", role: .cancel) { confirmDelete = nil }
        } message: { target in
            Text("« \(MetricCatalog.displayLabel(target)) » sera retirée. Les valeurs déjà enregistrées sont conservées.")
        }
    }

    private var deleteBinding: Binding<Bool> {
        Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })
    }

    private var editBinding: Binding<Bool> {
        Binding(get: { editTarget != nil }, set: { if !$0 { editTarget = nil } })
    }

    // MARK: - Row

    private func metricRow(_ def: MetricDefinition, index: Int) -> some View {
        SectionCard(padding: Spacing.m) {
            HStack(spacing: Spacing.s) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(metricTitle(def)).font(.eggCallout).foregroundStyle(palette.onSurface)
                    if def.builtin {
                        Text("Intégrée").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.5))
                    }
                }
                Spacer()

                iconButton(systemName: "arrow.up", enabled: index > 0) {
                    if let s = app.session { Task { await vm.move(index, -1, session: s) } }
                }
                iconButton(systemName: "arrow.down", enabled: index < vm.defs.count - 1) {
                    if let s = app.session { Task { await vm.move(index, +1, session: s) } }
                }

                if !def.builtin {
                    iconButton(systemName: "pencil", enabled: true) { editTarget = def }
                    iconButton(systemName: "trash", enabled: true, tint: palette.error) { confirmDelete = def }
                }

                Toggle("", isOn: enabledBinding(def))
                    .labelsHidden()
                    .tint(palette.primary)
            }
        }
    }

    private func iconButton(systemName: String, enabled: Bool, tint: Color? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(enabled ? (tint ?? palette.primary) : palette.onSurface.opacity(0.25))
                .frame(width: 34, height: 34)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    private func enabledBinding(_ def: MetricDefinition) -> Binding<Bool> {
        Binding(
            get: { def.enabled },
            set: { newValue in
                if let s = app.session {
                    Task { await vm.setEnabled(def, newValue, session: s) }
                }
            }
        )
    }

    private func metricTitle(_ def: MetricDefinition) -> String {
        let label = MetricCatalog.displayLabel(def)
        let (le, re) = MetricCatalog.emojis(def)
        let emojis = [le, re].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        return emojis.isEmpty ? label : "\(label)  \(emojis)"
    }
}

// MARK: - Add/Edit sheet

private struct MetricEditorSheet: View {
    @Environment(\.palette) private var palette

    let initial: MetricDefinition?
    let onSave: (_ label: String, _ left: String?, _ right: String?) -> Void
    let onCancel: () -> Void

    @State private var label: String
    @State private var left: String
    @State private var right: String

    init(
        initial: MetricDefinition?,
        onSave: @escaping (_ label: String, _ left: String?, _ right: String?) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.initial = initial
        self.onSave = onSave
        self.onCancel = onCancel
        _label = State(initialValue: initial?.label ?? "")
        _left = State(initialValue: initial?.emojiLeft ?? "")
        _right = State(initialValue: initial?.emojiRight ?? "")
    }

    private var trimmedLabel: String { label.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form {
                Section("Nom") {
                    TextField("Nom de la jauge", text: $label)
                        .onChange(of: label) { _, newValue in
                            if newValue.count > 40 { label = String(newValue.prefix(40)) }
                        }
                }
                Section("Emojis (facultatif)") {
                    TextField("Gauche", text: $left)
                        .onChange(of: left) { _, newValue in
                            if newValue.count > 4 { left = String(newValue.prefix(4)) }
                        }
                    TextField("Droite", text: $right)
                        .onChange(of: right) { _, newValue in
                            if newValue.count > 4 { right = String(newValue.prefix(4)) }
                        }
                }
            }
            .navigationTitle(initial == nil ? "Nouvelle jauge" : "Modifier la jauge")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { onCancel() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Enregistrer") {
                        onSave(
                            trimmedLabel,
                            left.isEmpty ? nil : left,
                            right.isEmpty ? nil : right
                        )
                    }
                    .disabled(trimmedLabel.isEmpty)
                }
            }
        }
    }
}
