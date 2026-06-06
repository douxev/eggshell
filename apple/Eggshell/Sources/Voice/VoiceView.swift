import SwiftUI
import TransitionCore
import AVFoundation

// ===========================================================================
// TAB-ROOT — Mémos vocaux. Enregistre de courts clips, les chiffre au repos
// dans AppPaths.voiceDir, estime la fréquence fondamentale (F0) via YIN, puis
// liste / lit / partage / supprime les clips. La carte de tendance trace
// l'évolution de la hauteur — c'est le signal de suivi de voix sous HRT.
//
// Parité Android : VoiceScreen.kt + VoiceRepository.kt + PitchDetector.kt.
// ===========================================================================

@MainActor
final class VoiceViewModel: NSObject, ObservableObject {
    /// Cycle de vie de l'enregistreur. "Traitement" couvre le décodage +
    /// détection de hauteur YIN + chiffrement AES-GCM, qui prend quelques
    /// secondes — assez pour que sans état dédié l'utilisateur croie que le
    /// bouton stop n'a pas répondu et double-tape.
    enum Phase { case idle, recording, processing }

    @Published var loading = true
    @Published var clips: [VoiceClip] = []
    @Published var error: String?
    @Published var phase: Phase = .idle
    @Published var recordingMs: Int64 = 0
    @Published var playingId: String?

    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?
    private var tempRecordingURL: URL?
    private var recordStartedAt: Date?
    private var recordStartedAtMs: Int64 = 0
    private var playerTempURL: URL?
    private var timer: Timer?

    var isRecording: Bool { phase == .recording }
    var isProcessing: Bool { phase == .processing }

    func load(_ session: VaultService) async {
        loading = true
        do {
            clips = try await session.listVoiceClips().sorted { $0.atMs > $1.atMs }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    // MARK: - Enregistrement

    func toggle(_ session: VaultService) {
        switch phase {
        case .recording: Task { await stopAndSave(session) }
        case .idle: Task { await startRecording() }
        case .processing: break // on ignore les taps pendant le traitement
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
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
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
            recordStartedAtMs = Time.nowMs()
            recordingMs = 0
            phase = .recording
            startTimer()
        } catch {
            self.error = describe(error)
        }
    }

    private func stopAndSave(_ session: VaultService) async {
        guard let rec = recorder, let url = tempRecordingURL else {
            phase = .idle
            return
        }
        stopTimer()
        let duration = recordStartedAt.map { Date().timeIntervalSince($0) } ?? rec.currentTime
        let startedAtMs = recordStartedAtMs
        rec.stop()
        recorder = nil
        // Bascule sur "Traitement…" immédiatement : le bouton montre un
        // indicateur et ignore les taps pendant le décodage + chiffrement.
        phase = .processing
        recordingMs = 0
        try? AVAudioSession.sharedInstance().setActive(false)

        // La détection YIN tourne sur la piste de fond pour ne pas figer l'UI.
        let pitchHz = await Task.detached { PitchDetector.estimatePitch(url: url) }.value

        do {
            let data = try Data(contentsOf: url)
            let blobURL = try await session.encryptBlobToFile(data, in: AppPaths.voiceDir)
            _ = try await session.addVoiceClip(NewVoiceClip(
                id: UUID().uuidString,
                atMs: startedAtMs,
                durationMs: Int64(duration * 1000),
                filePath: blobURL.path,
                pitchHz: pitchHz))
            AppPaths.secureDelete(url)
            tempRecordingURL = nil
            recordStartedAt = nil
            await load(session)
        } catch {
            AppPaths.secureDelete(url)
            self.error = describe(error)
        }
        phase = .idle
    }

    private func startTimer() {
        stopTimer()
        let t = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let start = self.recordStartedAt, self.phase == .recording else { return }
                self.recordingMs = Int64(Date().timeIntervalSince(start) * 1000)
            }
        }
        timer = t
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    private func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    // MARK: - Lecture

    func togglePlay(_ clip: VoiceClip, session: VaultService) {
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
        if let t = playerTempURL { AppPaths.secureDelete(t); playerTempURL = nil }
        try? AVAudioSession.sharedInstance().setActive(false)
    }

    // MARK: - Partage

    /// Déchiffre le clip vers un fichier temporaire en clair (cacheDir) pour
    /// ShareLink. À l'appelant de purger ensuite.
    func decryptToTemp(_ clip: VoiceClip, session: VaultService) async -> URL? {
        do {
            let data = try await session.decryptBlobFile(URL(fileURLWithPath: clip.filePath))
            let temp = AppPaths.cacheDir.appendingPathComponent("share-\(clip.id)-\(Time.nowMs()).m4a")
            try data.write(to: temp)
            return temp
        } catch {
            self.error = describe(error)
            return nil
        }
    }

    // MARK: - Suppression

    func delete(_ clip: VoiceClip, session: VaultService) async {
        do {
            if playingId == clip.id { stopPlayback() }
            try await session.deleteVoiceClip(clip.id)
            AppPaths.secureDelete(URL(fileURLWithPath: clip.filePath))
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
                trendCard
                if vm.clips.contains(where: { $0.pitchHz != nil }) {
                    Text("Estimation locale, sensible aux conditions de capture. Une variation de quelques Hz n'est pas forcément un vrai changement de F0.")
                        .font(.eggCaption)
                        .foregroundStyle(palette.onSurface.opacity(0.55))
                        .padding(.horizontal, Spacing.xs)
                }

                recordCard

                Text("Enregistrements")
                    .font(.eggLabel)
                    .foregroundStyle(palette.onSurface.opacity(0.6))

                if vm.clips.isEmpty {
                    EmptyStateCard(text: "Aucun enregistrement vocal pour le moment.", systemImage: "waveform")
                } else {
                    ForEach(vm.clips, id: \.id) { clip in
                        ClipRow(
                            clip: clip,
                            playing: vm.playingId == clip.id,
                            onPlay: { if let s = app.session { vm.togglePlay(clip, session: s) } },
                            decryptToTemp: { await vm.decryptToTemp(clip, session: $0) },
                            onDelete: { if let s = app.session { Task { await vm.delete(clip, session: s) } } })
                    }
                }
            }
            if let e = vm.error { ErrorBanner(message: e) }
        }
        .task { if let s = app.session { await vm.load(s) } }
        .onDisappear { vm.stopPlayback() }
    }

    // MARK: - Carte de tendance F0

    private var trendCard: some View {
        // Hauteur du dernier clip en titre, delta vs. le tout premier clip
        // analysé : c'est le signal d'entraînement vocal sous HRT.
        let withPitch = vm.clips.filter { $0.pitchHz != nil }
        let latest = withPitch.first?.pitchHz
        let earliest = withPitch.last?.pitchHz
        let delta: Int32? = {
            guard let latest, let earliest, withPitch.count >= 2 else { return nil }
            return latest - earliest
        }()
        // Sparkline chronologique (du plus ancien au plus récent).
        let series = withPitch.compactMap { $0.pitchHz }.reversed().map { Double($0) }

        return SectionCard {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(latest != nil ? "Hauteur (F0)" : "Clips enregistrés")
                        .font(.eggLabel)
                        .foregroundStyle(palette.onSurface.opacity(0.6))
                    if let latest {
                        HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                            Text("\(latest)")
                                .font(.eggDisplay)
                                .foregroundStyle(palette.onSurface)
                            Text("Hz")
                                .font(.eggBody)
                                .foregroundStyle(palette.onSurface.opacity(0.7))
                        }
                        Text(deltaLabel(delta, count: vm.clips.count))
                            .font(.eggCaption)
                            .foregroundStyle(palette.onSurface.opacity(0.8))
                    } else {
                        HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                            Text("\(vm.clips.count)")
                                .font(.eggDisplay)
                                .foregroundStyle(palette.onSurface)
                            Text(vm.clips.count <= 1 ? "clip" : "clips")
                                .font(.eggBody)
                                .foregroundStyle(palette.onSurface.opacity(0.7))
                        }
                        Text("Enregistre régulièrement pour suivre ta hauteur.")
                            .font(.eggCaption)
                            .foregroundStyle(palette.onSurface.opacity(0.8))
                    }
                }
                Spacer()
                if series.count >= 2 {
                    Sparkline(values: series, tint: palette.primary, height: 48)
                        .frame(width: 130)
                }
            }
        }
    }

    private func deltaLabel(_ delta: Int32?, count: Int) -> String {
        guard let delta else { return "\(count) clip\(count <= 1 ? "" : "s")" }
        if delta > 0 { return "+\(delta) Hz depuis le début" }
        if delta < 0 { return "\(delta) Hz depuis le début" }
        return "Stable depuis le début"
    }

    // MARK: - Carte d'enregistrement

    private var recordCard: some View {
        SectionCard {
            VStack(spacing: Spacing.m) {
                ZStack {
                    Circle()
                        .fill(recorderColor)
                        .frame(width: 92, height: 92)
                        .scaleEffect(vm.isRecording ? 1.06 : 1)
                        .animation(.easeInOut(duration: 0.25), value: vm.isRecording)
                    if vm.isProcessing {
                        ProgressView().tint(palette.onPrimary)
                    } else {
                        Button {
                            if let s = app.session { vm.toggle(s) }
                        } label: {
                            Image(systemName: vm.isRecording ? "stop.fill" : "mic.fill")
                                .font(.system(size: 38, weight: .semibold))
                                .foregroundStyle(palette.onPrimary)
                                .frame(width: 92, height: 92)
                        }
                        .buttonStyle(.plain)
                    }
                }

                Text(recorderTitle)
                    .font(.eggCallout)
                    .foregroundStyle(palette.onSurface)

                Text(recorderSubtitle)
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.55))
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var recorderColor: Color {
        switch vm.phase {
        case .recording: return palette.error
        case .processing: return palette.surfaceContainerHigh
        case .idle: return palette.primary
        }
    }

    private var recorderTitle: String {
        switch vm.phase {
        case .recording: return "Enregistrement… \(mmss(vm.recordingMs))"
        case .processing: return "Traitement…"
        case .idle: return "Appuie pour enregistrer"
        }
    }

    private var recorderSubtitle: String {
        switch vm.phase {
        case .processing: return "Estimation de la hauteur et chiffrement en cours."
        default: return "Quelques secondes suffisent pour estimer la hauteur (F0)."
        }
    }
}

// MARK: - Ligne de clip

private struct ClipRow: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette

    let clip: VoiceClip
    let playing: Bool
    let onPlay: () -> Void
    let decryptToTemp: (VaultService) async -> URL?
    let onDelete: () -> Void

    @State private var shareItem: ShareItem?

    var body: some View {
        HStack(spacing: Spacing.m) {
            Button(action: onPlay) {
                Image(systemName: playing ? "stop.circle.fill" : "play.circle.fill")
                    .font(.title)
                    .foregroundStyle(palette.primary)
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(dateLabel(clip.atMs))
                        .font(.eggCallout)
                        .foregroundStyle(palette.onSurface)
                    Spacer()
                    if let pitch = clip.pitchHz {
                        Pill(text: "\(pitch) Hz")
                    }
                }
                Text(mmss(clip.durationMs))
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
            }

            Button {
                guard let s = app.session else { return }
                Task {
                    if let url = await decryptToTemp(s) { shareItem = ShareItem(url: url) }
                }
            } label: {
                Image(systemName: "square.and.arrow.up")
                    .foregroundStyle(palette.onSurface.opacity(0.7))
            }
            .buttonStyle(.plain)

            Button(action: onDelete) {
                Image(systemName: "trash")
                    .foregroundStyle(palette.error)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, Spacing.xs)
        .sheet(item: $shareItem) { item in
            ShareSheet(url: item.url) { AppPaths.secureDelete(item.url) }
        }
    }
}

/// Petit wrapper Identifiable pour piloter `.sheet(item:)` avec une URL en clair.
private struct ShareItem: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

// MARK: - Feuille de partage

/// ShareLink dans une feuille dédiée afin de purger le fichier en clair après
/// fermeture (defense-in-depth — on ne laisse pas traîner d'audio déchiffré).
private struct ShareSheet: View {
    @Environment(\.dismiss) private var dismiss
    let url: URL
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: Spacing.l) {
                ShareLink(item: url) {
                    Label("Partager l'enregistrement", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .glassProminentButton()
            }
            .padding(Spacing.xl)
            .navigationTitle("Partager")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Fermer") { dismiss() }
                }
            }
        }
        .onDisappear { onClose() }
    }
}

// MARK: - Helpers

private func mmss(_ ms: Int64) -> String {
    let total = Int(ms / 1000)
    return String(format: "%d:%02d", total / 60, total % 60)
}

private func dateLabel(_ ms: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    let f = DateFormatter()
    f.locale = Locale(identifier: "fr_FR")
    f.dateFormat = "d MMM HH:mm"
    return f.string(from: date)
}
