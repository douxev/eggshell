import SwiftUI
import TransitionCore

@MainActor
final class DreamEditorViewModel: ObservableObject {
    @Published var nightMs: Int64 = DreamsStore.nightOf(Time.nowMs())
    @Published var title = ""
    @Published var body = ""
    @Published var lucid = false
    @Published var definitions: [MetricDefinition] = []
    @Published var values: [Int64: UInt32] = [:]
    @Published var allTags: [DreamTag] = []
    @Published var selectedTagIds: Set<Int64> = []
    @Published var audio: [DreamAudio] = []
    @Published var recording = false
    @Published var transcribing: Int64?
    @Published var transcribeUnavailable: OnDeviceTranscriber.Reason?
    @Published var loading = true

    private let transcriber = OnDeviceTranscriber()
    /// The dream row id. Audio hangs off it, so an unsaved dream is persisted
    /// the moment recording starts — see `ensureSaved`.
    private(set) var dreamId: Int64 = -1

    let editingId: Int64?
    let presetNightMs: Int64?
    var isEditing: Bool { editingId != nil }

    init(editingId: Int64?, presetNightMs: Int64?) {
        self.editingId = editingId
        self.presetNightMs = presetNightMs
    }

    func load(session: VaultService, store: DreamsStore) async {
        loading = true
        definitions = ((try? await session.listMetricDefinitions(
            domain: "dreams", includeArchived: false)) ?? [])
            .filter { $0.enabled && !$0.archived }
        allTags = await store.tags(session: session)
        transcribeUnavailable = transcriber.availability()

        if let id = editingId {
            dreamId = id
            if let d = await store.get(session: session, id: id) {
                nightMs = d.nightMs
                title = d.title
                body = d.body
                lucid = d.lucid
            }
            for v in (try? await session.listMetricValues(entryDomain: "dreams", entryId: id)) ?? [] {
                values[v.metricId] = v.value
            }
            selectedTagIds = Set(await store.tags(session: session, for: id).map(\.id))
            audio = await store.audio(session: session, for: id)
        } else if let preset = presetNightMs {
            nightMs = preset
        }
        // Mid-scale default, so an untouched slider records "no opinion"
        // rather than zero.
        for def in definitions where values[def.id] == nil {
            values[def.id] = (def.minValue + def.maxValue) / 2
        }
        loading = false
    }

    func toggleTag(_ id: Int64) {
        if selectedTagIds.contains(id) { selectedTagIds.remove(id) } else { selectedTagIds.insert(id) }
    }

    func createTag(session: VaultService, store: DreamsStore, label: String) async {
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        // Get-or-create in the core: typing a tag you already have selects it.
        guard let tag = await store.addTag(session: session, label: trimmed) else { return }
        allTags = await store.tags(session: session)
        selectedTagIds.insert(tag.id)
    }

    /// Persist enough that attachments have a row to hang off. Recording is the
    /// one action that cannot wait for Save.
    private func ensureSaved(session: VaultService, store: DreamsStore) async -> Int64 {
        if dreamId > 0 { return dreamId }
        guard let created = try? await store.add(
            session: session, nightMs: nightMs, title: title, body: body, lucid: lucid)
        else { return -1 }
        dreamId = created.id
        return dreamId
    }

    func startRecording(session: VaultService, store: DreamsStore) async {
        guard await ensureSaved(session: session, store: store) > 0 else { return }
        recording = store.startRecording()
    }

    func stopRecording(session: VaultService, store: DreamsStore, autoTranscribe: Bool) async {
        recording = false
        guard dreamId > 0,
              let (row, plaintext) = await store.stopRecording(session: session, dreamId: dreamId)
        else { return }
        audio = await store.audio(session: session, for: dreamId)
        if autoTranscribe, transcribeUnavailable == nil {
            await transcribe(session: session, store: store, audio: row, url: plaintext)
        } else {
            // Nothing else will read it; the plaintext copy must not linger.
            try? FileManager.default.removeItem(at: plaintext)
        }
    }

    func transcribeExisting(session: VaultService, store: DreamsStore, _ clip: DreamAudio) async {
        transcribing = clip.id
        guard let url = await store.decryptToTemp(session: session, clip) else {
            transcribing = nil
            return
        }
        await transcribe(session: session, store: store, audio: clip, url: url)
    }

    /// The decrypted copy is removed in a `defer`: it exists only for the
    /// length of the call, and a failure part-way is exactly when it would
    /// otherwise be left behind.
    private func transcribe(
        session: VaultService, store: DreamsStore, audio clip: DreamAudio, url: URL
    ) async {
        transcribing = clip.id
        defer {
            try? FileManager.default.removeItem(at: url)
            transcribing = nil
        }
        switch await transcriber.transcribe(url: url) {
        case .text(let t):
            await store.setTranscript(session: session, audioId: clip.id, transcript: t)
            audio = await store.audio(session: session, for: dreamId)
        case .unavailable(let reason):
            transcribeUnavailable = reason
        case .noSpeech, .failed:
            break
        }
    }

    func deleteAudio(session: VaultService, store: DreamsStore, _ clip: DreamAudio) async {
        await store.deleteAudio(session: session, clip)
        audio = await store.audio(session: session, for: dreamId)
    }

    func save(session: VaultService, store: DreamsStore) async -> Bool {
        let id: Int64
        if dreamId > 0 {
            guard let updated = try? await store.update(
                session: session, id: dreamId, nightMs: nightMs,
                title: title, body: body, lucid: lucid)
            else { return false }
            id = updated.id
        } else {
            guard let created = try? await store.add(
                session: session, nightMs: nightMs, title: title, body: body, lucid: lucid)
            else { return false }
            id = created.id
        }
        dreamId = id

        // Replace the tag set wholesale rather than diffing: tag/untag are both
        // idempotent in the core, so this is cheap and cannot drift.
        let existing = Set(await store.tags(session: session, for: id).map(\.id))
        for add in selectedTagIds.subtracting(existing) {
            await store.tag(session: session, dreamId: id, tagId: add)
        }
        for remove in existing.subtracting(selectedTagIds) {
            await store.untag(session: session, dreamId: id, tagId: remove)
        }

        let payload = definitions.compactMap { def in
            values[def.id].map { MetricValue(metricId: def.id, value: $0) }
        }
        try? await session.replaceMetricValues(entryDomain: "dreams", entryId: id, values: payload)
        return true
    }

    func delete(session: VaultService, store: DreamsStore) async {
        guard dreamId > 0 else { return }
        await store.delete(session: session, id: dreamId)
    }
}

struct DreamEditorView: View {
    let editingId: Int64?
    let presetNightMs: Int64?

    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var dreamsStore: DreamsStore
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm: DreamEditorViewModel

    @State private var newTag = ""
    @State private var showNightPicker = false
    @State private var confirmDelete = false
    @State private var autoTranscribe = true

    init(editingId: Int64?, presetNightMs: Int64?) {
        self.editingId = editingId
        self.presetNightMs = presetNightMs
        _vm = StateObject(
            wrappedValue: DreamEditorViewModel(
                editingId: editingId, presetNightMs: presetNightMs))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                nightCard
                fields
                lucidCard
                voiceSection
                if !vm.definitions.isEmpty {
                    SectionTitleView("Sommeil", prominent: true)
                    MetricSliderColumn(definitions: vm.definitions, values: $vm.values)
                }
                tagSection
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle(vm.isEditing ? "Modifier le rêve" : "Nouveau rêve")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Enregistrer") {
                    guard let session = app.session else { return }
                    Task {
                        if await vm.save(session: session, store: dreamsStore) { dismiss() }
                    }
                }
            }
            if vm.isEditing {
                ToolbarItem(placement: .destructiveAction) {
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Image(systemName: "trash")
                    }
                }
            }
        }
        .task {
            guard let session = app.session else { return }
            await vm.load(session: session, store: dreamsStore)
        }
        .onDisappear {
            // Leaving has to silence it: a dream reading itself aloud from a
            // screen its owner has already left is the opposite of the point.
            dreamsStore.stopPlayback()
        }
        .sheet(isPresented: $showNightPicker) { nightPicker }
        .alert("Supprimer ce rêve ?", isPresented: $confirmDelete) {
            Button("Annuler", role: .cancel) {}
            Button("Supprimer", role: .destructive) {
                guard let session = app.session else { return }
                Task {
                    await vm.delete(session: session, store: dreamsStore)
                    dismiss()
                }
            }
        } message: {
            Text("Le rêve, ses tags et ses notes vocales seront définitivement supprimés.")
        }
    }

    /// The night, first and prominent: it is what the entry is about, and it is
    /// not the day the user is typing on.
    private var nightCard: some View {
        Button { showNightPicker = true } label: {
            EggCard(variant: .low) {
                MicroLabel("NUIT DU")
                Text(nightLabel)
                    .font(EggFont.titleS)
                    .foregroundStyle(palette.onSurface)
                    .padding(.top, 2)
                MicroLabel("La nuit du rêve, pas le jour où tu l’écris. Un réveil à 3 h compte pour la nuit qui commence la veille.")
                    .padding(.top, 4)
            }
        }
        .buttonStyle(.plain)
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: Metrics.blockGap) {
            EggCard(variant: .low) {
                TextField("Titre (facultatif)", text: $vm.title)
                    .font(.eggBody)
            }
            EggCard(variant: .low) {
                TextField("Le rêve", text: $vm.body, axis: .vertical)
                    .font(.eggBody)
                    .lineLimit(5...)
            }
        }
    }

    private var lucidCard: some View {
        EggCard(variant: .low) {
            Toggle(isOn: $vm.lucid) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Rêve lucide").font(.eggBody)
                    MicroLabel("Tu savais que tu rêvais.")
                }
            }
            .tint(palette.primary)
        }
    }

    private var voiceSection: some View {
        VStack(alignment: .leading, spacing: Metrics.blockGap) {
            SectionTitleView("Notes vocales", prominent: true)
            MicroLabel("Plus rapide que d’écrire au réveil. L’audio est chiffré comme le reste du coffre.")

            ForEach(vm.audio, id: \.id) { clip in
                DreamAudioRow(
                    clip: clip,
                    playing: dreamsStore.playingAudioId == clip.id,
                    transcribing: vm.transcribing == clip.id,
                    canTranscribe: vm.transcribeUnavailable == nil,
                    onPlay: {
                        guard let session = app.session else { return }
                        Task { await dreamsStore.togglePlayback(session: session, clip) }
                    },
                    onTranscribe: {
                        guard let session = app.session else { return }
                        Task {
                            await vm.transcribeExisting(
                                session: session, store: dreamsStore, clip)
                        }
                    },
                    onDelete: {
                        guard let session = app.session else { return }
                        Task {
                            await vm.deleteAudio(session: session, store: dreamsStore, clip)
                        }
                    })
            }

            if let reason = vm.transcribeUnavailable {
                EggCard(variant: .outlined) {
                    Text(OnDeviceTranscriber.message(for: reason))
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
            } else {
                Toggle(isOn: $autoTranscribe) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Transcrire automatiquement").font(.eggBody)
                        MicroLabel("La transcription se fait sur ton téléphone. Rien n’est envoyé à un serveur.")
                    }
                }
                .tint(palette.primary)
            }

            Button {
                guard let session = app.session else { return }
                Task {
                    if vm.recording {
                        await vm.stopRecording(
                            session: session, store: dreamsStore, autoTranscribe: autoTranscribe)
                    } else {
                        await vm.startRecording(session: session, store: dreamsStore)
                    }
                }
            } label: {
                Label(
                    vm.recording ? "Arrêter" : "Enregistrer",
                    systemImage: vm.recording ? "stop.fill" : "mic.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(palette.primary)
        }
    }

    private var tagSection: some View {
        VStack(alignment: .leading, spacing: Metrics.blockGap) {
            SectionTitleView("Tags", prominent: true)
            MicroLabel("Pour regrouper les rêves qui se ressemblent et repérer ce qui revient.")
            ChipFlowLayout(spacing: 7) {
                ForEach(vm.allTags, id: \.id) { tag in
                    Button { vm.toggleTag(tag.id) } label: {
                        Text(tag.label)
                            .font(EggFont.label)
                            .foregroundStyle(
                                vm.selectedTagIds.contains(tag.id)
                                    ? palette.onSecondaryContainer : palette.onSurfaceVariant)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(
                                vm.selectedTagIds.contains(tag.id)
                                    ? palette.secondaryContainer : palette.surfaceContainerHighest,
                                in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            HStack(spacing: Spacing.s) {
                TextField("Nouveau tag", text: $newTag).font(.eggBody)
                Button("Ajouter") {
                    guard let session = app.session else { return }
                    let label = newTag
                    newTag = ""
                    Task {
                        await vm.createTag(session: session, store: dreamsStore, label: label)
                    }
                }
                .disabled(newTag.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private var nightPicker: some View {
        NavigationStack {
            DatePicker(
                "Nuit du",
                selection: Binding(
                    get: { Date(timeIntervalSince1970: Double(vm.nightMs) / 1000) },
                    set: { vm.nightMs = DreamsStore.nightOf(date: $0) }),
                in: ...Date(),
                displayedComponents: [.date])
                .datePickerStyle(.graphical)
                .padding()
                .navigationTitle("Nuit du")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("OK") { showNightPicker = false }
                    }
                }
        }
    }

    private var nightLabel: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr")
        f.dateFormat = "EEEE d MMMM yyyy"
        return f.string(from: Date(timeIntervalSince1970: Double(vm.nightMs) / 1000)).capitalized
    }
}

private struct DreamAudioRow: View {
    @Environment(\.palette) private var palette

    let clip: DreamAudio
    let playing: Bool
    let transcribing: Bool
    let canTranscribe: Bool
    let onPlay: () -> Void
    let onTranscribe: () -> Void
    let onDelete: () -> Void

    var body: some View {
        EggCard(variant: .low, paddingH: 18, paddingV: 12, spacing: 0) {
            HStack(spacing: Spacing.s) {
                Button(action: onPlay) {
                    Image(systemName: playing ? "stop.fill" : "play.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(palette.primary)
                        .frame(width: Metrics.touchTarget, height: Metrics.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(playing ? "Arrêter la lecture" : "Écouter cette note vocale")

                Text(duration)
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurface)
                Spacer(minLength: Spacing.s)

                if clip.transcript == nil && canTranscribe {
                    Button(action: onTranscribe) {
                        Label(
                            transcribing ? "Transcription…" : "Transcrire",
                            systemImage: "text.bubble")
                            .font(EggFont.label)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(palette.primary)
                    .disabled(transcribing)
                }

                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "trash")
                        .foregroundStyle(palette.error)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Supprimer cette note vocale")
            }

            if let transcript = clip.transcript, !transcript.isEmpty {
                Text(transcript)
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .padding(.top, 8)
            }
        }
    }

    private var duration: String {
        let total = Int(clip.durationMs / 1000)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
