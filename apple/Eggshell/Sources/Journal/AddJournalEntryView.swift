import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — create or edit a journal entry. Five optional gauges
// (mood, dysphoria, euphoria, libido, energy), a free-text note and a
// side-effects field. When editing, the core has no update primitive, so we
// delete the old row and add a fresh one on save.
// ===========================================================================

@MainActor
final class AddJournalEntryViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?

    // Existing entry context (when editing)
    @Published var existingAtMs: Int64?

    // Gauges: each has an enabled toggle + a 0...10 value
    @Published var moodOn = false
    @Published var moodVal: Double = 5
    @Published var dysphoriaOn = false
    @Published var dysphoriaVal: Double = 5
    @Published var euphoriaOn = false
    @Published var euphoriaVal: Double = 5
    @Published var libidoOn = false
    @Published var libidoVal: Double = 5
    @Published var energyOn = false
    @Published var energyVal: Double = 5

    @Published var freeText = ""
    @Published var sideEffects = ""

    func load(_ session: VaultService, entryId: Int64?) async {
        loading = true
        do {
            if let id = entryId, let e = try await session.getJournalEntry(id) {
                existingAtMs = e.atMs
                if let v = e.mood { moodOn = true; moodVal = Double(v) }
                if let v = e.dysphoria { dysphoriaOn = true; dysphoriaVal = Double(v) }
                if let v = e.euphoria { euphoriaOn = true; euphoriaVal = Double(v) }
                if let v = e.libido { libidoOn = true; libidoVal = Double(v) }
                if let v = e.energy { energyOn = true; energyVal = Double(v) }
                freeText = e.freeText ?? ""
                sideEffects = e.sideEffects ?? ""
            }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func save(_ session: VaultService, entryId: Int64?) async -> Bool {
        do {
            let text = freeText.trimmingCharacters(in: .whitespacesAndNewlines)
            let effects = sideEffects.trimmingCharacters(in: .whitespacesAndNewlines)
            let entry = NewJournalEntry(
                atMs: existingAtMs ?? Time.nowMs(),
                mood: moodOn ? UInt32(moodVal) : nil,
                dysphoria: dysphoriaOn ? UInt32(dysphoriaVal) : nil,
                euphoria: euphoriaOn ? UInt32(euphoriaVal) : nil,
                libido: libidoOn ? UInt32(libidoVal) : nil,
                energy: energyOn ? UInt32(energyVal) : nil,
                freeText: text.isEmpty ? nil : text,
                sideEffects: effects.isEmpty ? nil : effects)
            if let id = entryId {
                try await session.deleteJournalEntry(id)
            }
            _ = try await session.addJournalEntry(entry)
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

    private var gaugesCard: some View {
        SectionCard {
            Text("Ressenti").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            gauge(title: "Humeur", isOn: $vm.moodOn, value: $vm.moodVal, low: "😞", high: "😊")
            gauge(title: "Dysphorie", isOn: $vm.dysphoriaOn, value: $vm.dysphoriaVal, low: "😌", high: "😣")
            gauge(title: "Euphorie", isOn: $vm.euphoriaOn, value: $vm.euphoriaVal, low: "😐", high: "😄")
            gauge(title: "Libido", isOn: $vm.libidoOn, value: $vm.libidoVal, low: "💤", high: "🔥")
            gauge(title: "Énergie", isOn: $vm.energyOn, value: $vm.energyVal, low: "🥱", high: "⚡")
        }
    }

    private func gauge(title: String, isOn: Binding<Bool>, value: Binding<Double>, low: String, high: String) -> some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Toggle(isOn: isOn) {
                Text(title).font(.eggCallout).foregroundStyle(palette.onSurface)
            }
            .tint(palette.primary)
            if isOn.wrappedValue {
                HStack(spacing: Spacing.m) {
                    Text(low).font(.title3)
                    Slider(value: value, in: 0...10, step: 1).tint(palette.primary)
                    Text(high).font(.title3)
                    Text("\(Int(value.wrappedValue))")
                        .font(.eggLabel)
                        .foregroundStyle(palette.primary)
                        .frame(minWidth: 22, alignment: .trailing)
                }
            }
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
