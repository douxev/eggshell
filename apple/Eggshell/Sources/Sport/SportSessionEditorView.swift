import SwiftUI
import TransitionCore

@MainActor
final class SportSessionEditorViewModel: ObservableObject {
    @Published var activities: [SportActivity] = []
    @Published var activityId: Int64?
    @Published var started = Date()
    /// Minutes, as typed. Kept as text so a half-entered "4" is not read as 4.
    @Published var minutes = ""
    @Published var note = ""
    /// Why the last save failed. Surfaced rather than swallowed: a write that
    /// fails silently is indistinguishable from one that worked.
    @Published var error: String?

    private(set) var sessionId: Int64?
    var canDelete: Bool { sessionId != nil }

    /// The distance and heart rate of the session being edited, held and
    /// written back untouched. The editor has no field for them — they are
    /// numbers a watch measured, not ones anyone types — and an update that did
    /// not carry them forward would silently erase them the first time someone
    /// fixed a typo in the note.
    private var distanceM: Double?
    private var avgHr: Int64?
    private var maxHr: Int64?

    /// Where the session came from, carried through an edit: fixing the note
    /// on a watch import used to relabel it "manual", and the source is what
    /// tells a later import which sessions are the user's own word.
    private var source = SportStore.sourceManual

    /// A duration known to the second, and the minutes text it was shown as.
    /// While the field still shows that text the exact figure is saved — editing
    /// the note of a 42 min 37 s run must not round it, and a 45-second import
    /// (shown as « 0 ») must stay savable. Typing a new number replaces it.
    private var exact: (shown: String, seconds: Int64)?

    /// The chronometer's start, or nil. The elapsed time is always computed
    /// from it, so it stays right across a locked screen.
    @Published private(set) var timerStart: Date?

    func startTimer() {
        let now = Date()
        timerStart = now
        // The session starts when the chronometer does.
        started = now
    }

    func stopTimer() {
        guard let start = timerStart else { return }
        timerStart = nil
        let seconds = max(1, Int64(Date().timeIntervalSince(start)))
        let shown = String((seconds + 59) / 60)
        minutes = shown
        exact = (shown, seconds)
    }

    private var durationS: Int64? {
        if let exact, exact.shown == minutes { return exact.seconds }
        return Int64(minutes).map { $0 * 60 }
    }

    /// True when there is a duration to save. A session with no length is not one.
    var canSave: Bool { timerStart != nil || (durationS ?? 0) > 0 }

    func load(session: VaultService, id: Int64?) async {
        sessionId = id
        // Only unarchived types are offered for a NEW session; an existing one
        // keeps whatever it was logged under, even if archived since.
        activities = await SportStore.activities(session, includeArchived: id != nil)
        guard let id, let s = try? await session.getSportSession(id) else { return }
        activityId = s.activityId
        started = Date(timeIntervalSince1970: Double(s.startedMs) / 1000)
        let shown = String(s.durationS / 60)
        minutes = shown
        exact = (shown, s.durationS)
        note = s.freeText ?? ""
        distanceM = s.distanceM
        avgHr = s.avgHr
        maxHr = s.maxHr
        source = s.source
    }

    func onMinutes(_ typed: String) {
        minutes = String(typed.filter(\.isNumber).prefix(4))
    }

    /// Returns true when the session is on disk.
    func save(session: VaultService, heartRate: HeartRateMonitor) async -> Bool {
        // Saving with the chronometer running stops it: that is the end of the session.
        if timerStart != nil { stopTimer() }
        guard let duration = durationS, duration > 0 else { return false }
        // Only for a session recorded here and now. Re-opening an old session
        // with a strap on must not rewrite the heart rate it was logged with.
        if sessionId == nil {
            if let avg = heartRate.averageBpm { avgHr = Int64(avg) }
            if let peak = heartRate.maxBpm { maxHr = Int64(peak) }
        }
        let new = NewSportSession(
            activityId: activityId,
            startedMs: Int64(started.timeIntervalSince1970 * 1000),
            durationS: duration,
            freeText: note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : note,
            distanceM: distanceM,
            avgHr: avgHr,
            maxHr: maxHr,
            source: source)
        do {
            if let id = sessionId {
                _ = try await session.updateSportSession(id, new)
            } else {
                sessionId = try await session.addSportSession(new).id
            }
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    func delete(session: VaultService) async {
        guard let id = sessionId else { return }
        try? await session.deleteSportSession(id)
    }
}

/// New or existing session: type, when, how long, an optional sensor, a note.
struct SportSessionEditorView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = SportSessionEditorViewModel()
    /// Owned by the editor so leaving it drops the connection: a link left
    /// open would keep the radio busy and the sensor awake for as long as the
    /// app lived, which is a battery cost the user never asked for.
    @StateObject private var heartRate = HeartRateMonitor()

    let sessionId: Int64?
    @State private var confirmDelete = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                EggCard(variant: .low, spacing: Spacing.s) {
                    MicroLabel("ACTIVITÉ")
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ChoiceChip(label: "Sans type", selected: vm.activityId == nil) {
                                vm.activityId = nil
                            }
                            ForEach(vm.activities, id: \.id) { a in
                                ChoiceChip(label: a.name, selected: vm.activityId == a.id) {
                                    vm.activityId = a.id
                                }
                            }
                        }
                    }
                }

                EggCard(variant: .low, spacing: Spacing.s) {
                    MicroLabel("DATE ET HEURE")
                    // A session you have not done yet is not a session: the
                    // picker stops at now rather than letting one be dated
                    // forwards by accident.
                    DatePicker(
                        "Date et heure", selection: $vm.started, in: ...Date(),
                        displayedComponents: [.date, .hourAndMinute])
                        .labelsHidden()
                        .tint(palette.primary)
                        .environment(\.locale, Locale(identifier: "fr_FR"))
                }

                // The chronometer, for a session being done now: start it at
                // the beginning, stop it at the end, and the duration fills in.
                if sessionId == nil {
                    chronoCard
                }

                EggCard(variant: .low, spacing: Spacing.s) {
                    MicroLabel("DURÉE")
                    HStack {
                        TextField("45", text: Binding(get: { vm.minutes }, set: vm.onMinutes))
                            .disabled(vm.timerStart != nil)
                            .keyboardType(.numberPad)
                            .font(.eggBody)
                            .foregroundStyle(palette.onSurface)
                        Text("min")
                            .font(.eggBody)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    .padding(.horizontal, Spacing.m)
                    .padding(.vertical, 10)
                    .frame(minHeight: Metrics.touchTarget)
                    .background(
                        palette.surfaceContainerHigh,
                        in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                    if vm.timerStart == nil && !vm.minutes.isEmpty && !vm.canSave {
                        Text("Indique une durée.")
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.error)
                    }
                }

                // Only for a session being logged now: connecting a sensor while
                // editing last week's run would measure nothing about it.
                if sessionId == nil {
                    heartRateCard
                }

                EggCard(variant: .low, spacing: Spacing.s) {
                    MicroLabel("NOTES")
                    TextEditor(text: $vm.note)
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurface)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 110)
                        .padding(.horizontal, Spacing.s)
                        .background(
                            palette.surfaceContainerHigh,
                            in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                }

                if vm.canDelete {
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label("Supprimer la séance", systemImage: "trash")
                            .font(.system(size: 15.5, weight: .semibold))
                            .foregroundStyle(palette.error)
                            .frame(maxWidth: .infinity)
                            .frame(height: 46)
                            .overlay(Capsule().stroke(palette.outline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }

                if let error = vm.error { ErrorCardView(error) }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle(sessionId == nil ? "Nouvelle séance" : "Modifier la séance")
        .navigationBarTitleDisplayMode(.inline)
        .eggActionBar {
            ActionBarButton("Enregistrer", systemImage: "checkmark", enabled: vm.canSave) { save() }
        }
        .alert("Supprimer cette séance ?", isPresented: $confirmDelete) {
            Button("Supprimer", role: .destructive) {
                guard let session = app.session else { return }
                Task {
                    await vm.delete(session: session)
                    router.pop()
                }
            }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("Cette séance sera définitivement effacée.")
        }
        .task {
            guard let session = app.session else { return }
            // A new session starts from nothing; an existing one keeps what it
            // was recorded with and is not overwritten by a sensor connected now.
            if sessionId == nil { heartRate.resetSummary() }
            await vm.load(session: session, id: sessionId)
        }
        .onDisappear { heartRate.stop() }
    }

    private func save() {
        guard let session = app.session else { return }
        Task {
            if await vm.save(session: session, heartRate: heartRate) { router.pop() }
        }
    }

    /// Connect a heart-rate sensor, and show what it is saying. A card the
    /// user has to press rather than a scan that starts with the screen: a
    /// Bluetooth scan is a radio cost and a permission prompt, and most
    /// sessions are typed in without a strap anywhere near.
    private var heartRateCard: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            HStack(alignment: .center, spacing: Spacing.m) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Fréquence cardiaque")
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                    Text(heartRateLabel)
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: Spacing.s)
                Button(heartRate.state.isActive ? "Déconnecter" : "Connecter") {
                    if heartRate.state.isActive { heartRate.stop() } else { heartRate.start() }
                }
                .font(EggFont.label)
                .foregroundStyle(palette.primary)
                .buttonStyle(.plain)
            }
        }
    }

    /// Start, stop, and the time in between — redrawn every second from the
    /// start instant rather than counted up, so a locked screen cannot make it
    /// drift.
    private var chronoCard: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            HStack(alignment: .center, spacing: Spacing.m) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Chronomètre")
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                    if let start = vm.timerStart {
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            Text(Self.elapsedLabel(Int64(max(0, context.date.timeIntervalSince(start)))))
                                .font(.system(size: 24, weight: .semibold).monospacedDigit())
                                .foregroundStyle(palette.onSurface)
                        }
                    } else {
                        Text("Lance-le au début de la séance : la durée se remplit toute seule à l'arrêt.")
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: Spacing.s)
                Button(vm.timerStart == nil ? "Démarrer" : "Arrêter") {
                    if vm.timerStart == nil { vm.startTimer() } else { vm.stopTimer() }
                }
                .font(EggFont.label)
                .foregroundStyle(palette.primary)
                .buttonStyle(.plain)
            }
        }
    }

    /// "4:07", "1:02:45".
    static func elapsedLabel(_ seconds: Int64) -> String {
        let h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    /// One line saying exactly where the connection has got to, or what went wrong.
    private var heartRateLabel: String {
        switch heartRate.state {
        case .idle:
            return "Connecte une ceinture, une bague ou ta montre en mode « diffusion de la fréquence cardiaque » (Garmin, Polar, Coros, Suunto, Amazfit…). L'Apple Watch et la plupart des montres Wear OS ne diffusent pas. Rien ne sort du téléphone."
        case .scanning:
            return "Recherche d'un capteur…"
        case .connecting(let name):
            return "Connexion à \(name ?? "ton capteur")…"
        case .waiting:
            return "Connecté. En attente d'une première mesure."
        case .live(_, let bpm):
            return "\(bpm) bpm"
        case .reconnecting:
            return "Signal perdu, reconnexion… La moyenne déjà mesurée est conservée."
        case .failed(let reason):
            switch reason {
            case .bluetoothOff:  return "Le Bluetooth est éteint."
            case .noPermission:  return "Autorisation Bluetooth refusée."
            case .notFound:      return "Aucun capteur trouvé en 20 secondes. Vérifie que la diffusion de la fréquence cardiaque est activée sur la montre ou que la ceinture est bien portée, puis réessaie."
            case .disconnected:  return "Capteur déconnecté."
            case .unsupported:   return "Cet appareil ne gère pas ce type de capteur."
            }
        }
    }
}
