import Foundation
import TransitionCore
import UIKit

/// Export notes as a ZIP holding markdown at the root and an `assets/` folder,
/// the way Obsidian and Joplin hand a vault over.
///
/// Mirrors android's `NoteExporter`. The image markers of `NoteImageRef` are
/// rewritten to relative paths on the way out, so the archive opens correctly
/// in any markdown editor rather than only in this app.
enum NoteExporter {

    /// Build the archive and return a URL the share sheet can carry.
    ///
    /// The staging directory lives in the cache and holds *decrypted* files, so
    /// it is torn down as soon as the zip exists — see `stage(_:)`.
    static func zip(notes: [Note], session: VaultService,
                    archiveName: String) async throws -> URL {
        let staging = AppPaths.cacheDir
            .appendingPathComponent("note_export/\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { wipe(staging) }

        var usedNames = Set<String>()
        for note in notes {
            let images = (try? await session.noteImages(noteId: note.id)) ?? []
            var relativeByImageId: [Int64: String] = [:]

            if !images.isEmpty {
                let assets = staging.appendingPathComponent("assets", isDirectory: true)
                try? FileManager.default.createDirectory(at: assets, withIntermediateDirectories: true)
                for image in images {
                    guard let data = try? await session.decryptBlobFile(
                        URL(fileURLWithPath: image.filePath)) else { continue }
                    let name = "\(note.id)-\(image.id).jpg"
                    try? data.write(to: assets.appendingPathComponent(name))
                    relativeByImageId[image.id] = "assets/\(name)"
                }
            }

            let fileName = uniqueName(for: note, taken: &usedNames)
            let markdown = rewriteImageRefs(in: note.body, to: relativeByImageId)
            let document = "# \(note.title)\n\n\(markdown)\n"
            try document.data(using: .utf8)?
                .write(to: staging.appendingPathComponent(fileName))
        }

        return try archive(staging, named: archiveName)
    }

    // MARK: Internals

    /// `![](eggshell-note-image:42)` → `![](assets/7-42.jpg)`.
    ///
    /// An image whose file could not be decrypted keeps its marker rather than
    /// becoming a broken relative link: a reader then sees that something was
    /// there, instead of an empty box that looks like a rendering bug.
    private static func rewriteImageRefs(in body: String,
                                         to relative: [Int64: String]) -> String {
        body.components(separatedBy: .newlines).map { line -> String in
            guard let id = NoteImageRef.imageId(inMarkerLine: line),
                  let path = relative[id] else { return line }
            return "![](\(path))"
        }.joined(separator: "\n")
    }

    /// A filesystem-safe, collision-free name. Two notes may share a title, and
    /// on a case-insensitive filesystem « Rdv » and « RDV » are the same file.
    private static func uniqueName(for note: Note, taken: inout Set<String>) -> String {
        let allowed = CharacterSet.alphanumerics.union(.init(charactersIn: " -_éèêàâîïôûùç"))
        var base = String(note.title.unicodeScalars.filter { allowed.contains($0) })
            .trimmingCharacters(in: .whitespaces)
        if base.isEmpty { base = "note-\(note.id)" }
        base = String(base.prefix(60))

        var candidate = "\(base).md"
        var suffix = 2
        while taken.contains(candidate.lowercased()) {
            candidate = "\(base)-\(suffix).md"
            suffix += 1
        }
        taken.insert(candidate.lowercased())
        return candidate
    }

    /// Zip a directory without a third-party dependency.
    ///
    /// `NSFileCoordinator` with `.forUploading` is the system's own zipper: it
    /// hands back a temporary archive inside the coordinated block, which is
    /// why the bytes are copied out before returning rather than the URL kept.
    private static func archive(_ directory: URL, named archiveName: String) throws -> URL {
        var coordinatorError: NSError?
        var copyError: Error?
        let destination = AppPaths.cacheDir
            .appendingPathComponent("note_export", isDirectory: true)
            .appendingPathComponent("\(archiveName).zip")
        try? FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: destination)

        NSFileCoordinator().coordinate(
            readingItemAt: directory, options: [.forUploading], error: &coordinatorError
        ) { zipped in
            do { try FileManager.default.copyItem(at: zipped, to: destination) }
            catch { copyError = error }
        }
        if let coordinatorError { throw coordinatorError }
        if let copyError { throw copyError }
        return destination
    }

    /// The staging tree held plaintext copies of encrypted attachments; scrub
    /// them rather than just unlinking the directory.
    private static func wipe(_ directory: URL) {
        let files = (try? FileManager.default.subpathsOfDirectory(atPath: directory.path)) ?? []
        for relative in files {
            let url = directory.appendingPathComponent(relative)
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue {
                AppPaths.secureDelete(url)
            }
        }
        try? FileManager.default.removeItem(at: directory)
    }
}
