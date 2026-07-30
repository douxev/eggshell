import PhotosUI
import SwiftUI
import TransitionCore

/// One note, open.
///
/// A filled note opens **rendered**, not as source — a wall of markdown is not
/// what someone came back to read. Tapping the body switches to editing, which
/// is also where a brand-new note starts, since there is nothing to render yet.
struct NoteEditorView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    let noteId: Int64

    @StateObject private var holder = NotesStoreHolder()
    @State private var title = ""
    @State private var body_ = ""
    @State private var editing = false
    @State private var loaded = false
    @State private var pickerItem: PhotosPickerItem?
    @State private var exportURL: ShareableFile?
    @State private var confirmDelete = false
    @State private var saveTask: Task<Void, Never>?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                TextField("Titre", text: $title)
                    .font(EggFont.titleL)
                    .foregroundStyle(palette.onSurface)
                    .textInputAutocapitalization(.sentences)
                    .onChange(of: title) { _, _ in scheduleSave() }

                if editing {
                    editor
                } else {
                    rendered
                }
                Color.clear.frame(height: Spacing.xxl)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
        .task { await load() }
        .onDisappear {
            saveTask?.cancel()
            // The debounce may still be pending; commit synchronously-ish so
            // leaving the screen never loses the last keystrokes.
            Task { await holder.inner?.update(noteId, title: title, body: body_) }
        }
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task { await attach(item) }
        }
        .sheet(item: $exportURL) { file in NoteShareSheet(url: file.url) }
        .confirmationDialog("Supprimer cette note ?", isPresented: $confirmDelete,
                            titleVisibility: .visible) {
            Button("Supprimer", role: .destructive) {
                Task {
                    saveTask?.cancel()
                    await holder.inner?.delete(noteId)
                    dismiss()
                }
            }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("Cette action est définitive.")
        }
    }

    // MARK: Modes

    private var editor: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            TextEditor(text: $body_)
                .font(.eggBody)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 320)
                .padding(Spacing.s)
                .background(
                    RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                        .fill(palette.surfaceContainerLow))
                .onChange(of: body_) { _, _ in scheduleSave() }

            Text("Markdown : # titre, - liste, **gras**, > citation.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
        }
    }

    private var rendered: some View {
        NoteBodyView(body_) { imageId in
            if let image = holder.inner?.images[imageId] {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                    .contextMenu {
                        Button("Retirer l'image", role: .destructive) {
                            Task { await remove(imageId: imageId) }
                        }
                    }
            } else {
                // A reference whose file is gone degrades to a gap, never to
                // broken markup — the whole reason images are not inlined.
                RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                    .fill(palette.surfaceContainerLow)
                    .frame(height: 120)
                    .overlay {
                        Image(systemName: "photo")
                            .foregroundStyle(palette.onSurfaceVariant.opacity(0.6))
                    }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture { editing = true }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            PhotosPicker(selection: $pickerItem, matching: .images) {
                Image(systemName: "photo.badge.plus")
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button {
                    editing.toggle()
                } label: {
                    Label(editing ? "Aperçu" : "Modifier",
                          systemImage: editing ? "eye" : "pencil")
                }
                Button { export() } label: {
                    Label("Exporter", systemImage: "square.and.arrow.up")
                }
                Divider()
                Button(role: .destructive) { confirmDelete = true } label: {
                    Label("Supprimer", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
        }
    }

    // MARK: Actions

    private func load() async {
        guard !loaded, let session = app.session else { return }
        loaded = true
        let store = NotesStore(session: session)
        holder.inner = store
        guard let note = try? await session.getNote(noteId) else { return }
        title = note?.title ?? ""
        body_ = note?.body ?? ""
        // A note with nothing in it has nothing to render: start in the editor
        // rather than on an empty page with no visible way forward.
        editing = (note?.body ?? "").isEmpty
        await store.loadImages(noteId: noteId)
    }

    /// Debounced autosave. Typing should not mean one SQLCipher write per key.
    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task {
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard !Task.isCancelled else { return }
            await holder.inner?.update(noteId, title: title, body: body_)
        }
    }

    private func attach(_ item: PhotosPickerItem) async {
        defer { pickerItem = nil }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data),
              let store = holder.inner,
              let imageId = await store.attach(image, to: noteId) else { return }
        // The marker goes on its own line, with a blank line before it, so the
        // block parser sees an image and not a paragraph that happens to
        // contain one.
        let separator = body_.isEmpty ? "" : "\n\n"
        body_ += "\(separator)\(NoteImageRef.marker(for: imageId))\n"
        await store.update(noteId, title: title, body: body_)
    }

    private func remove(imageId: Int64) async {
        await holder.inner?.detach(imageId: imageId, noteId: noteId)
        body_ = body_.components(separatedBy: .newlines)
            .filter { NoteImageRef.imageId(inMarkerLine: $0) != imageId }
            .joined(separator: "\n")
        await holder.inner?.update(noteId, title: title, body: body_)
    }

    private func export() {
        guard let session = app.session else { return }
        Task {
            guard let note = try? await session.getNote(noteId), let note else { return }
            let name = note.title.trimmingCharacters(in: .whitespaces)
            if let url = try? await NoteExporter.zip(
                notes: [note], session: session,
                archiveName: name.isEmpty ? "note" : name) {
                exportURL = ShareableFile(url: url)
            }
        }
    }
}
