import Foundation
import SwiftUI
import TransitionCore
import UIKit

/// Notes and the images embedded in them — the storage half of the module.
///
/// Mirrors android's `NotesRepository`. Rows go through `VaultService`; the
/// attachment ciphertext lives in `AppPaths.noteImagesDir`, which the core's v3
/// backup reads and restores by that exact name.
@MainActor
final class NotesStore: ObservableObject {
    @Published private(set) var folders: [NoteFolder] = []
    @Published private(set) var notes: [Note] = []
    @Published var error: String?

    /// Decrypted attachments, kept for the lifetime of the screen only.
    @Published private(set) var images: [Int64: UIImage] = [:]

    private let session: VaultService

    init(session: VaultService) { self.session = session }

    // MARK: Listing

    func load(folderId: Int64?) async {
        do {
            async let f = session.listNoteFolders(parentId: folderId)
            async let n = session.listNotes(folderId: folderId)
            folders = try await f
            notes = try await n
        } catch {
            self.error = "On n'a pas réussi à ouvrir tes notes."
        }
    }

    // MARK: Notes

    @discardableResult
    func create(title: String, body: String, folderId: Int64?) async -> Note? {
        let now = Time.nowMs()
        do {
            let note = try await session.addNote(
                NewNote(folderId: folderId, title: title, body: body,
                        createdMs: now, updatedMs: now))
            notes.append(note)
            return note
        } catch {
            self.error = "On n'a pas réussi à créer cette note."
            return nil
        }
    }

    func update(_ id: Int64, title: String, body: String) async {
        do {
            let updated = try await session.updateNote(id, title: title, body: body)
            if let i = notes.firstIndex(where: { $0.id == id }) { notes[i] = updated }
        } catch {
            self.error = "On n'a pas réussi à enregistrer cette note."
        }
    }

    /// Delete the note, its image rows (cascaded in SQL) and their ciphertext.
    ///
    /// The paths are read BEFORE the row goes: once the cascade has run nothing
    /// is left to say which files belonged to this note, and they would sit on
    /// disk until the orphan sweep noticed.
    func delete(_ id: Int64) async {
        do {
            let doomed = (try? await session.noteImages(noteId: id)) ?? []
            try await session.deleteNote(id)
            doomed.forEach { AppPaths.secureDelete(URL(fileURLWithPath: $0.filePath)) }
            notes.removeAll { $0.id == id }
        } catch {
            self.error = "On n'a pas réussi à supprimer cette note."
        }
    }

    func delete(ids: Set<Int64>) async {
        for id in ids { await delete(id) }
    }

    func move(_ id: Int64, toFolder folderId: Int64?) async {
        do {
            try await session.moveNote(id, toFolder: folderId)
            notes.removeAll { $0.id == id }
        } catch {
            self.error = "On n'a pas réussi à déplacer cette note."
        }
    }

    /// Persist the order the user just dragged into place.
    func reorder(_ ordered: [Note]) async {
        notes = ordered
        do {
            try await session.reorderNotes(ordered.map(\.id))
        } catch {
            self.error = "On n'a pas réussi à enregistrer l'ordre."
        }
    }

    // MARK: Folders

    func createFolder(named name: String, parentId: Int64?) async {
        do {
            let folder = try await session.addNoteFolder(
                NewNoteFolder(name: name, parentId: parentId, createdMs: Time.nowMs()))
            folders.append(folder)
        } catch {
            self.error = "On n'a pas réussi à créer ce dossier."
        }
    }

    func renameFolder(_ id: Int64, to name: String) async {
        do {
            try await session.renameNoteFolder(id, name: name)
            if let i = folders.firstIndex(where: { $0.id == id }) {
                folders[i] = NoteFolder(id: folders[i].id, name: name,
                                        parentId: folders[i].parentId,
                                        sortOrder: folders[i].sortOrder,
                                        createdMs: folders[i].createdMs)
            }
        } catch {
            self.error = "On n'a pas réussi à renommer ce dossier."
        }
    }

    func folderContentsCount(_ id: Int64) async -> Int64 {
        (try? await session.noteFolderContentsCount(id)) ?? 0
    }

    /// Delete a folder, everything nested inside it, and the files those notes
    /// owned. Paths first, for the same reason as `delete(_:)`.
    func deleteFolder(_ id: Int64) async {
        do {
            let doomed = (try? await session.noteImagePathsUnderFolder(id)) ?? []
            try await session.deleteNoteFolder(id)
            doomed.forEach { AppPaths.secureDelete(URL(fileURLWithPath: $0)) }
            folders.removeAll { $0.id == id }
        } catch {
            self.error = "On n'a pas réussi à supprimer ce dossier."
        }
    }

    // MARK: Attachments

    /// Encrypt a picked image into the note's own store and return its row id,
    /// so the caller can drop a marker for it into the body.
    ///
    /// Re-encoding to JPEG is what strips EXIF: an attachment dropped into a
    /// note is exactly as revealing as one added to the gallery, so it must not
    /// carry GPS or camera identity either.
    func attach(_ image: UIImage, to noteId: Int64) async -> Int64? {
        guard let cleaned = image.jpegData(compressionQuality: 0.9) else {
            self.error = "On n'a pas réussi à lire cette image."
            return nil
        }
        do {
            let url = try await session.encryptBlobToFile(cleaned, in: AppPaths.noteImagesDir)
            let position = Int64((try? await session.noteImages(noteId: noteId).count) ?? 0)
            do {
                let row = try await session.addNoteImage(
                    NewNoteImage(noteId: noteId, filePath: url.path, position: position))
                images[row.id] = image
                return row.id
            } catch {
                // No row means nothing will ever point at this file again.
                AppPaths.secureDelete(url)
                throw error
            }
        } catch {
            self.error = "On n'a pas réussi à joindre cette image."
            return nil
        }
    }

    /// Decrypt every attachment of a note into `images`, for rendering.
    func loadImages(noteId: Int64) async {
        guard let rows = try? await session.noteImages(noteId: noteId) else { return }
        for row in rows where images[row.id] == nil {
            if let data = try? await session.decryptBlobFile(URL(fileURLWithPath: row.filePath)),
               let ui = UIImage(data: data) {
                images[row.id] = ui
            }
        }
    }

    func detach(imageId: Int64, noteId: Int64) async {
        guard let rows = try? await session.noteImages(noteId: noteId),
              let row = rows.first(where: { $0.id == imageId }) else { return }
        try? await session.deleteNoteImage(imageId)
        AppPaths.secureDelete(URL(fileURLWithPath: row.filePath))
        images.removeValue(forKey: imageId)
    }

    /// Delete ciphertext with no row behind it — a crash between the file write
    /// and the INSERT, or a note deleted while its files were unreachable.
    ///
    /// Compares BASENAMES, not full paths: a restored backup carries rows whose
    /// `file_path` was absolute on the source device, and the core repoints
    /// them at import. Matching whole paths would make every restored image
    /// look orphaned and delete the lot at the first unlock.
    func cleanupOrphans() async {
        guard let tracked = try? await session.allNoteImagePaths() else { return }
        let keep = Set(tracked.map { URL(fileURLWithPath: $0).lastPathComponent })
        let dir = AppPaths.noteImagesDir
        let files = (try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: nil)) ?? []
        for file in files where !keep.contains(file.lastPathComponent) {
            AppPaths.secureDelete(file)
        }
    }
}
