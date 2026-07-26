import SwiftUI
import TransitionCore
import AVFoundation

// ===========================================================================
// Voix (§6.11) — un écran poussé de la pile unique.
//
// Trois blocs, dans cet ordre : la tendance (où en est ta hauteur), le
// magnétophone (le geste du jour), puis tes enregistrements. Les clips sont
// chiffrés au repos dans AppPaths.voiceDir ; la fréquence fondamentale (F0)
// est estimée sur l'appareil par YIN, jamais ailleurs.
//
// Grammaire iOS (§4) : pas de bande d'action ici — la colonne porte simplement
// 40 pt de marge basse, comme le prototype.
//
// Parité Android : VoiceScreen.kt + VoiceRepository.kt + PitchDetector.kt.
// ===========================================================================

@MainActor
final class VoiceViewModel: NSObject, ObservableObject {
    /// Cycle de vie de l'enregistreur. « Traitement » couvre le décodage +
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
    /// Incrémenté quand un clip vient d'être scellé dans le coffre : la vue
    /// s'en sert pour déclencher le retour haptique de succès.
    @Published var savedTick = 0

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
        error = nil
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
            error = "Le micro n'est pas autorisé. Tu peux l'activer dans les réglages du téléphone."
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
                error = "On n'a pas réussi à démarrer l'enregistrement. Réessaie dans un instant."
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
        // Bascule sur « Traitement… » immédiatement : le bouton montre un
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
            savedTick += 1
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
                    error = "Ce clip refuse de se lire. Il est peut-être abîmé."
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

// MARK: - Écran

struct VoiceView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var vm = VoiceViewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                if let message = vm.error {
                    ErrorCardView(message, retryLabel: "Réessayer") { Task { await reload() } }
                }

                if vm.loading {
                    SkeletonBlock(height: 170, cornerRadius: Radius.card)
                    SkeletonBlock(height: 210, cornerRadius: Radius.card)
                    SkeletonBlock(height: 140, cornerRadius: Radius.card)
                } else {
                    trendCard
                    if hasPitch { estimateCaveat }
                    recorderCard
                    SectionTitleView("TES ENREGISTREMENTS")
                    clipList
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
            // Voix ne porte pas de bande d'action : la colonne réserve sa
            // propre marge basse (§6.11).
            .padding(.bottom, 40)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Voix")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { dismiss() } label: {
                    HStack(spacing: 2) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 16, weight: .semibold))
                        Text("Retour").font(.system(size: 16))
                    }
                    .foregroundStyle(palette.primary)
                }
                .accessibilityLabel("Retour")
            }
        }
        .toolbarBackground(palette.surface, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .sensoryFeedback(.success, trigger: vm.savedTick)
        .task { await reload() }
        .onDisappear { vm.stopPlayback() }
    }

    private func reload() async {
        if let session = app.session { await vm.load(session) }
    }

    private var pitched: [VoiceClip] { vm.clips.filter { $0.pitchHz != nil } }
    private var hasPitch: Bool { !pitched.isEmpty }

    // MARK: - Carte de tendance F0

    private var trendCard: some View {
        // Hauteur du dernier clip en titre, delta vs. le tout premier clip
        // analysé : c'est le signal d'entraînement vocal.
        let latest = pitched.first
        let earliest = pitched.last
        let delta: Int32? = {
            guard let a = latest?.pitchHz, let b = earliest?.pitchHz, pitched.count >= 2 else {
                return nil
            }
            return a - b
        }()
        // Sparkline chronologique (du plus ancien au plus récent).
        let series = pitched.compactMap(\.pitchHz).reversed().map { Double($0) }

        return EggCard(variant: .low, paddingH: 18, paddingV: 18, spacing: Metrics.blockGap) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    MicroLabel(eyebrow(latest))
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(headlineValue(latest))
                            .font(.system(size: 34, weight: .semibold))
                            .foregroundStyle(palette.onSurface)
                        Text(headlineUnit(latest))
                            .font(EggFont.titleS)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                }
                Spacer(minLength: Spacing.s)
                if let delta { deltaPill(delta) }
            }
            .accessibilityElement(children: .combine)

            if series.count >= 2 {
                PitchSparkline(values: series)
                    .frame(height: 56)
            } else {
                Text(hasPitch
                     ? "Enregistre-toi une deuxième fois et la courbe se dessinera ici."
                     : "Enregistre-toi de temps en temps : la hauteur se lit sur la durée, "
                       + "jamais sur un seul clip.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func eyebrow(_ latest: VoiceClip?) -> String {
        guard let latest else { return "TA VOIX" }
        return "HAUTEUR MOYENNE · \(dayMonthUpper(latest.atMs))"
    }

    private func headlineValue(_ latest: VoiceClip?) -> String {
        if let pitch = latest?.pitchHz { return "\(pitch)" }
        return "\(vm.clips.count)"
    }

    private func headlineUnit(_ latest: VoiceClip?) -> String {
        if latest?.pitchHz != nil { return "Hz" }
        return vm.clips.count <= 1 ? "clip" : "clips"
    }

    /// La pilule marque **qu'il s'est passé quelque chose**, pas une bonne ou
    /// une mauvaise direction : certaines voix s'entraînent vers le haut,
    /// d'autres vers le bas. Le sens est porté par le glyphe et le signe, jamais
    /// par la couleur seule (§10).
    private func deltaPill(_ delta: Int32) -> some View {
        let stable = delta == 0
        let rising = delta > 0
        let glyph = stable ? "arrow.right" : (rising ? "arrow.up.right" : "arrow.down.right")
        let label = stable ? "Stable" : "\(rising ? "+" : "")\(delta) Hz"
        let spoken: String = {
            if stable { return "Hauteur stable depuis le premier enregistrement." }
            let amount = abs(Int(delta))
            return rising
                ? "\(amount) hertz de plus qu'au premier enregistrement."
                : "\(amount) hertz de moins qu'au premier enregistrement."
        }()

        return StatusPillView(
            label,
            systemImage: glyph,
            container: stable ? palette.surfaceContainerHighest : palette.successContainer,
            content: stable ? palette.onSurfaceVariant : palette.onSuccessContainer)
            .accessibilityLabel(spoken)
    }

    private var estimateCaveat: some View {
        Text("Estimation faite sur l'appareil, sensible aux conditions de capture. "
             + "Quelques hertz d'écart ne sont pas forcément un vrai changement.")
            .font(EggFont.bodyS)
            .foregroundStyle(palette.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, Spacing.xs)
    }

    // MARK: - Carte d'enregistrement

    private var recorderCard: some View {
        EggCard(variant: .primary, spacing: 14) {
            VStack(spacing: 14) {
                recorderButton
                Text(recorderTitle)
                    .font(EggFont.titleS)
                    .monospacedDigit()
                Text(recorderCaption)
                    .font(EggFont.bodyS)
                    .opacity(0.8)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var recorderButton: some View {
        Button {
            if let session = app.session { vm.toggle(session) }
        } label: {
            ZStack {
                Circle()
                    .fill(vm.isRecording ? palette.error : palette.primary)
                    .shadow(color: palette.scrim.opacity(0.22), radius: 10, y: 6)
                if vm.isProcessing {
                    ProgressView()
                        .controlSize(.large)
                        .tint(palette.onPrimary)
                } else {
                    Image(systemName: vm.isRecording ? "stop.fill" : "mic.fill")
                        .font(.system(size: 42, weight: .semibold))
                        .foregroundStyle(vm.isRecording ? palette.onError : palette.onPrimary)
                }
            }
            .frame(width: 96, height: 96)
            .scaleEffect(vm.isRecording && !reduceMotion ? 1.06 : 1)
            .animation(.easeInOut(duration: 0.25), value: vm.isRecording)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(vm.isProcessing)
        .accessibilityLabel(recorderAccessibilityLabel)
    }

    private var recorderTitle: String {
        switch vm.phase {
        case .recording: return "Enregistrement · \(mmss(vm.recordingMs))"
        case .processing: return "Traitement…"
        case .idle: return "Enregistrer 30 secondes"
        }
    }

    private var recorderCaption: String {
        switch vm.phase {
        case .recording:
            return "Appuie de nouveau pour arrêter. Trente secondes suffisent largement."
        case .processing:
            return "On estime la hauteur et on chiffre le clip. Encore quelques secondes."
        case .idle:
            return "Pièce calme, même paragraphe, téléphone à 30 cm — c'est ce qui rend la "
                + "courbe comparable."
        }
    }

    private var recorderAccessibilityLabel: String {
        switch vm.phase {
        case .recording: return "Arrêter l'enregistrement, \(mmss(vm.recordingMs)) écoulées"
        case .processing: return "Traitement de l'enregistrement en cours"
        case .idle: return "Enregistrer trente secondes de voix"
        }
    }

    // MARK: - Liste des clips

    @ViewBuilder
    private var clipList: some View {
        if vm.clips.isEmpty {
            EmptyStateView(
                "Rien d'enregistré pour l'instant. Un premier clip te donnera le point de "
                    + "départ auquel comparer tous les suivants.",
                systemImage: "waveform")
        } else {
            ListGroup {
                ForEach(Array(vm.clips.enumerated()), id: \.element.id) { pair in
                    ClipRow(
                        clip: pair.element,
                        playing: vm.playingId == pair.element.id,
                        showsSeparator: pair.offset < vm.clips.count - 1,
                        onPlay: {
                            if let session = app.session {
                                vm.togglePlay(pair.element, session: session)
                            }
                        },
                        decryptToTemp: { await vm.decryptToTemp(pair.element, session: $0) },
                        onDelete: {
                            if let session = app.session {
                                Task { await vm.delete(pair.element, session: session) }
                            }
                        })
                }
            }
        }
    }
}

// MARK: - Ligne de clip

private struct ClipRow: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette

    let clip: VoiceClip
    let playing: Bool
    let showsSeparator: Bool
    let onPlay: () -> Void
    let decryptToTemp: (VaultService) async -> URL?
    let onDelete: () -> Void

    @State private var shareItem: ShareItem?
    @State private var confirmDelete = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: Metrics.blockGap) {
                Button(action: onPlay) {
                    Image(systemName: playing ? "stop.fill" : "play.fill")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(palette.onPrimaryContainer)
                        .frame(width: 36, height: 36)
                        .background(palette.primaryContainer, in: Circle())
                        .frame(width: Metrics.touchTarget, height: Metrics.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(playing
                                    ? "Arrêter la lecture"
                                    : "Écouter l'enregistrement du \(dayMonth(clip.atMs))")

                VStack(alignment: .leading, spacing: 2) {
                    Text(dayMonth(clip.atMs))
                        .font(.eggCallout)
                        .foregroundStyle(palette.onSurface)
                    Text("\(mmss(clip.durationMs)) · \(timeLabel(clip.atMs))")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                }

                Spacer(minLength: Spacing.s)

                if let pitch = clip.pitchHz {
                    TypeBadgeView("\(pitch) Hz")
                        .accessibilityLabel("Hauteur estimée : \(pitch) hertz")
                }

                // Partager et supprimer sont des gestes rares : ils passent par
                // un menu explicite plutôt que par deux boutons permanents qui
                // écraseraient la ligne. Le glyphe reste visible pour rester
                // découvrable — un menu contextuel invisible ne l'est pas.
                Menu {
                    Button { startShare() } label: {
                        Label("Partager", systemImage: "square.and.arrow.up")
                    }
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label("Supprimer", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(palette.onSurfaceVariant)
                        .frame(width: Metrics.touchTarget, height: Metrics.touchTarget)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Autres actions pour l'enregistrement du \(dayMonth(clip.atMs))")
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.vertical, Spacing.m)

            if showsSeparator {
                Rectangle()
                    .fill(palette.outlineVariant)
                    .frame(height: 1)
                    .padding(.leading, Metrics.screenMargin)
            }
        }
        .sheet(item: $shareItem) { item in
            ShareSheet(url: item.url) { AppPaths.secureDelete(item.url) }
        }
        .alert("Supprimer cet enregistrement ?", isPresented: $confirmDelete) {
            Button("Annuler", role: .cancel) {}
            Button("Supprimer", role: .destructive) { onDelete() }
        } message: {
            Text("Le clip quitte le coffre pour de bon, et sa hauteur disparaît de la courbe.")
        }
    }

    private func startShare() {
        guard let session = app.session else { return }
        Task {
            if let url = await decryptToTemp(session) { shareItem = ShareItem(url: url) }
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
    @Environment(\.palette) private var palette
    let url: URL
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                Text("Une copie en clair a été préparée juste pour ce partage. "
                     + "Elle est effacée dès que tu fermes cette feuille.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                ShareLink(item: url) {
                    HStack(spacing: Spacing.s) {
                        Image(systemName: "square.and.arrow.up")
                            .font(.system(size: 17, weight: .semibold))
                        Text("Partager l'enregistrement")
                            .font(.system(size: 15.5, weight: .semibold))
                    }
                    .foregroundStyle(palette.onPrimary)
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .background(palette.primary, in: Capsule())
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)

                Spacer(minLength: 0)
            }
            .padding(Metrics.cardPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surface.ignoresSafeArea())
            .navigationTitle("Partager")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Fermer") { dismiss() }
                }
            }
        }
        .presentationDetents([.height(260)])
        .presentationDragIndicator(.visible)
        .onDisappear { onClose() }
    }
}

// MARK: - Sparkline de hauteur

/// La courbe du prototype : trait `--primary` de 2,4, bouts arrondis, et un
/// point terminal plein qui dit « voilà où tu en es aujourd'hui ».
private struct PitchSparkline: View {
    @Environment(\.palette) private var palette
    let values: [Double]

    /// Marge intérieure : le point terminal a un rayon de 4,2, il lui faut la
    /// place de ne pas être rogné par le bord du cadre.
    private let inset: CGFloat = 5

    var body: some View {
        GeometryReader { geo in
            let pts = points(in: geo.size)
            ZStack {
                Path { path in
                    guard let first = pts.first else { return }
                    path.move(to: first)
                    for point in pts.dropFirst() { path.addLine(to: point) }
                }
                .stroke(
                    palette.primary,
                    style: StrokeStyle(lineWidth: 2.4, lineCap: .round, lineJoin: .round))

                if let last = pts.last {
                    Circle()
                        .fill(palette.primary)
                        .frame(width: 8.4, height: 8.4)
                        .position(last)
                }
            }
        }
        .accessibilityElement()
        .accessibilityLabel(spokenSummary)
    }

    private func points(in size: CGSize) -> [CGPoint] {
        guard values.count >= 2 else { return [] }
        let minValue = values.min() ?? 0
        let maxValue = values.max() ?? 1
        let span = max(maxValue - minValue, 0.0001)
        let width = max(size.width - inset * 2, 1)
        let height = max(size.height - inset * 2, 1)
        let step = width / CGFloat(values.count - 1)
        var result: [CGPoint] = []
        result.reserveCapacity(values.count)
        for index in values.indices {
            let ratio = CGFloat((values[index] - minValue) / span)
            result.append(CGPoint(
                x: inset + CGFloat(index) * step,
                y: inset + height * (1 - ratio)))
        }
        return result
    }

    /// Un tracé n'a pas de contenu lisible : il est annoncé en une phrase (§10).
    private var spokenSummary: String {
        guard let first = values.first, let last = values.last else { return "Aucune courbe" }
        return "Courbe de hauteur sur \(values.count) enregistrements, "
            + "de \(Int(first.rounded())) à \(Int(last.rounded())) hertz."
    }
}

// MARK: - Helpers

private func mmss(_ ms: Int64) -> String {
    let total = Int(ms / 1000)
    return String(format: "%d:%02d", total / 60, total % 60)
}

/// « 21 juillet », l'année seulement quand ce n'est pas l'année en cours.
private func dayMonth(_ ms: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    let calendar = Calendar.current
    let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: Date())
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "fr_FR")
    formatter.dateFormat = sameYear ? "d MMMM" : "d MMMM yyyy"
    return formatter.string(from: date)
}

/// « 21 JUILLET » — l'étiquette en petites capitales de la carte de tendance,
/// mise en capitales dans la chaîne elle-même (§3.3).
private func dayMonthUpper(_ ms: Int64) -> String {
    let locale = Locale(identifier: "fr_FR")
    return dayMonth(ms).uppercased(with: locale)
}

private func timeLabel(_ ms: Int64) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "fr_FR")
    formatter.dateFormat = "HH:mm"
    return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
}
