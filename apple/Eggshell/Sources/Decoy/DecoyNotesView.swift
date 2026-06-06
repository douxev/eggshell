import SwiftUI

// Working fake "Notes" app shown when the decoy PIN is entered. NO vault access.
// Notes are PERSISTED in a plain UserDefaults suite so the decoy behaves like a
// real notes app across sessions (edits/additions/deletions survive a cold
// start). The content is decoy-only cover text — it never touches the real
// encrypted vault. Seeded once on first launch so it looks lived-in.
// Mirrors android/.../ui/unlock/DecoyScreen.kt.
struct DecoyNotesView: View {
    struct Note: Identifiable, Codable, Equatable {
        var id = UUID()
        var title: String
        var body: String
        var tintHex: UInt32
    }

    @State private var notes: [Note] = DecoyNotesStore.loadOrSeed()
    @State private var search = ""
    @State private var editing: Note?

    private static let teal = Color(hex: 0x006A6A)
    private static let surface = Color(hex: 0xFAFDFC)

    private var filtered: [Note] {
        guard !search.isEmpty else { return notes }
        return notes.filter { $0.title.localizedCaseInsensitiveContains(search) || $0.body.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    ForEach(filtered) { note in
                        Button { editing = note } label: { card(note) }.buttonStyle(.plain)
                    }
                }
                .padding()
            }
            .background(Self.surface.ignoresSafeArea())
            .navigationTitle("Notes")
            .searchable(text: $search)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        notes.insert(Note(title: "Nouvelle note", body: "", tintHex: Self.tintHexes.randomElement()!), at: 0)
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .tint(Self.teal)
            .sheet(item: $editing) { note in editor(note) }
        }
        // Persist on every change so the decoy stays believable across launches.
        .onChange(of: notes) { _, new in DecoyNotesStore.save(new) }
    }

    private func card(_ note: Note) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(note.title).font(.headline).lineLimit(1)
            Text(note.body).font(.caption).foregroundStyle(.secondary).lineLimit(5)
        }
        .frame(maxWidth: .infinity, minHeight: 90, alignment: .topLeading)
        .padding(12)
        .background(Color(hex: note.tintHex), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func editor(_ note: Note) -> some View {
        NavigationStack {
            VStack {
                if let idx = notes.firstIndex(where: { $0.id == note.id }) {
                    TextField("Titre", text: Binding(get: { notes[idx].title }, set: { notes[idx].title = $0 }))
                        .font(.title2.bold()).padding(.horizontal)
                    TextEditor(text: Binding(get: { notes[idx].body }, set: { notes[idx].body = $0 }))
                        .padding(.horizontal)
                }
            }
            .navigationTitle("").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("OK") { editing = nil } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive) {
                        notes.removeAll { $0.id == note.id }; editing = nil
                    } label: { Image(systemName: "trash") }
                }
            }
            .tint(Self.teal)
        }
    }

    static let tintHexes: [UInt32] = [0xFFF3C4, 0xD7F0E6, 0xFAD9D5, 0xE3E0F4, 0xDDEBF7, 0xF7E6D5]
}

// Plaintext decoy-notes store (cover content only; never the real vault).
enum DecoyNotesStore {
    private static let d = UserDefaults(suiteName: "com.douxev.eggshell.decoynotes") ?? .standard
    private static let key = "notes"

    static func loadOrSeed() -> [DecoyNotesView.Note] {
        if let data = d.data(forKey: key),
           let notes = try? JSONDecoder().decode([DecoyNotesView.Note].self, from: data) {
            return notes
        }
        let seed = self.seed()
        save(seed)
        return seed
    }

    static func save(_ notes: [DecoyNotesView.Note]) {
        if let data = try? JSONEncoder().encode(notes) { d.set(data, forKey: key) }
    }

    static func clear() { d.removeObject(forKey: key) }

    private static func seed() -> [DecoyNotesView.Note] {
        let t = DecoyNotesView.tintHexes
        return [
            .init(title: "Courses", body: "Lait\nœufs\npain\ncafé\ntomates", tintHex: t[0]),
            .init(title: "À faire ce week-end", body: "Lessive\nappeler maman\nranger le bureau", tintHex: t[1]),
            .init(title: "Idées vacances", body: "Lisbonne ? Rome au printemps. Vérifier les vols.", tintHex: t[2]),
            .init(title: "Recette tarte tatin", body: "Pommes, beurre, sucre, pâte. 25 min à 180°C.", tintHex: t[3]),
            .init(title: "Films à voir", body: "Portrait de la jeune fille en feu\nPast Lives", tintHex: t[4]),
        ]
    }
}
