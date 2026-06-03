import SwiftUI
import TransitionCore
import AVFoundation

// ===========================================================================
// TAB-ROOT — Voice memos. Records short clips, encrypts them at rest as .bin
// blobs in AppPaths.voiceDir, and lists/plays/deletes them. F0 pitch estimation
// is not implemented yet (placeholder caption).
// ===========================================================================

@MainActor
final class VoiceViewModel: NSObject, ObservableObject {
    @Published var loading = true
    @Published var clips: [VoiceClip] = []
    @Published var error: String?
    @Published var isRecording = false
    @Published var isBusy = false
    @Published var playingId: String?

    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?
    private var tempRecordingURL: URL?
    private var recordStartedAt: Date?
    private var playerTempURL: URL?

    func load(_ session: VaultService) async {
        loading = true
        do {
            clips = try await session.listVoiceClips().sorted { $0.atMs > $1.atMs }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    // MARK: - Recording

    func toggleRecording(_ session: VaultService) {
        if isRecording {
            Task { await stopAndSave(session) }
        } else {
            Task { await startRecording() }
        }
    }

    private func startRecording() async {
        error = nil
        let granted = await requestPermission()
        guard granted else {
            error = "Permission micro refusée."
            return
        }
        do {
            let avSession = AVAudioSession.sharedInstance()
            try avSession.setCategory(.playAndRecord, mode: .default)
            try avSession.setActive(true)

            let url = AppPaths.cacheDir.appendingPathComponent("rec-\(UUID().uuidString).m4a")
            let settings: [String: Any] = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 44100.0,
                AVNumberOfChannelsKey: 1
            ]
            let rec = try AVAudioRecorder(url: url, settings: settings)
            rec.prepareToRecord()
            guard rec.record() else {
                error = "Impossible de démarrer l'enregistrement."
                return
            }
            recorder = rec
            tempRecordingURL = url
            recordStartedAt = Date()
            isRecording = true
        } catch {
            self.error = describe(error)
        }
    }

    private func stopAndSave(_ session: VaultService) async {
        guard let rec = recorder, let url = tempRecordingURL else {
            isRecording = false
            return
        }
        let duration = recordStartedAt.map { Date().timeIntervalSince($0) } ?? rec.currentTime
        rec.stop()
        recorder = nil
        isRecording = false
        isBusy = true
        try? AVAudioSession.sharedInstance().setActive(false)

        do {
            let data = try Data(contentsOf: url)
            let blobURL = try await session.encryptBlobToFile(data, in: AppPaths.voiceDir, ext: "bin")
            _ = try await session.addVoiceClip(NewVoiceClip(
                id: UUID().uuidString,
                atMs: Time.nowMs(),
                durationMs: Int64(duration * 1000),
                filePath: blobURL.path,
                pitchHz: nil))
            try? FileManager.default.removeItem(at: url)
            tempRecordingURL = nil
            recordStartedAt = nil
            await load(session)
        } catch {
            self.error = describe(error)
        }
        isBusy = false
    }

    private func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    // MARK: - Playback

    func play(_ clip: VoiceClip, session: VaultService) {
        Task {
            if playingId == clip.id {
                stopPlayback()
                return
            }
            stopPlayback()
            do {
                let blobURL = URL(fileURLWithPath: clip.filePath)
                let data = try await session.decryptBlobFile(blobURL)
                let temp = AppPaths.cacheDir.appendingPathComponent("play-\(UUID().uuidString).m4a")
                try data.write(to: temp)
                playerTempURL = temp

                let avSession = AVAudioSession.sharedInstance()
                try avSession.setCategory(.playback, mode: .default)
                try avSession.setActive(true)

                let p = try AVAudioPlayer(contentsOf: temp)
                p.delegate = self
                guard p.play() else {
                    error = "Lecture impossible."
                    return
                }
                player = p
                playingId = clip.id
            } catch {
                self.error = describe(error)
            }
        }
    }

    func stopPlayback() {
        player?.stop()
        player = nil
        playingId = nil
        if let t = playerTempURL { try? FileManager.default.removeItem(at: t); playerTempURL = nil }
        try? AVAudioSession.sharedInstance().setActive(false)
    }

    // MARK: - Delete

    func delete(_ clip: VoiceClip, session: VaultService) async {
        do {
            if playingId == clip.id { stopPlayback() }
            try await session.deleteVoiceClip(clip.id)
            try? FileManager.default.removeItem(at: URL(fileURLWithPath: clip.filePath))
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }
}

extension VoiceViewModel: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.stopPlayback() }
    }
}

struct VoiceView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @StateObject private var vm = VoiceViewModel()

    var body: some View {
        TabScaffold(title: "Voix") {
            if vm.loading {
                ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
            } else {
                recordCard
                if vm.clips.isEmpty {
                    EmptyStateCard(text: "Aucun enregistrement vocal pour le moment.", systemImage: "waveform")
                } else {
                    clipsCard
                }
            }
            if let e = vm.error { ErrorBanner(message: e) }
        }
        .task { if let s = app.session { await vm.load(s) } }
        .onDisappear { vm.stopPlayback() }
    }

    private var recordCard: some View {
        SectionCard {
            VStack(spacing: Spacing.m) {
                Button {
                    if let s = app.session { vm.toggleRecording(s) }
                } label: {
                    Image(systemName: vm.isRecording ? "stop.fill" : "mic.fill")
                        .font(.system(size: 40, weight: .semibold))
                        .foregroundStyle(palette.onPrimary)
                        .frame(width: 96, height: 96)
                        .background(vm.isRecording ? palette.error : palette.primary, in: Circle())
                }
                .buttonStyle(.plain)
                .disabled(vm.isBusy)

                Text(vm.isRecording ? "Enregistrement…" : "Appuie pour enregistrer")
                    .font(.eggCallout)
                    .foregroundStyle(palette.onSurface.opacity(0.7))

                Text("L'estimation de hauteur (F0) arrive bientôt.")
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.5))
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var clipsCard: some View {
        SectionCard {
            Text("Enregistrements").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            ForEach(vm.clips, id: \.id) { clip in
                clipRow(clip)
                if clip.id != vm.clips.last?.id {
                    Divider().overlay(palette.outlineVariant)
                }
            }
        }
    }

    private func clipRow(_ clip: VoiceClip) -> some View {
        HStack(spacing: Spacing.m) {
            Button {
                if let s = app.session { vm.play(clip, session: s) }
            } label: {
                Image(systemName: vm.playingId == clip.id ? "stop.circle.fill" : "play.circle.fill")
                    .font(.title)
                    .foregroundStyle(palette.primary)
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 2) {
                Text(dateLabel(clip.atMs)).font(.eggCallout).foregroundStyle(palette.onSurface)
                Text(durationLabel(clip.durationMs)).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            }
            Spacer()
            Button("Supprimer") {
                if let s = app.session { Task { await vm.delete(clip, session: s) } }
            }
            .font(.eggCaption)
            .foregroundStyle(palette.error)
            .buttonStyle(.plain)
        }
        .padding(.vertical, Spacing.xs)
    }

    private func dateLabel(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr")
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: date)
    }

    private func durationLabel(_ ms: Int64) -> String {
        let totalSeconds = Int(ms / 1000)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }
}
