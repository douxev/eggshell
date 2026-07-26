import SwiftUI
import TransitionCore

// « Personnaliser les indicateurs » — the catalogue editor of one domain
// (« journal » or « bleeding »). Reached from the Journal complet link and from
// the Menstruations segment (D5).
//
// A built-in can be hidden and reordered but never renamed or removed: the five
// reserved accents of §6.2 have to keep meaning the same thing in the calendar,
// the history bars and the report. Anything you made yourself can be renamed,
// re-emoji'd and archived — and archiving only stops drawing it. The values
// already recorded are never touched, which is the whole reason hiding exists.

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
        error = nil
        do {
            defs = try await session.listMetricDefinitions(domain: domain, includeArchived: false)
                .sorted { $0.sortOrder < $1.sortOrder }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    /// Adds an indicator of your own, with a unique key and the next free rank.
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
                    createdAtMs: Time.nowMs()))
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    func rename(
        _ def: MetricDefinition, label: String, left: String?, right: String?,
        session: VaultService
    ) async {
        await apply(
            def, label: label, left: left, right: right,
            sortOrder: def.sortOrder, enabled: def.enabled, session: session)
    }

    func setEnabled(_ def: MetricDefinition, _ enabled: Bool, session: VaultService) async {
        await apply(
            def, label: def.label, left: def.emojiLeft, right: def.emojiRight,
            sortOrder: def.sortOrder, enabled: enabled, session: session)
    }

    /// Moves the definition at `index` by `delta` (−1 up, +1 down) by swapping
    /// its rank with its neighbour's, then persisting both rows.
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

    private func apply(
        _ def: MetricDefinition, label: String, left: String?, right: String?,
        sortOrder: Int64, enabled: Bool, session: VaultService
    ) async {
        do {
            try await session.updateMetricDefinition(
                def.id,
                MetricDefinitionUpdate(
                    label: label, emojiLeft: left, emojiRight: right,
                    sortOrder: sortOrder, enabled: enabled))
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
            enabled: def.enabled)
    }

    private static func makeKey() -> String {
        "custom_\(Time.nowMs())_\(UUID().uuidString.prefix(8))"
    }
}

// MARK: - Écran

struct MetricEditorView: View {
    let domain: String

    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @StateObject private var vm: MetricEditorViewModel

    @State private var adding = false
    @State private var editTarget: MetricDefinition?
    @State private var confirmDelete: MetricDefinition?

    init(domain: String) {
        self.domain = domain
        _vm = StateObject(wrappedValue: MetricEditorViewModel(domain: domain))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                Text(intro)
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                if let message = vm.error {
                    ErrorCardView(message, retryLabel: "Réessayer") { reload() }
                }

                if vm.loading {
                    SkeletonBlock(height: 64, cornerRadius: Radius.listGroup)
                    SkeletonBlock(height: 64, cornerRadius: Radius.listGroup)
                } else if vm.defs.isEmpty {
                    EmptyStateView(
                        "Aucun indicateur pour l'instant. Crée le premier — un nom, deux emojis, "
                            + "et il apparaîtra dans ta saisie.",
                        systemImage: "slider.horizontal.3",
                        actionLabel: "Ajouter un indicateur",
                        action: { adding = true })
                } else {
                    ListGroup {
                        ForEach(Array(vm.defs.enumerated()), id: \.element.id) { pair in
                            metricRow(
                                pair.element,
                                index: pair.offset,
                                showsSeparator: pair.offset < vm.defs.count - 1)
                        }
                    }
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
            .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Tes indicateurs")
        .navigationBarTitleDisplayMode(.inline)
        .eggActionBar {
            ActionBarButton("Ajouter un indicateur", systemImage: "plus") { adding = true }
        }
        .task { reload() }
        .sheet(isPresented: $adding) {
            MetricEditorSheet(initial: nil) { label, left, right in
                adding = false
                guard let session = app.session else { return }
                Task { await vm.add(label: label, left: left, right: right, session: session) }
            } onCancel: {
                adding = false
            }
        }
        .sheet(isPresented: editBinding) {
            if let target = editTarget {
                MetricEditorSheet(initial: target) { label, left, right in
                    editTarget = nil
                    guard let session = app.session else { return }
                    Task {
                        await vm.rename(
                            target, label: label, left: left, right: right, session: session)
                    }
                } onCancel: {
                    editTarget = nil
                }
            }
        }
        .alert(
            "Retirer cet indicateur ?", isPresented: deleteBinding, presenting: confirmDelete
        ) { target in
            Button("Retirer", role: .destructive) {
                if let session = app.session {
                    Task { await vm.archive(target, session: session) }
                }
                confirmDelete = nil
            }
            Button("Annuler", role: .cancel) { confirmDelete = nil }
        } message: { target in
            Text("« \(MetricCatalog.displayLabel(target)) » ne sera plus proposé. "
                    + "Tout ce que tu avais déjà noté reste intact.")
        }
    }

    private var intro: String {
        let subject = domain == "bleeding" ? "tes menstruations" : "ton ressenti"
        return "Choisis ce que tu veux suivre pour \(subject) : masque ce qui ne te parle pas, "
            + "réordonne, ajoute les tiens. Masquer un indicateur ne supprime jamais "
            + "les valeurs déjà notées."
    }

    private var deleteBinding: Binding<Bool> {
        Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })
    }

    private var editBinding: Binding<Bool> {
        Binding(get: { editTarget != nil }, set: { if !$0 { editTarget = nil } })
    }

    private func reload() {
        guard let session = app.session else { return }
        Task { await vm.load(session) }
    }

    // MARK: - Ligne

    private func metricRow(
        _ def: MetricDefinition, index: Int, showsSeparator: Bool
    ) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: Spacing.m) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title(def))
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                    Text(def.builtin ? "Indicateur d'origine" : "Le tien")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                Spacer(minLength: Spacing.s)

                // One menu instead of four cramped icon buttons: every action
                // keeps a full-size target and a spoken name (§10).
                Menu {
                    Button {
                        if let session = app.session {
                            Task { await vm.move(index, -1, session: session) }
                        }
                    } label: {
                        Label("Monter", systemImage: "arrow.up")
                    }
                    .disabled(index == 0)

                    Button {
                        if let session = app.session {
                            Task { await vm.move(index, +1, session: session) }
                        }
                    } label: {
                        Label("Descendre", systemImage: "arrow.down")
                    }
                    .disabled(index >= vm.defs.count - 1)

                    if !def.builtin {
                        Button { editTarget = def } label: {
                            Label("Renommer", systemImage: "pencil")
                        }
                        Button(role: .destructive) { confirmDelete = def } label: {
                            Label("Retirer", systemImage: "trash")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(palette.onSurfaceVariant)
                        .frame(width: Metrics.touchTarget, height: Metrics.touchTarget)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Options de \(MetricCatalog.displayLabel(def))")

                Toggle("", isOn: enabledBinding(def))
                    .labelsHidden()
                    .tint(palette.primary)
                    .accessibilityLabel("Afficher \(MetricCatalog.displayLabel(def))")
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.vertical, Spacing.s)
            .frame(minHeight: 56)

            if showsSeparator {
                Rectangle()
                    .fill(palette.outlineVariant)
                    .frame(height: 1)
                    // Inset grouped lists start their hairline under the text
                    // column, not at the screen margin (README §4).
                    .padding(.leading, ListRowView.separatorInset)
            }
        }
    }

    private func enabledBinding(_ def: MetricDefinition) -> Binding<Bool> {
        Binding(
            get: { def.enabled },
            set: { value in
                guard let session = app.session else { return }
                Task { await vm.setEnabled(def, value, session: session) }
            })
    }

    private func title(_ def: MetricDefinition) -> String {
        let label = MetricCatalog.displayLabel(def)
        let emojis = MetricCatalog.emojis(def)
        let pair = [emojis.0, emojis.1]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return pair.isEmpty ? label : "\(label)  \(pair)"
    }
}

// MARK: - Feuille d'ajout / renommage

private struct MetricEditorSheet: View {
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
                    TextField("Sommeil, appétit, confiance…", text: $label)
                        .onChange(of: label) { _, value in
                            if value.count > 40 { label = String(value.prefix(40)) }
                        }
                }
                Section {
                    TextField("Bas de l'échelle", text: $left)
                        .onChange(of: left) { _, value in
                            if value.count > 4 { left = String(value.prefix(4)) }
                        }
                    TextField("Haut de l'échelle", text: $right)
                        .onChange(of: right) { _, value in
                            if value.count > 4 { right = String(value.prefix(4)) }
                        }
                } header: {
                    Text("Emojis (facultatif)")
                } footer: {
                    Text("Deux emojis aident à retrouver le sens du curseur d'un coup d'œil.")
                }
            }
            .navigationTitle(initial == nil ? "Nouvel indicateur" : "Renommer")
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
                            right.isEmpty ? nil : right)
                    }
                    .disabled(trimmedLabel.isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
