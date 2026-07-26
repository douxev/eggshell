import SwiftUI
import LocalAuthentication
import UniformTypeIdentifiers
import TransitionCore

// ===========================================================================
// Première ouverture (§6.14) — three segments, and one question instead of a
// technical menu.
//
//   1/3  « Qui peut prendre ton téléphone ? »
//        Three situations rather than four security modes. The third one chains
//        phrase secrète → PIN d'accès + PIN leurre (obligatoirement différents)
//        → icône déguisée. Two discreet escape hatches stay reachable from
//        here: « Configurer manuellement » (the raw four modes) and
//        « J'ai déjà une sauvegarde » (restore an encrypted bundle).
//   2/3  Modules, plus an optional first treatment and its daily reminder.
//   3/3  Apparence, then « Tout est prêt ».
//
// The pre-refonte wizard was seven pages; nothing it did has been dropped, it
// has been folded into these three. Every step past the vault creation can be
// skipped from the header and re-done later in Réglages.
//
// The vault session is created once (create / restore) then carried through the
// rest of the wizard; the last screen hands it to AppState.completeOnboarding.
// ===========================================================================

// Building an `LAContext` is not free and the answer cards re-render on every
// keystroke, so the two facts we need about this device are resolved once.
private let biometricGlyph: String = Biometric.kind == .faceID ? "faceid" : "touchid"
private let biometricUsable: Bool = Biometric.isAvailable

@MainActor
final class OnboardingViewModel: ObservableObject {
    /// The situation the person is in — not a mode. What each one configures is
    /// spelled out on its card, in plain words.
    enum Answer: String, CaseIterable, Identifiable {
        case nobody      // Keystore only, opens instantly
        case entourage   // biometrics at every open
        case coerced     // paranoid passphrase + decoy PIN + disguised icon

        var id: String { rawValue }

        var title: String {
            switch self {
            case .nobody:    return "Personne, je suis tranquille"
            case .entourage: return "Mon entourage, parfois"
            case .coerced:   return "On peut me demander de l'ouvrir"
            }
        }
        var detail: String {
            switch self {
            case .nobody:
                return "Ouverture immédiate. Les données restent chiffrées sur l'appareil."
            case .entourage:
                return "Empreinte ou visage à chaque ouverture."
            case .coerced:
                return "Phrase secrète, second code qui ouvre une app de notes anodine, et icône déguisée."
            }
        }
        var systemImage: String {
            switch self {
            case .nobody:    return "checkmark.circle.fill"
            case .entourage: return biometricGlyph
            case .coerced:   return "lock.shield.fill"
            }
        }
        /// The recommended answer wears the primary card and says so.
        var recommended: Bool { self == .coerced }
    }

    enum Step: Equatable {
        // 1/3 — sécurité
        case answer
        case manualMode          // escape hatch: the raw four modes
        case passphrase
        case pinPair             // access PIN + decoy PIN
        case disguise            // launcher icon
        case restore
        case creating
        // 2/3 — modules
        case modules
        // 3/3 — apparence
        case appearance
        case ready
    }

    /// Which of the four PIN entries the pad is collecting.
    enum PinStage: Equatable { case access, accessConfirm, decoy, decoyConfirm }

    static let minPassphrase = 8

    @Published var step: Step = .answer
    @Published var error: String?

    // 1/3 — security.
    @Published var answer: Answer?
    @Published var manualMode: SecurityMode?
    @Published var mode: SecurityMode = .keystoreBiometric
    @Published var passphrase = ""
    @Published var passphraseConfirm = ""

    // PIN pair.
    @Published var pinStage: PinStage = .access
    @Published var pinEntry = ""
    @Published var pinError: String?
    @Published private(set) var savingPins = false
    private var accessPin = ""
    private var decoyPin = ""

    // Disguise.
    @Published var iconVariant: AppIconVariant = .default

    // Restore.
    @Published var restoreURL: URL?
    @Published var bundlePassphrase = ""
    @Published var restoreError: String?

    // 2/3 — the optional first treatment. Held here rather than in the step so
    // the anchored action bar can commit it.
    @Published var wantsFirstMed = false
    @Published var medName = ""
    @Published var medDose = ""
    @Published var medUnit = ""
    @Published var medRemind = false
    @Published var medHour = 8
    @Published var medMinute = 0

    /// The session, once created/restored. Drives the rest of the wizard.
    private(set) var session: VaultService?

    private weak var app: AppState?
    func bind(_ app: AppState) { self.app = app }

    // MARK: - Progress

    /// Which of the three segments the current step belongs to.
    var segment: Int {
        switch step {
        case .answer, .manualMode, .passphrase, .pinPair, .disguise, .restore, .creating:
            return 0
        case .modules:
            return 1
        case .appearance, .ready:
            return 2
        }
    }

    /// Where « Passer » goes, or nil when the step is not optional. Creating the
    /// vault never is — everything after it can be re-done in Réglages.
    var skipTarget: Step? {
        switch step {
        case .pinPair:    return .disguise
        case .disguise:   return .modules
        case .modules:    return .appearance
        case .appearance: return .ready
        default:          return nil
        }
    }

    /// Step the Back button returns to, or nil. There is no way back once the
    /// vault exists: re-entering the creation steps would try to create a
    /// second one.
    var backTarget: Step? {
        switch step {
        case .manualMode, .restore:
            return .answer
        case .passphrase:
            return answer == .coerced ? .answer : .manualMode
        default:
            return nil
        }
    }

    func goBack() {
        error = nil
        if let target = backTarget { step = target }
    }

    func skip() {
        error = nil
        if let target = skipTarget { step = target }
    }

    // MARK: - Validation

    var passphraseValid: Bool {
        passphrase.count >= Self.minPassphrase && passphrase == passphraseConfirm
    }
    var passphraseTooShort: Bool {
        !passphrase.isEmpty && passphrase.count < Self.minPassphrase
    }
    var passphraseMismatch: Bool {
        !passphrase.isEmpty && !passphraseConfirm.isEmpty && passphrase != passphraseConfirm
    }

    // MARK: - The one action of the step

    var actionTitle: String? {
        switch step {
        case .answer:      return "Continuer"
        case .manualMode:  return "Continuer"
        case .passphrase:  return "Créer mon coffre"
        case .restore:     return "Restaurer"
        case .disguise:    return "Continuer"
        case .modules:     return "Continuer"
        case .appearance:  return "Continuer"
        case .ready:       return "Commencer"
        case .pinPair, .creating: return nil
        }
    }

    var actionEnabled: Bool {
        switch step {
        case .answer:     return answer != nil
        case .manualMode: return manualMode != nil
        case .passphrase: return passphraseValid
        case .restore:    return restoreURL != nil && !bundlePassphrase.isEmpty
        default:          return true
        }
    }

    func primaryAction() {
        switch step {
        case .answer:      commitAnswer()
        case .manualMode:  commitManualMode()
        case .passphrase:  createWithPassphrase()
        case .restore:     startRestore()
        case .disguise:    applyDisguise()
        case .modules:     commitModules()
        case .appearance:  step = .ready
        case .ready:       finish()
        case .pinPair, .creating: break
        }
    }

    // MARK: - 1/3 Security

    private func commitAnswer() {
        guard let answer else { return }
        error = nil
        switch answer {
        case .nobody:    createKeystore(mode: .keystoreOnly)
        case .entourage: createKeystore(mode: .keystoreBiometric)
        case .coerced:
            mode = .paranoid
            passphrase = ""
            passphraseConfirm = ""
            step = .passphrase
        }
    }

    func chooseManual() {
        error = nil
        manualMode = nil
        step = .manualMode
    }

    func chooseRestore() {
        error = nil
        restoreError = nil
        bundlePassphrase = ""
        restoreURL = nil
        step = .restore
    }

    private func commitManualMode() {
        guard let m = manualMode else { return }
        error = nil
        mode = m
        if m.needsPassphrase {
            passphrase = ""
            passphraseConfirm = ""
            step = .passphrase
        } else {
            createKeystore(mode: m)
        }
    }

    private func createKeystore(mode m: SecurityMode) {
        guard let app else { return }
        error = nil
        mode = m
        step = .creating
        Task {
            do {
                var ctx: LAContext?
                if m.needsBiometric {
                    ctx = try await Biometric.authenticate(
                        reason: "Activer le déverrouillage biométrique")
                }
                let s = try await app.manager.create(mode: m, passphrase: nil, biometricContext: ctx)
                self.session = s
                self.step = .modules
            } catch {
                self.error = describe(error)
                self.step = .answer
            }
        }
    }

    func createWithPassphrase() {
        guard let app, passphraseValid else { return }
        let m = mode
        let pass = passphrase
        let chained = answer == .coerced
        error = nil
        step = .creating
        Task {
            do {
                let s = try await app.manager.create(mode: m, passphrase: pass, biometricContext: nil)
                self.session = s
                self.passphrase = ""
                self.passphraseConfirm = ""
                // The coerced branch continues into the decoy pair; the manual
                // passphrase modes go straight to the modules.
                self.step = chained ? .pinPair : .modules
            } catch {
                self.error = describe(error)
                self.step = .passphrase
            }
        }
    }

    // MARK: - 1/3 PIN pair

    var pinStageTitle: String {
        switch pinStage {
        case .access:        return "Choisis ton code d'accès"
        case .accessConfirm: return "Retape ton code d'accès"
        case .decoy:         return "Choisis ton code leurre"
        case .decoyConfirm:  return "Retape ton code leurre"
        }
    }
    var pinStageDetail: String {
        switch pinStage {
        case .access, .accessConfirm:
            return "Quatre chiffres. C'est celui-ci qui ouvre tes vraies données."
        case .decoy, .decoyConfirm:
            return "Celui-ci ouvre une app de notes ordinaire. Personne ne peut deviner qu'il en existe un autre."
        }
    }

    func appendPin(_ digit: String) {
        guard !savingPins, pinEntry.count < 4 else { return }
        pinError = nil
        pinEntry.append(digit)
        if pinEntry.count == 4 { advancePinStage() }
    }

    func backspacePin() { if !pinEntry.isEmpty { pinEntry.removeLast() } }

    private func advancePinStage() {
        let entered = pinEntry
        pinEntry = ""
        switch pinStage {
        case .access:
            accessPin = entered
            pinStage = .accessConfirm
        case .accessConfirm:
            if entered == accessPin {
                pinStage = .decoy
            } else {
                accessPin = ""
                pinStage = .access
                pinError = "Les deux saisies ne correspondent pas. On recommence."
            }
        case .decoy:
            if entered == accessPin {
                pinError = "Ton code leurre doit être différent de ton code d'accès."
            } else {
                decoyPin = entered
                pinStage = .decoyConfirm
            }
        case .decoyConfirm:
            if entered == decoyPin {
                savePinPair()
            } else {
                decoyPin = ""
                pinStage = .decoy
                pinError = "Les deux saisies ne correspondent pas. On recommence."
            }
        }
    }

    private func savePinPair() {
        let access = accessPin
        let fake = decoyPin
        savingPins = true
        pinError = nil
        Task {
            // Both PINs go through Argon2id, which is deliberately slow: off the
            // main actor so the pad does not freeze mid-animation. The message is
            // built there too — an `Error` would not survive the hop.
            let failure: String? = await Task.detached {
                do {
                    try DecoyVerifier().setPair(accessPin: access, decoyPin: fake)
                    return nil
                } catch {
                    return describe(error)
                }
            }.value
            self.savingPins = false
            if let failure {
                self.pinError = failure
                self.pinStage = .access
            } else {
                self.accessPin = ""
                self.decoyPin = ""
                self.step = .disguise
            }
        }
    }

    // MARK: - 1/3 Disguise

    private func applyDisguise() {
        let variant = iconVariant
        error = nil
        step = .modules
        guard variant != .default else { return }
        Task { try? await AppIconManager.set(variant) }
    }

    // MARK: - Restore

    private func startRestore() {
        guard let app, let url = restoreURL else { return }
        restoreError = nil
        error = nil
        let pass = bundlePassphrase
        step = .creating
        Task {
            // The picked URL may be security-scoped (Files / iCloud).
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                // Local mode = keystoreOnly: paranoid restore would require a DB
                // re-key (VaultManager throws paranoidRequiresRekey otherwise).
                let s = try await app.manager.restore(
                    fromBundle: data,
                    bundlePassphrase: pass,
                    mode: .keystoreOnly,
                    localPassphrase: nil,
                    biometricContext: nil)
                // Restore brings a fully populated vault — skip the setup.
                app.completeOnboarding(session: s)
            } catch {
                self.restoreError = describe(error)
                self.step = .restore
            }
        }
    }

    // MARK: - 2/3 Modules

    /// Persist the optional first treatment (+ optional daily reminder) then
    /// advance. Best-effort: a failure surfaces an error but never blocks.
    private func commitModules() {
        error = nil
        let trimmed = medName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard wantsFirstMed, !trimmed.isEmpty, let session else {
            step = .appearance
            return
        }
        let dose = Self.parseDose(medDose)
        let unit = medUnit.trimmingCharacters(in: .whitespaces)
        let remind = medRemind
        let hour = medHour
        let minute = medMinute
        Task {
            do {
                let med = try await session.addMedication(
                    NewMedication(
                        name: trimmed,
                        kind: "other",
                        route: "oral",
                        defaultDose: dose,
                        defaultDoseUnit: unit.isEmpty ? nil : unit,
                        color: nil,
                        notes: nil))
                if remind {
                    let seed = NewDoseSchedule(
                        medicationId: med.id,
                        kind: "daily",
                        intervalMinutes: nil,
                        dailyHour: UInt32(max(0, min(23, hour))),
                        dailyMinute: UInt32(max(0, min(59, minute))),
                        intervalDays: nil,
                        nextDueAtMs: 0)
                    var sched = seed
                    sched.nextDueAtMs = NextDueCalculator.firstDue(seed)
                    _ = try await session.addSchedule(sched)
                }
            } catch {
                self.error = describe(error)
            }
            self.step = .appearance
        }
    }

    static func parseDose(_ s: String) -> Double? {
        Double(s.replacingOccurrences(of: ",", with: "."))
    }

    // MARK: - Finish

    func finish() {
        guard let app, let session else { return }
        app.completeOnboarding(session: session)
    }
}

// MARK: - Screen

struct OnboardingView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var features: FeaturesStore
    @EnvironmentObject private var theme: ThemeStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = OnboardingViewModel()

    var body: some View {
        ZStack {
            palette.surface.ignoresSafeArea()
            VStack(spacing: 0) {
                ProgressSegments(segment: vm.segment,
                                 showsSkip: vm.skipTarget != nil,
                                 onSkip: vm.skip)
                ScrollView {
                    VStack(alignment: .leading, spacing: Spacing.l) {
                        if vm.backTarget != nil {
                            Button(action: vm.goBack) {
                                HStack(spacing: 2) {
                                    Image(systemName: "chevron.left")
                                        .font(.system(size: 13, weight: .semibold))
                                    Text("Retour").font(EggFont.label)
                                }
                                .foregroundStyle(palette.primary)
                                .frame(minHeight: Metrics.touchTarget, alignment: .leading)
                            }
                            .buttonStyle(.plain)
                        }
                        if let e = vm.error { ErrorCardView(e) }
                        stepContent
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, vm.backTarget == nil ? 26 : Spacing.s)
                    .padding(.bottom, Spacing.xl)
                }
            }
        }
        // The action bar reserves its band; on the steps that have no single
        // action (the pad, the spinner) nothing is inset at all.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let title = vm.actionTitle {
                ActionBar {
                    ActionBarButton(title, enabled: vm.actionEnabled) { vm.primaryAction() }
                }
            }
        }
        .task { vm.bind(app) }
    }

    @ViewBuilder
    private var stepContent: some View {
        switch vm.step {
        case .answer:     SecurityAnswerStep(vm: vm)
        case .manualMode: ManualModeStep(vm: vm)
        case .passphrase: PassphraseStep(vm: vm)
        case .pinPair:    PinPairStep(vm: vm)
        case .disguise:   DisguiseStep(vm: vm)
        case .restore:    RestoreStep(vm: vm)
        case .creating:   CreatingStep()
        case .modules:    ModulesStep(vm: vm, features: features)
        case .appearance: AppearanceStep(theme: theme)
        case .ready:      ReadyStep(paranoid: vm.mode.needsPassphrase)
        }
    }
}

// MARK: - Progress row

/// Three bars 24 × 4 and, when the step is optional, « Passer ».
private struct ProgressSegments: View {
    @Environment(\.palette) private var palette
    let segment: Int
    let showsSkip: Bool
    let onSkip: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 7) {
            ForEach(0..<3, id: \.self) { i in
                Capsule()
                    .fill(i <= segment ? palette.primary : palette.surfaceContainerHighest)
                    .frame(width: 24, height: 4)
            }
            Spacer(minLength: Spacing.s)
            if showsSkip {
                Button(action: onSkip) {
                    Text("Passer")
                        .font(EggFont.micro)
                        .tracking(0.5)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .padding(.horizontal, 6)
                        .frame(minHeight: Metrics.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 4 + 20)
        .padding(.top, 14)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Étape \(segment + 1) sur 3"))
    }
}

// MARK: - Shared step chrome

/// Title + subtitle, the same two lines on every step of the first run.
private struct StepHeading: View {
    @Environment(\.palette) private var palette
    let title: String
    let detail: String
    var large: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(large ? Font.system(size: 30, weight: .medium, design: .rounded) : Font.eggTitle)
                .foregroundStyle(palette.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            Text(detail)
                .font(.eggBody)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - 1/3 « Qui peut prendre ton téléphone ? »

private struct SecurityAnswerStep: View {
    @ObservedObject var vm: OnboardingViewModel
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            // The narrow no-break space before the "?" is the French rule.
            StepHeading(
                title: "Qui peut prendre ton téléphone\u{202F}?",
                detail: "Une seule question, et l'app se configure. Tu pourras tout changer plus tard.",
                large: true)

            VStack(spacing: 10) {
                ForEach(OnboardingViewModel.Answer.allCases) { answer in
                    answerCard(answer)
                }
            }

            VStack(spacing: Spacing.m) {
                Button { vm.chooseManual() } label: {
                    Text("Configurer manuellement")
                        .font(EggFont.micro)
                        .tracking(0.5)
                        .foregroundStyle(palette.primary)
                        .frame(maxWidth: .infinity, minHeight: Metrics.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                Button { vm.chooseRestore() } label: {
                    Text("J'ai déjà une sauvegarde")
                        .font(EggFont.micro)
                        .tracking(0.5)
                        .foregroundStyle(palette.primary)
                        .frame(maxWidth: .infinity, minHeight: Metrics.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            HStack(alignment: .top, spacing: Spacing.s) {
                Image(systemName: "lock.fill").font(.system(size: 12, weight: .semibold))
                Text("Tout est chiffré sur ton téléphone. Aucun compte, aucun serveur, aucune publicité.")
                    .fixedSize(horizontal: false, vertical: true)
            }
            .font(EggFont.bodyS)
            .foregroundStyle(palette.onSurfaceVariant)
        }
    }

    private func answerCard(_ answer: OnboardingViewModel.Answer) -> some View {
        let selected = vm.answer == answer
        let highlighted = answer.recommended
        // Offering biometrics on a phone that has none would send the person
        // into a prompt that can only fail, so the card says so and steps aside.
        let unavailable = (answer == .entourage && !biometricUsable)
        return EggCard(
            variant: highlighted ? .primary : .low,
            paddingH: 18,
            paddingV: 18,
            spacing: Spacing.m,
            action: { vm.answer = answer }
        ) {
            HStack(alignment: .top, spacing: 14) {
                IconTile(
                    size: 42,
                    cornerRadius: 14,
                    container: highlighted ? palette.primary : palette.surfaceContainerHighest
                ) {
                    Image(systemName: answer.systemImage)
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(highlighted ? palette.onPrimary : palette.onSurfaceVariant)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(answer.title)
                        .font(EggFont.titleS)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(answer.detail)
                        .font(EggFont.bodyS)
                        .foregroundStyle(
                            highlighted
                            ? palette.onPrimaryContainer.opacity(0.82)
                            : palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            if highlighted {
                HStack(spacing: 7) {
                    Image(systemName: "checkmark").font(.system(size: 12, weight: .bold))
                    Text("Recommandé si tu hésites").font(EggFont.micro).tracking(0.5)
                }
            }
            if unavailable {
                HStack(spacing: 7) {
                    Image(systemName: "exclamationmark.circle").font(.system(size: 12, weight: .bold))
                    Text("Aucun Face ID ni Touch ID configuré sur ce téléphone pour l'instant.")
                        .font(EggFont.bodyS)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(palette.onSurfaceVariant)
            }
        }
        .overlay(
            RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                .strokeBorder(selected ? palette.primary : Color.clear, lineWidth: 2)
        )
        .disabled(unavailable)
        .opacity(unavailable ? 0.55 : 1)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

// MARK: - 1/3 Escape hatch: the raw four modes

private struct ManualModeStep: View {
    @ObservedObject var vm: OnboardingViewModel
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            StepHeading(
                title: "Choisis toi-même",
                detail: "Quel que soit le mode, tout est chiffré sur l'appareil. Tu peux en changer plus tard.")
            ForEach(SecurityMode.allCases) { m in
                let selected = vm.manualMode == m
                EggCard(variant: .low, paddingH: 18, paddingV: 18, spacing: 4,
                        action: { vm.manualMode = m }) {
                    Text(m.title).font(EggFont.titleS)
                    Text(m.blurb)
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .overlay(
                    RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                        .strokeBorder(selected ? palette.primary : Color.clear, lineWidth: 2)
                )
                .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
        }
    }
}

// MARK: - 1/3 Passphrase

private struct PassphraseStep: View {
    @ObservedObject var vm: OnboardingViewModel
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            StepHeading(
                title: "Choisis ta phrase secrète",
                detail: "Au moins \(OnboardingViewModel.minPassphrase) caractères. Une phrase entière est plus solide et plus facile à retenir qu'un mot compliqué.")

            VStack(alignment: .leading, spacing: Spacing.s) {
                field("Phrase secrète", text: $vm.passphrase)
                if vm.passphraseTooShort {
                    hint("Encore un peu courte — \(OnboardingViewModel.minPassphrase) caractères minimum.",
                         color: palette.error)
                }
                field("Retape-la", text: $vm.passphraseConfirm)
                if vm.passphraseMismatch {
                    hint("Les deux ne correspondent pas.", color: palette.error)
                }
            }

            EggCard(variant: .error, paddingH: 18, paddingV: 18, spacing: Spacing.s) {
                HStack(alignment: .top, spacing: Spacing.m) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 16, weight: .semibold))
                    Text("Note-la quelque part de sûr. Elle chiffre tout : sans elle, personne ne peut rouvrir tes données — nous non plus.")
                        .font(EggFont.bodyS)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func field(_ placeholder: String, text: Binding<String>) -> some View {
        SecureField(placeholder, text: text)
            .textFieldStyle(.plain)
            .textContentType(.newPassword)
            .foregroundStyle(palette.onSurface)
            .padding(.horizontal, Spacing.l)
            .frame(height: 52)
            .background(
                RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                    .fill(palette.surfaceContainerHigh)
            )
    }

    private func hint(_ text: String, color: Color) -> some View {
        Text(text)
            .font(EggFont.bodyS)
            .foregroundStyle(color)
            .fixedSize(horizontal: false, vertical: true)
    }
}

// MARK: - 1/3 Access PIN + decoy PIN

private struct PinPairStep: View {
    @ObservedObject var vm: OnboardingViewModel
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            StepHeading(title: vm.pinStageTitle, detail: vm.pinStageDetail)

            VStack(spacing: Spacing.l) {
                PinPips(count: vm.pinEntry.count,
                        filled: palette.primary,
                        empty: palette.outline)
                if let e = vm.pinError {
                    Text(e)
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onErrorContainer)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(palette.errorContainer, in: Capsule())
                }
                if vm.savingPins {
                    HStack(spacing: Spacing.s) {
                        ProgressView()
                        Text("On enregistre tes codes…")
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                }
                PinKeypad(
                    keyContainer: palette.surfaceContainerHigh,
                    digitColor: palette.onSurface,
                    accentColor: palette.primary,
                    mutedColor: palette.onSurfaceVariant,
                    enabled: !vm.savingPins,
                    biometricSymbol: nil,
                    onDigit: { vm.appendPin($0) },
                    onBackspace: { vm.backspacePin() })
            }
            .frame(maxWidth: .infinity)

            Text("Les deux codes doivent être différents — c'est ce qui rend le leurre crédible.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - 1/3 Disguised icon

private struct DisguiseStep: View {
    @ObservedObject var vm: OnboardingViewModel
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            StepHeading(
                title: "Sous quel nom sur ton écran d'accueil\u{202F}?",
                detail: "L'icône et le nom de l'app changent. Ce que tu choisis ici ne touche pas à tes données.")

            ListGroup {
                let all = AppIconVariant.allCases
                ForEach(Array(all.enumerated()), id: \.element.id) { pair in
                    ListRowView(
                        title: pair.element.label,
                        subtitle: pair.element == .default ? "L'icône d'origine" : nil,
                        systemImage: pair.element.systemImage,
                        iconContainer: vm.iconVariant == pair.element ? palette.primaryContainer : nil,
                        iconTint: vm.iconVariant == pair.element ? palette.onPrimaryContainer : nil,
                        // Always a trailing slot, empty when unselected: it is a
                        // choice, not a door, so no disclosure chevron.
                        trailingText: vm.iconVariant == pair.element ? "✓" : "",
                        showsSeparator: pair.offset < all.count - 1,
                        action: { vm.iconVariant = pair.element })
                }
            }

            if !AppIconManager.available {
                Text("Les icônes de rechange ne sont pas encore intégrées à cette version : ton choix sera appliqué dès qu'elles arriveront.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - Restore

private struct RestoreStep: View {
    @ObservedObject var vm: OnboardingViewModel
    @Environment(\.palette) private var palette
    @State private var picking = false

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            StepHeading(
                title: "Reprendre ta sauvegarde",
                detail: "Choisis ton fichier de sauvegarde chiffré, puis saisis la phrase secrète qui l'a protégé.")

            Button { picking = true } label: {
                HStack(spacing: Spacing.m) {
                    Image(systemName: "doc.badge.plus").font(.system(size: 17, weight: .semibold))
                    Text(vm.restoreURL?.lastPathComponent ?? "Choisir un fichier…")
                        .font(EggFont.titleS)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .foregroundStyle(palette.onSurface)
                .padding(.horizontal, Spacing.l)
                .frame(height: 52)
                .frame(maxWidth: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                        .fill(palette.surfaceContainerHigh)
                )
            }
            .buttonStyle(.plain)

            SecureField("Phrase secrète de la sauvegarde", text: $vm.bundlePassphrase)
                .textFieldStyle(.plain)
                .foregroundStyle(palette.onSurface)
                .padding(.horizontal, Spacing.l)
                .frame(height: 52)
                .background(
                    RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                        .fill(palette.surfaceContainerHigh)
                )

            if let e = vm.restoreError { ErrorCardView(e) }
        }
        .fileImporter(
            isPresented: $picking,
            allowedContentTypes: [.data, .item],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls): vm.restoreURL = urls.first
            case .failure(let err):  vm.restoreError = describe(err)
            }
        }
    }
}

// MARK: - Creating

private struct CreatingStep: View {
    @Environment(\.palette) private var palette
    var body: some View {
        VStack(spacing: Spacing.m) {
            ProgressView()
            Text("On prépare ton coffre…")
                .font(.eggBody)
                .foregroundStyle(palette.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Spacing.xxl)
    }
}

// MARK: - 2/3 Modules (+ optional first treatment)

private struct ModulesStep: View {
    @ObservedObject var vm: OnboardingViewModel
    @ObservedObject var features: FeaturesStore
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            StepHeading(
                title: "Qu'est-ce que tu veux suivre\u{202F}?",
                detail: "Coche large ou serré, comme tu veux : tout se règle ensuite dans Réglages › Modules.")

            ListGroup {
                ToggleRow(label: "Médics", systemImage: "pills.fill", isOn: $features.medications)
                ToggleRow(label: "Ressenti", systemImage: "face.smiling", isOn: $features.journal)
                ToggleRow(label: "Analyses", systemImage: "drop.fill", isOn: $features.hormones)
                ToggleRow(label: "Poids", systemImage: "ruler", isOn: $features.weight)
                ToggleRow(label: "Menstruations", systemImage: "calendar.badge.clock", isOn: $features.bleeding)
                ToggleRow(label: "Rendez-vous", systemImage: "calendar", isOn: $features.appointments)
                ToggleRow(label: "Photos", systemImage: "camera.fill", isOn: $features.photos)
                ToggleRow(label: "Voix", systemImage: "waveform", isOn: $features.voice,
                          showsSeparator: false)
            }
            .tint(palette.primary)

            if features.medications { firstMedication }
        }
    }

    private var firstMedication: some View {
        EggCard(variant: .low, spacing: Spacing.m) {
            Toggle(isOn: $vm.wantsFirstMed) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Ajouter un premier traitement").font(EggFont.titleS)
                    Text("Facultatif — tu pourras en ajouter autant que tu veux ensuite.")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(palette.primary)

            if vm.wantsFirstMed {
                CardRule()
                textField("Nom", text: $vm.medName)
                if vm.medName.trimmingCharacters(in: .whitespaces).isEmpty {
                    Text("Donne-lui un nom pour l'enregistrer — sinon on passe simplement à la suite.")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: Spacing.s) {
                    textField("Dose", text: $vm.medDose, numeric: true)
                    textField("Unité", text: $vm.medUnit)
                }
                Toggle("Me le rappeler chaque jour", isOn: $vm.medRemind)
                    .font(EggFont.titleS)
                    .tint(palette.primary)
                if vm.medRemind {
                    stepperRow("Heure", value: $vm.medHour, range: 0...23)
                    stepperRow("Minute", value: $vm.medMinute, range: 0...59)
                }
            }
        }
    }

    private func textField(_ placeholder: String, text: Binding<String>, numeric: Bool = false) -> some View {
        TextField(placeholder, text: text)
            .textFieldStyle(.plain)
            .keyboardType(numeric ? .decimalPad : .default)
            .foregroundStyle(palette.onSurface)
            .padding(.horizontal, Spacing.m)
            .frame(height: 48)
            .background(
                RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                    .fill(palette.surfaceContainerHighest)
            )
    }

    private func stepperRow(_ label: String, value: Binding<Int>, range: ClosedRange<Int>) -> some View {
        HStack {
            Text(label).font(EggFont.titleS).foregroundStyle(palette.onSurface)
            Spacer()
            Text(String(format: "%02d", value.wrappedValue))
                .font(EggFont.titleS)
                .foregroundStyle(palette.onSurfaceVariant)
                .monospacedDigit()
            Stepper(label, value: value, in: range).labelsHidden()
        }
        .frame(minHeight: Metrics.touchTarget)
    }
}

/// A settings line whose trailing control is a native switch.
private struct ToggleRow: View {
    @Environment(\.palette) private var palette
    let label: String
    let systemImage: String
    @Binding var isOn: Bool
    var showsSeparator: Bool = true

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: Spacing.m) {
                IconTile(size: 44) {
                    Image(systemName: systemImage)
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                Toggle(label, isOn: $isOn)
                    .font(EggFont.titleS)
                    .foregroundStyle(palette.onSurface)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.vertical, Spacing.s)
            .frame(minHeight: 56)
            if showsSeparator {
                Rectangle()
                    .fill(palette.outlineVariant)
                    .frame(height: 1)
                    .padding(.leading, ListRowView.separatorInset)
            }
        }
    }
}

// MARK: - 3/3 Appearance

private struct AppearanceStep: View {
    @ObservedObject var theme: ThemeStore
    @Environment(\.palette) private var palette
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            StepHeading(
                title: "Choisis ton ambiance",
                detail: "L'aperçu est direct : touche une pastille et toute l'app change.")
            let columns = [GridItem(.adaptive(minimum: 96), spacing: Spacing.m)]
            LazyVGrid(columns: columns, spacing: Spacing.m) {
                ForEach(Themes.all) { t in
                    swatch(t)
                }
            }
        }
    }

    private func swatch(_ t: Theme) -> some View {
        let selected = theme.themeId == t.id
        let preview = t.swatch(dark: colorScheme == .dark)
        return Button { theme.themeId = t.id } label: {
            VStack(spacing: Spacing.s) {
                ZStack {
                    RoundedRectangle(cornerRadius: Radius.iconTile, style: .continuous)
                        .fill(preview.surface)
                    Circle()
                        .fill(preview.primary)
                        .frame(width: 26, height: 26)
                }
                .frame(height: 48)
                Text(t.label)
                    .font(EggFont.label)
                    .foregroundStyle(palette.onSurface)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(Spacing.s)
            .background(
                RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                    .fill(palette.surfaceContainerLow)
            )
            .overlay(
                RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                    .strokeBorder(selected ? palette.primary : Color.clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

// MARK: - 3/3 Ready

private struct ReadyStep: View {
    @Environment(\.palette) private var palette
    let paranoid: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            EggshellLogo(size: 74)
            Text("Tout est prêt")
                .font(.eggDisplay)
                .foregroundStyle(palette.onSurface)
            Text("Ton coffre chiffré est créé. Rien ne quitte ton téléphone : ni compte, ni serveur, ni statistique.")
                .font(.eggBody)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            EggCard(variant: .low, paddingH: 18, paddingV: 18, spacing: Spacing.s) {
                Text("Ce que tu peux faire ensuite").font(EggFont.titleS)
                Text("Note une prise depuis l'accueil, remplis ton ressenti du jour, et quand une consultation approche, prépare un récapitulatif pour ton médecin.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if paranoid {
                EggCard(variant: .tertiary, paddingH: 18, paddingV: 18, spacing: Spacing.s) {
                    HStack(alignment: .top, spacing: Spacing.m) {
                        Image(systemName: "key.fill").font(.system(size: 16, weight: .semibold))
                        Text("Dernier rappel : ta phrase secrète est la seule clé. Mets-la à l'abri avant de fermer l'app.")
                            .font(EggFont.bodyS)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }
}
