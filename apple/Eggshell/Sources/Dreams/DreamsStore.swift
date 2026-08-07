import AVFoundation
import Foundation
import TransitionCore

/// The dream journal: entries, their tags, and their voice notes.
///
/// Mirrors Android's `DreamsRepository`, including the reason the audio gets
/// its own directory: the voice module sweeps every file under `voice/` whose
/// id is absent from `voice_clips` and does it at each unlock, so a dream
/// recording stored there would be deleted the first time the app locked.
/// Giving it a `voice_clips` row instead would file it in the voice-training
/// gallery next to pitch measurements. Silent loss one way, silent leakage the
/// other.
@MainActor
final class DreamsStore: ObservableObject {

    private var player: AVAudioPlayer?
    private var recorder: AVAudioRecorder?
    private var recordingURL: URL?
    private var recordingStartedAtMs: Int64 = 0
    /// Set while a clip is sounding so the row can show a stop button.
    @Published private(set) var playingAudioId: Int64?

    private var audioDir: URL {
        let dir = AppPaths.base.appendingPathComponent("dream_audio")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private var cacheDir: URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("dream_audio")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    // MARK: - Dreams

    func list(session: VaultService, tagId: Int64? = nil) async -> [Dream] {
        (try? await session.listDreams(tagId: tagId, limit: 200, offset: 0)) ?? []
    }

    func get(session: VaultService, id: Int64) async -> Dream? {
        try? await session.getDream(id)
    }

    func add(
        session: VaultService,
        nightMs: Int64,
        title: String,
        body: String,
        lucid: Bool
    ) async throws -> Dream {
        let now = Time.nowMs()
        return try await session.addDream(
            NewDream(
                nightMs: nightMs, title: title, body: body,
                lucid: lucid, createdMs: now, updatedMs: now))
    }

    /// In-place, which is what keeps the id stable — and the id is what the
    /// tags and the voice notes hang off.
    func update(
        session: VaultService,
        id: Int64,
        nightMs: Int64,
        title: String,
        body: String,
        lucid: Bool
    ) async throws -> Dream {
        try await session.updateDream(
            id, nightMs: nightMs, title: title, body: body,
            lucid: lucid, updatedMs: Time.nowMs())
    }

    func delete(session: VaultService, id: Int64) async {
        // Read the paths before the row goes: the cascade drops the audio rows,
        // and after that nothing knows which files to wipe.
        let paths = ((try? await session.dreamAudio(id)) ?? []).map { $0.filePath }
        try? await session.deleteDream(id)
        for p in paths { wipe(URL(fileURLWithPath: p)) }
    }

    // MARK: - Tags

    func tags(session: VaultService) async -> [DreamTag] {
        (try? await session.listDreamTags()) ?? []
    }

    func tags(session: VaultService, for dreamId: Int64) async -> [DreamTag] {
        (try? await session.tagsForDream(dreamId)) ?? []
    }

    /// Get-or-create in the core: typing a tag you already have lands on it.
    func addTag(session: VaultService, label: String) async -> DreamTag? {
        try? await session.addDreamTag(label, color: nil, createdMs: Time.nowMs())
    }

    func tag(session: VaultService, dreamId: Int64, tagId: Int64) async {
        try? await session.tagDream(dreamId, tagId: tagId)
    }

    func untag(session: VaultService, dreamId: Int64, tagId: Int64) async {
        try? await session.untagDream(dreamId, tagId: tagId)
    }

    // MARK: - Voice notes

    func audio(session: VaultService, for dreamId: Int64) async -> [DreamAudio] {
        (try? await session.dreamAudio(dreamId)) ?? []
    }

    func startRecording() -> Bool {
        guard recorder == nil else { return false }
        let url = cacheDir.appendingPathComponent("rec-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        do {
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
            let rec = try AVAudioRecorder(url: url, settings: settings)
            guard rec.record() else { return false }
            recorder = rec
            recordingURL = url
            recordingStartedAtMs = Time.nowMs()
            return true
        } catch {
            wipe(url)
            return false
        }
    }

    /// Stop, encrypt, attach.
    ///
    /// Returns the plaintext file alongside the row so the caller can hand it
    /// straight to the transcriber: decrypting the blob again just to re-derive
    /// what is already in hand would write a second plaintext copy to disk. The
    /// caller deletes it when done.
    func stopRecording(session: VaultService, dreamId: Int64) async -> (DreamAudio, URL)? {
        guard let rec = recorder, let url = recordingURL else { return nil }
        recorder = nil
        recordingURL = nil
        rec.stop()
        let durationMs = max(0, Time.nowMs() - recordingStartedAtMs)

        guard let bytes = try? Data(contentsOf: url), !bytes.isEmpty else {
            wipe(url)
            return nil
        }
        guard let cipher = try? await session.encryptBlob(bytes) else {
            wipe(url)
            return nil
        }
        let final = audioDir.appendingPathComponent("\(UUID().uuidString).bin")
        guard (try? cipher.write(to: final, options: Data.WritingOptions.atomic)) != nil else {
            wipe(url)
            return nil
        }
        guard let row = try? await session.addDreamAudio(
            NewDreamAudio(
                dreamId: dreamId, filePath: final.path,
                durationMs: durationMs, transcript: nil, createdMs: recordingStartedAtMs))
        else {
            wipe(url)
            return nil
        }
        return (row, url)
    }

    func cancelRecording() {
        guard let rec = recorder else { return }
        let url = recordingURL
        recorder = nil
        recordingURL = nil
        rec.stop()
        if let url { wipe(url) }
    }

    /// Decrypt to a temp file the caller is responsible for deleting.
    func decryptToTemp(session: VaultService, _ audio: DreamAudio) async -> URL? {
        guard let cipher = try? Data(contentsOf: URL(fileURLWithPath: audio.filePath)),
              let plain = try? await session.decryptBlob(cipher)
        else { return nil }
        let out = cacheDir.appendingPathComponent("play-\(UUID().uuidString).m4a")
        guard (try? plain.write(to: out, options: Data.WritingOptions.atomic)) != nil else { return nil }
        return out
    }

    /// Play or stop a voice note.
    ///
    /// Not a nice-to-have. Transcription is on-device only and plenty of
    /// devices cannot do it — on those, listening is the *only* way to get a
    /// dream back out of the app. A recording that can be made and never heard
    /// is worth nothing.
    func togglePlayback(session: VaultService, _ audio: DreamAudio) async {
        if playingAudioId == audio.id {
            stopPlayback()
            return
        }
        stopPlayback()
        guard let temp = await decryptToTemp(session: session, audio) else { return }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
            let p = try AVAudioPlayer(contentsOf: temp)
            p.delegate = PlaybackDelegate.shared
            PlaybackDelegate.shared.onFinish = { [weak self] in
                Task { @MainActor in self?.stopPlayback() }
            }
            p.play()
            player = p
            playingURL = temp
            playingAudioId = audio.id
        } catch {
            wipe(temp)
        }
    }

    private var playingURL: URL?

    func stopPlayback() {
        player?.stop()
        player = nil
        playingAudioId = nil
        if let url = playingURL { wipe(url) }
        playingURL = nil
    }

    func setTranscript(session: VaultService, audioId: Int64, transcript: String?) async {
        try? await session.setDreamTranscript(audioId, transcript: transcript)
    }

    func deleteAudio(session: VaultService, _ audio: DreamAudio) async {
        if playingAudioId == audio.id { stopPlayback() }
        try? await session.deleteDreamAudio(audio.id)
        wipe(URL(fileURLWithPath: audio.filePath))
    }

    /// Wipe decrypted copies. Called from the app's background purge.
    func purgeCache() {
        // Stop first, or the purge deletes the file out from under a playing
        // AVAudioPlayer and a dream keeps sounding from a phone whose owner has
        // already put the app away.
        stopPlayback()
        let files = (try? FileManager.default.contentsOfDirectory(
            at: cacheDir, includingPropertiesForKeys: nil)) ?? []
        for f in files { wipe(f) }
    }

    /// Delete ciphertext whose row is gone — a restored backup, or a crash
    /// between the row delete and the file delete. Dream recordings do not get
    /// to outlive the entry indefinitely.
    func cleanupOrphans(session: VaultService) async {
        guard let known = try? await session.allDreamAudioPaths() else { return }
        let names = Set(known.map { URL(fileURLWithPath: $0).lastPathComponent })
        let files = (try? FileManager.default.contentsOfDirectory(
            at: audioDir, includingPropertiesForKeys: nil)) ?? []
        for f in files where !names.contains(f.lastPathComponent) { wipe(f) }
    }

    private func wipe(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    // MARK: - Nights

    /// The night an instant belongs to, as local midnight.
    ///
    /// A dream recalled at 3 am belongs to the night that started the previous
    /// evening, not to the day that just began — so anything before noon counts
    /// back one day. Without it, waking at 2 am to scribble and finishing the
    /// entry at 9 am would file one night under two dates.
    static func nightOf(_ atMs: Int64, calendar: Calendar = .current) -> Int64 {
        let date = Date(timeIntervalSince1970: Double(atMs) / 1000)
        let hour = calendar.component(.hour, from: date)
        let day = hour < 12 ? calendar.date(byAdding: .day, value: -1, to: date)! : date
        return nightOf(date: day, calendar: calendar)
    }

    /// Local midnight of a chosen day — used when the user picks a night.
    static func nightOf(date: Date, calendar: Calendar = .current) -> Int64 {
        Int64(calendar.startOfDay(for: date).timeIntervalSince1970 * 1000)
    }
}

/// AVAudioPlayer's delegate is a stored `weak var`, so a closure cannot be one.
/// One shared object forwards completion back to whoever is playing.
private final class PlaybackDelegate: NSObject, AVAudioPlayerDelegate {
    static let shared = PlaybackDelegate()
    var onFinish: (() -> Void)?
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        onFinish?()
    }
}
