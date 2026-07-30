import SwiftUI
import TransitionCore
import UIKit

/// The notes module — folders and notes of one level, with the root at nil.
///
/// Nesting is handled by pushing another `NotesView` rather than by holding a
/// tree in memory: each level lists exactly what the core returns for its
/// parent, so a deep hierarchy costs no more than a shallow one.
struct NotesView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette

    /// nil at the root. A pushed level carries the folder it is showing.
    var folderId: Int64?
    var folderName: String?

    @StateObject private var store: NotesStoreHolder = NotesStoreHolder()

    @State private var selection: Set<Int64> = []
    @State private var selecting = false
    @State private var newFolderName = ""
    @State private var showNewFolder = false
    @State private var renaming: NoteFolder?
    @State private var renameText = ""
    @State private var confirmFolderDelete: NoteFolder?
    @State private var folderDeleteCount: Int64 = 0
    @State private var exportURL: ShareableFile?
    @State private var exporting = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                if let error = store.inner?.error {
                    ErrorCardView(error)
                }
                if folders.isEmpty && notes.isEmpty {
                    EmptyStateCard(
                        text: "Rien ici pour l'instant. Crée une note, ou un dossier pour les ranger.",
                        systemImage: "doc.text")
                }
                if !folders.isEmpty {
                    MicroLabel("DOSSIERS")
                    ListGroup {
                        ForEach(Array(folders.enumerated()), id: \.element.id) { index, folder in
                            folderRow(folder)
                            if index != folders.count - 1 { separator }
                        }
                    }
                }
                if !notes.isEmpty {
                    MicroLabel("NOTES")
                    ListGroup {
                        ForEach(Array(notes.enumerated()), id: \.element.id) { index, note in
                            noteRow(note)
                            if index != notes.count - 1 { separator }
                        }
                    }
                }
                Color.clear.frame(height: Spacing.xxl)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle(folderName ?? "Notes")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
        .task {
            if store.inner == nil, let session = app.session {
                store.inner = NotesStore(session: session)
                await store.inner?.cleanupOrphans()
            }
            await store.inner?.load(folderId: folderId)
        }
        .alert("Nouveau dossier", isPresented: $showNewFolder) {
            TextField("Nom", text: $newFolderName)
            Button("Annuler", role: .cancel) { newFolderName = "" }
            Button("Créer") {
                let name = newFolderName.trimmingCharacters(in: .whitespaces)
                newFolderName = ""
                guard !name.isEmpty else { return }
                Task { await store.inner?.createFolder(named: name, parentId: folderId) }
            }
        }
        .alert("Renommer", isPresented: Binding(
            get: { renaming != nil },
            set: { if !$0 { renaming = nil } })
        ) {
            TextField("Nom", text: $renameText)
            Button("Annuler", role: .cancel) { renaming = nil }
            Button("Renommer") {
                let name = renameText.trimmingCharacters(in: .whitespaces)
                guard let folder = renaming, !name.isEmpty else { renaming = nil; return }
                renaming = nil
                Task { await store.inner?.renameFolder(folder.id, to: name) }
            }
        }
        .confirmationDialog(
            folderDeleteCount == 0
                ? "Supprimer ce dossier ?"
                : "Supprimer ce dossier et les \(folderDeleteCount) notes qu'il contient ?",
            isPresented: Binding(
                get: { confirmFolderDelete != nil },
                set: { if !$0 { confirmFolderDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Supprimer", role: .destructive) {
                guard let folder = confirmFolderDelete else { return }
                confirmFolderDelete = nil
                Task { await store.inner?.deleteFolder(folder.id) }
            }
            Button("Annuler", role: .cancel) { confirmFolderDelete = nil }
        } message: {
            Text("Cette action est définitive.")
        }
        .sheet(item: $exportURL) { file in
            NoteShareSheet(url: file.url)
        }
    }

    // MARK: Rows

    private var folders: [NoteFolder] { store.inner?.folders ?? [] }
    private var notes: [Note] { store.inner?.notes ?? [] }

    private var separator: some View {
        Rectangle()
            .fill(palette.outlineVariant)
            .frame(height: 1)
            .padding(.leading, ListRowView.separatorInset)
    }

    private func folderRow(_ folder: NoteFolder) -> some View {
        ListRowView(
            title: folder.name,
            systemImage: "folder.fill",
            iconContainer: palette.otherContainer,
            iconTint: palette.onOtherContainer,
            showsChevron: true,
            action: { router.push(.notesFolder(id: folder.id, name: folder.name)) }
        )
        .contextMenu {
            Button("Renommer") { renameText = folder.name; renaming = folder }
            Button("Supprimer", role: .destructive) {
                Task {
                    folderDeleteCount = await store.inner?.folderContentsCount(folder.id) ?? 0
                    confirmFolderDelete = folder
                }
            }
        }
    }

    private func noteRow(_ note: Note) -> some View {
        ListRowView(
            title: note.title.isEmpty ? "Sans titre" : note.title,
            subtitle: noteSnippet(note.body, limit: 90),
            systemImage: selecting
                ? (selection.contains(note.id) ? "checkmark.circle.fill" : "circle")
                : "doc.text",
            iconContainer: palette.otherContainer,
            iconTint: selecting && selection.contains(note.id)
                ? palette.primary : palette.onOtherContainer,
            showsChevron: !selecting,
            action: {
                if selecting {
                    if selection.contains(note.id) { selection.remove(note.id) }
                    else { selection.insert(note.id) }
                } else {
                    router.push(.noteEditor(id: note.id))
                }
            }
        )
        .contextMenu {
            Button("Exporter") { export(notes: [note], name: note.title) }
            if folderId != nil {
                Button("Sortir du dossier") {
                    Task { await store.inner?.move(note.id, toFolder: nil) }
                }
            }
            Button("Supprimer", role: .destructive) {
                Task { await store.inner?.delete(note.id) }
            }
        }
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button {
                    Task {
                        guard let note = await store.inner?.create(
                            title: "", body: "", folderId: folderId) else { return }
                        router.push(.noteEditor(id: note.id))
                    }
                } label: { Label("Nouvelle note", systemImage: "square.and.pencil") }

                Button { showNewFolder = true } label: {
                    Label("Nouveau dossier", systemImage: "folder.badge.plus")
                }

                Divider()

                Button {
                    selecting.toggle()
                    selection.removeAll()
                } label: {
                    Label(selecting ? "Terminer" : "Sélectionner",
                          systemImage: "checkmark.circle")
                }

                if !notes.isEmpty {
                    Button {
                        export(notes: notes, name: folderName ?? "notes")
                    } label: { Label("Tout exporter", systemImage: "square.and.arrow.up") }
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .disabled(exporting)
        }

        if selecting && !selection.isEmpty {
            ToolbarItem(placement: .bottomBar) {
                HStack {
                    Button {
                        export(notes: notes.filter { selection.contains($0.id) },
                               name: "notes-selection")
                    } label: { Label("Exporter", systemImage: "square.and.arrow.up") }
                    Spacer()
                    Button(role: .destructive) {
                        let doomed = selection
                        selection.removeAll()
                        Task { await store.inner?.delete(ids: doomed) }
                    } label: { Label("Supprimer", systemImage: "trash") }
                }
            }
        }
    }

    private func export(notes: [Note], name: String) {
        guard let session = app.session, !notes.isEmpty else { return }
        exporting = true
        Task {
            defer { exporting = false }
            let safe = name.trimmingCharacters(in: .whitespaces)
            let archive = safe.isEmpty ? "notes" : safe
            if let url = try? await NoteExporter.zip(
                notes: notes, session: session, archiveName: archive) {
                exportURL = ShareableFile(url: url)
            }
        }
    }
}

/// `NotesStore` is `@MainActor` and needs a session that only exists after
/// unlock, so it cannot be built in a `@StateObject` initialiser. This holder
/// owns it and is what the view actually observes.
@MainActor
final class NotesStoreHolder: ObservableObject {
    @Published var inner: NotesStore? {
        didSet { bind() }
    }
    private var task: Task<Void, Never>?

    /// Re-publish the store's changes as our own: nesting an ObservableObject
    /// inside another does not forward `objectWillChange`.
    private func bind() {
        task?.cancel()
        guard let inner else { return }
        task = Task { [weak self] in
            for await _ in inner.objectWillChange.values {
                self?.objectWillChange.send()
            }
        }
    }

    deinit { task?.cancel() }
}

/// A file handed to the share sheet. Identifiable so `.sheet(item:)` can drive
/// it — `.sheet(isPresented:)` plus a separate URL races on first present.
struct ShareableFile: Identifiable {
    let url: URL
    var id: String { url.path }
}

struct NoteShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
