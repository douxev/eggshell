import Foundation

// On-disk locations. All app data lives in Application Support (excluded from
// iCloud/iTunes backup — sensitive, encrypted-at-rest already). Mirrors the
// Android filesDir layout: vault.db + photos/ + voice/ holding AES-GCM blobs.
enum AppPaths {
    static var base: URL = {
        let dir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("eggshell", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        excludeFromBackup(dir)
        return dir
    }()

    static var realDB: URL { base.appendingPathComponent("vault.db") }
    static var decoyDB: URL { base.appendingPathComponent("vault_decoy.db") }

    static var photosDir: URL { ensureDir("photos") }
    static var voiceDir: URL { ensureDir("voice") }
    /// Note attachments get their own directory rather than sharing photos/:
    /// the photo orphan sweep deletes every .bin under photos/ with no
    /// photo_records row, and note images would be gone at the first unlock.
    /// Giving them photo rows instead would put private attachments into the
    /// progress gallery and the doctor PDF — silent leakage for silent loss.
    ///
    /// The name matters beyond tidiness: the core's v3 backup reads and
    /// restores the vault.db siblings by these exact names.
    static var noteImagesDir: URL { ensureDir("note_images") }
    static var cacheDir: URL {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("eggshell", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    private static func ensureDir(_ name: String) -> URL {
        let d = base.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    private static func excludeFromBackup(_ url: URL) {
        var u = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? u.setResourceValues(values)
    }

    /// Best-effort secure delete: overwrite then unlink (defense-in-depth for
    /// any plaintext temp that slipped to disk).
    static func secureDelete(_ url: URL) {
        if let h = try? FileHandle(forWritingTo: url),
           let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? Int, size > 0 {
            let zeros = Data(count: min(size, 1 << 20))
            try? h.write(contentsOf: zeros)
            try? h.close()
        }
        try? FileManager.default.removeItem(at: url)
    }

    /// Delete everything (full wipe).
    static func wipeAll() {
        try? FileManager.default.removeItem(at: base)
        try? FileManager.default.removeItem(at: cacheDir)
    }
}
