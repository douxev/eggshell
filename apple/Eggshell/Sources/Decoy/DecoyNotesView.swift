import SwiftUI

// Pure-UI fake "Notes" app shown when the decoy PIN is entered. NO vault access,
// no persistence — resets on cold start. Mirrors android/.../ui/unlock/DecoyScreen.kt.
// A coercive observer sees an ordinary, lived-in notes app.
struct DecoyNotesView: View {
    private struct Note: Identifiable { let id = UUID(); var title: String; var body: String; var tint: Color }

    @State private var notes: [Note] = DecoyNotesView.seed()
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
                    Button { notes.insert(Note(title: "Nouvelle note", body: "", tint: Self.tints.randomElement()!), at: 0) } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .tint(Self.teal)
            .sheet(item: $editing) { note in editor(note) }
        }
    }

    private func card(_ note: Note) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(note.title).font(.headline).lineLimit(1)
            Text(note.body).font(.caption).foregroundStyle(.secondary).lineLimit(5)
        }
        .frame(maxWidth: .infinity, minHeight: 90, alignment: .topLeading)
        .padding(12)
        .background(note.tint, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
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

    private static let tints = [Color(hex: 0xFFF3C4), Color(hex: 0xD7F0E6), Color(hex: 0xFAD9D5),
                                Color(hex: 0xE3E0F4), Color(hex: 0xDDEBF7), Color(hex: 0xF7E6D5)]

    private static func seed() -> [Note] {
        [
            Note(title: "Courses", body: "Lait\nœufs\npain\ncafé\ntomates", tint: tints[0]),
            Note(title: "À faire ce week-end", body: "Lessive\nappeler maman\nranger le bureau", tint: tints[1]),
            Note(title: "Idées vacances", body: "Lisbonne ? Rome au printemps. Vérifier les vols.", tint: tints[2]),
            Note(title: "Recette tarte tatin", body: "Pommes, beurre, sucre, pâte. 25 min à 180°C.", tint: tints[3]),
            Note(title: "Films à voir", body: "Portrait de la jeune fille en feu\nPpast Lives", tint: tints[4]),
        ]
    }
}
