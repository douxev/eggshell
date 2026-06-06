import SwiftUI
import LocalAuthentication
import UniformTypeIdentifiers
import TransitionCore

// ===========================================================================
// Onboarding — multi-step wizard with a Back button between steps, mirroring
// the Android `OnboardingScreen` / `OnboardingViewModel` two-half flow:
//
//   A. Security (progressive disclosure, plain language)
//      • Welcome
//      • Scenario choice: "Sans prise de tête" (keystoreOnly),
//        "Avec biométrie" (keystoreBiometric), "J'ai besoin d'être protégé·e"
//        (paranoid), plus "Restaurer une sauvegarde" and a discreet advanced
//        path to the four raw SecurityMode values.
//      • Passphrase setup (passphrase / paranoid modes) with inline validation.
//      • Restore: .fileImporter + bundle passphrase → app.manager.restore(...).
//
//   B. Post-creation setup wizard (all skippable):
//      • Features (FeaturesStore toggles)
//      • Optional first medication + a daily schedule
//      • Theme picker (ThemeStore, live preview)
//      • Recap → app.completeOnboarding(session:)
//
// The vault session is created once (create/restore) then carried through the
// setup wizard; "Terminer" hands it to AppState.completeOnboarding.
// ===========================================================================

@MainActor
final class OnboardingViewModel: ObservableObject {
    enum Step: Equatable {
        case welcome
        case scenario
        case advancedMode          // raw four-mode picker
        case passphrase            // create with passphrase/paranoid
        case restore               // import an encrypted bundle
        case creating              // spinner while the vault opens
        // --- post-creation setup wizard ---
        case features
        case medication
        case theme
        case recap
    }

    static let minPassphrase = 8

    @Published var step: Step = .welcome
    @Published var error: String?

    // Security path state.
    @Published var mode: SecurityMode = .keystoreBiometric
    @Published var passphrase = ""
    @Published var passphraseConfirm = ""

    // Restore state.
    @Published var bundlePassphrase = ""
    @Published var restoreError: String?

    // The session, once created/restored. Drives the setup wizard.
    private(set) var session: VaultService?

    private weak var app: AppState?
    func bind(_ app: AppState) { self.app = app }

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

    // MARK: - Navigation helpers

    /// Step the Back button returns to, or nil when no back is allowed.
    var backTarget: Step? {
        switch step {
        case .welcome, .creating, .features, .medication, .theme, .recap:
            return nil
        case .scenario:
            return .welcome
        case .advancedMode, .restore:
            return .scenario
        case .passphrase:
            return mode == .paranoid ? .scenario : .advancedMode
        }
    }

    func goBack() {
        error = nil
        if let target = backTarget { step = target }
    }

    // MARK: - Scenario entry points

    /// "Sans prise de tête" — instant Keystore-only vault.
    func chooseNoFuss() { createKeystore(mode: .keystoreOnly) }

    /// "Avec biométrie" — Keystore vault gated by Face ID / Touch ID.
    func chooseBiometric() { createKeystore(mode: .keystoreBiometric) }

    /// "J'ai besoin d'être protégé·e" — paranoid (passphrase-derived key).
    func chooseProtected() {
        error = nil
        mode = .paranoid
        passphrase = ""
        passphraseConfirm = ""
        step = .passphrase
    }

    /// Power-user escape hatch: the raw four-mode picker.
    func chooseAdvanced() {
        error = nil
        step = .advancedMode
    }

    func chooseRestore() {
        error = nil
        restoreError = nil
        bundlePassphrase = ""
        step = .restore
    }

    /// Advanced picker selected a mode: passphrase modes need a setup screen,
    /// keystore modes create immediately.
    func pickAdvancedMode(_ m: SecurityMode) {
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

    // MARK: - Create

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
                self.step = .features
            } catch {
                self.error = describe(error)
                self.step = .scenario
            }
        }
    }

    func createWithPassphrase() {
        guard let app, passphraseValid else { return }
        let m = mode
        let pass = passphrase
        error = nil
        step = .creating
        Task {
            do {
                let s = try await app.manager.create(mode: m, passphrase: pass, biometricContext: nil)
                self.session = s
                self.passphrase = ""
                self.passphraseConfirm = ""
                self.step = .features
            } catch {
                self.error = describe(error)
                self.step = .passphrase
            }
        }
    }

    // MARK: - Restore

    func restore(from url: URL) {
        guard let app else { return }
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
                // Restore brings a fully populated vault — skip the setup wizard.
                app.completeOnboarding(session: s)
            } catch {
                self.restoreError = describe(error)
                self.step = .restore
            }
        }
    }

    // MARK: - Setup wizard transitions

    func proceedFromFeatures() {
        error = nil
        step = .medication
    }

    func skipMedication() {
        error = nil
        step = .theme
    }

    /// Persist an optional first medication (+ optional daily reminder) then
    /// advance. Best-effort: a failure surfaces an error but never blocks.
    func addMedication(name: String, dose: Double?, unit: String?,
                       remind: Bool, hour: Int, minute: Int) {
        guard let session else { step = .theme; return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { step = .theme; return }
        error = nil
        Task {
            do {
                let med = try await session.addMedication(
                    NewMedication(
                        name: trimmed,
                        kind: "other",
                        route: "oral",
                        defaultDose: dose,
                        defaultDoseUnit: (unit?.isEmpty == false) ? unit : nil,
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
            self.step = .theme
        }
    }

    func proceedFromTheme() {
        error = nil
        step = .recap
    }

    func finish() {
        guard let app, let session else { return }
        app.completeOnboarding(session: session)
    }
}

struct OnboardingView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var features: FeaturesStore
    @EnvironmentObject private var theme: ThemeStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = OnboardingViewModel()

    var body: some View {
        ZStack {
            LinearGradient(colors: [palette.surface, palette.primaryContainer.opacity(0.5)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.xl) {
                    if vm.backTarget != nil {
                        Button {
                            vm.goBack()
                        } label: {
                            Label("Retour", systemImage: "chevron.left")
                                .font(.eggCallout)
                                .foregroundStyle(palette.primary)
                        }
                        .buttonStyle(.plain)
                    }
                    stepContent
                    if let e = vm.error { ErrorBanner(message: e) }
                }
                .padding(Spacing.xl)
            }
        }
        .task { vm.bind(app) }
    }

    @ViewBuilder
    private var stepContent: some View {
        switch vm.step {
        case .welcome:      welcome
        case .scenario:     scenario
        case .advancedMode: advancedMode
        case .passphrase:   passphraseStep
        case .restore:      restoreStep
        case .creating:     creating
        case .features:     featuresStep
        case .medication:   MedicationSetupStep(vm: vm)
        case .theme:        themeStep
        case .recap:        recapStep
        }
    }

    // MARK: - A. Welcome

    private var welcome: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            Image(systemName: "heart.fill")
                .font(.system(size: 56))
                .foregroundStyle(palette.primary)
            Text("Bienvenue")
                .font(.eggDisplay)
                .foregroundStyle(palette.onSurface)
            Text("Un suivi privé et chiffré de ta transition. Toutes tes données restent sur ton appareil.")
                .font(.eggBody)
                .foregroundStyle(palette.onSurface.opacity(0.8))
            Button("Commencer") { vm.step = .scenario }
                .glassProminentButton()
                .tint(palette.primary)
                .frame(maxWidth: .infinity)
        }
    }

    // MARK: - A. Scenario

    private var scenario: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Comment veux-tu protéger ton coffre ?")
                .font(.eggTitle)
                .foregroundStyle(palette.onSurface)
            Text("Tu pourras changer ce réglage plus tard.")
                .font(.eggCallout)
                .foregroundStyle(palette.onSurface.opacity(0.7))

            scenarioCard(
                icon: "bolt.fill",
                title: "Sans prise de tête",
                body: "Déverrouillage immédiat, sans code. La clé est protégée par le matériel de l'appareil.",
                action: vm.chooseNoFuss)
            scenarioCard(
                icon: "faceid",
                title: "Avec biométrie",
                body: "Face ID / Touch ID à chaque ouverture. Recommandé pour la plupart des gens.",
                action: vm.chooseBiometric)
            scenarioCard(
                icon: "lock.shield.fill",
                title: "J'ai besoin d'être protégé·e",
                body: "Une phrase secrète chiffre tout. Rien n'est déchiffrable sans elle — même pas par toi si tu l'oublies.",
                action: vm.chooseProtected)

            Button(action: vm.chooseRestore) {
                Label("Restaurer une sauvegarde", systemImage: "arrow.down.doc")
                    .frame(maxWidth: .infinity)
            }
            .glassButton()
            .tint(palette.primary)

            Button(action: vm.chooseAdvanced) {
                Text("Configuration avancée")
                    .font(.eggCallout)
                    .foregroundStyle(palette.primary)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.plain)
            .padding(.top, Spacing.s)
        }
    }

    private func scenarioCard(icon: String, title: String, body: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: Spacing.m) {
                Image(systemName: icon)
                    .font(.system(size: 22))
                    .foregroundStyle(palette.primary)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.eggHeadline).foregroundStyle(palette.onSurface)
                    Text(body).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Spacing.l)
            .glassCard(cornerRadius: Corner.large)
        }
        .buttonStyle(.plain)
    }

    // MARK: - A. Advanced four-mode picker

    private var advancedMode: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Mode de sécurité")
                .font(.eggTitle)
                .foregroundStyle(palette.onSurface)
            Text("Toutes les données sont chiffrées localement quel que soit le mode.")
                .font(.eggCallout)
                .foregroundStyle(palette.onSurface.opacity(0.7))
            ForEach(SecurityMode.allCases) { m in
                Button { vm.pickAdvancedMode(m) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(m.title).font(.eggHeadline).foregroundStyle(palette.onSurface)
                        Text(m.blurb).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(Spacing.l)
                    .glassCard(cornerRadius: Corner.large)
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - A. Passphrase setup

    private var passphraseStep: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text(vm.mode == .paranoid ? "Choisis ta phrase secrète" : "Phrase secrète")
                .font(.eggTitle)
                .foregroundStyle(palette.onSurface)
            Text("Au moins \(OnboardingViewModel.minPassphrase) caractères. Elle ne peut pas être récupérée si tu l'oublies.")
                .font(.eggCallout)
                .foregroundStyle(palette.onSurface.opacity(0.7))

            SecureField("Phrase secrète", text: $vm.passphrase)
                .textFieldStyle(.roundedBorder)
            if vm.passphraseTooShort {
                Text("Trop courte (minimum \(OnboardingViewModel.minPassphrase) caractères).")
                    .font(.eggCaption)
                    .foregroundStyle(palette.error)
            }
            SecureField("Confirmer", text: $vm.passphraseConfirm)
                .textFieldStyle(.roundedBorder)
            if vm.passphraseMismatch {
                Text("Les phrases ne correspondent pas.")
                    .font(.eggCaption)
                    .foregroundStyle(palette.error)
            }

            Button("Créer mon coffre") { vm.createWithPassphrase() }
                .glassProminentButton()
                .tint(palette.primary)
                .frame(maxWidth: .infinity)
                .disabled(!vm.passphraseValid)
        }
    }

    // MARK: - A. Restore

    private var restoreStep: some View {
        RestoreStep(vm: vm)
    }

    private var creating: some View {
        VStack(spacing: Spacing.m) {
            ProgressView()
                .tint(palette.primary)
            Text("Préparation du coffre…")
                .font(.eggCallout)
                .foregroundStyle(palette.onSurface.opacity(0.7))
        }
        .frame(maxWidth: .infinity)
        .padding(Spacing.xl)
    }

    // MARK: - B. Features

    private var featuresStep: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Quelles fonctions utilises-tu ?")
                .font(.eggTitle)
                .foregroundStyle(palette.onSurface)
            Text("Tu pourras les activer ou les masquer plus tard dans les réglages.")
                .font(.eggCallout)
                .foregroundStyle(palette.onSurface.opacity(0.7))
            SectionCard {
                Toggle("Médicaments", isOn: $features.medications)
                Toggle("Journal", isOn: $features.journal)
                Toggle("Hormones", isOn: $features.hormones)
                Toggle("Poids", isOn: $features.weight)
                Toggle("Saignements", isOn: $features.bleeding)
                Toggle("Photos", isOn: $features.photos)
                Toggle("Voix", isOn: $features.voice)
            }
            .tint(palette.primary)
            Button("Continuer") { vm.proceedFromFeatures() }
                .glassProminentButton()
                .tint(palette.primary)
                .frame(maxWidth: .infinity)
        }
        .tint(palette.primary)
    }

    // MARK: - B. Theme

    private var themeStep: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Choisis un thème")
                .font(.eggTitle)
                .foregroundStyle(palette.onSurface)
            Text("Aperçu en direct — applique-le tout de suite.")
                .font(.eggCallout)
                .foregroundStyle(palette.onSurface.opacity(0.7))
            let columns = [GridItem(.adaptive(minimum: 96), spacing: Spacing.m)]
            LazyVGrid(columns: columns, spacing: Spacing.m) {
                ForEach(Themes.all) { t in
                    themeSwatch(t)
                }
            }
            Button("Continuer") { vm.proceedFromTheme() }
                .glassProminentButton()
                .tint(palette.primary)
                .frame(maxWidth: .infinity)
        }
    }

    private func themeSwatch(_ t: Theme) -> some View {
        let selected = theme.themeId == t.id
        let swatch = t.swatch(dark: false)
        return Button {
            theme.themeId = t.id
        } label: {
            VStack(spacing: Spacing.s) {
                ZStack {
                    RoundedRectangle(cornerRadius: Corner.medium, style: .continuous)
                        .fill(swatch.surface)
                    Circle()
                        .fill(swatch.primary)
                        .frame(width: 26, height: 26)
                }
                .frame(height: 48)
                Text(t.label)
                    .font(.eggLabel)
                    .foregroundStyle(palette.onSurface)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(Spacing.s)
            .background(palette.surfaceContainer,
                        in: RoundedRectangle(cornerRadius: Corner.large, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: Corner.large, style: .continuous)
                    .strokeBorder(selected ? palette.primary : Color.clear, lineWidth: 2))
        }
        .buttonStyle(.plain)
    }

    // MARK: - B. Recap

    private var recapStep: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 52))
                .foregroundStyle(palette.success)
            Text("Tout est prêt")
                .font(.eggDisplay)
                .foregroundStyle(palette.onSurface)
            Text("Ton coffre chiffré est créé. Tu peux commencer à suivre ta transition en toute confidentialité.")
                .font(.eggBody)
                .foregroundStyle(palette.onSurface.opacity(0.8))
            Button("Terminer") { vm.finish() }
                .glassProminentButton()
                .tint(palette.primary)
                .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Restore sub-view (owns its own file-import sheet state)

private struct RestoreStep: View {
    @ObservedObject var vm: OnboardingViewModel
    @Environment(\.palette) private var palette
    @State private var picking = false
    @State private var pickedURL: URL?

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Restaurer une sauvegarde")
                .font(.eggTitle)
                .foregroundStyle(palette.onSurface)
            Text("Choisis ton fichier de sauvegarde chiffré, puis saisis la phrase secrète qui l'a protégé.")
                .font(.eggCallout)
                .foregroundStyle(palette.onSurface.opacity(0.7))

            Button {
                picking = true
            } label: {
                Label(pickedURL?.lastPathComponent ?? "Choisir un fichier…",
                      systemImage: "doc.badge.plus")
                    .frame(maxWidth: .infinity)
            }
            .glassButton()
            .tint(palette.primary)

            SecureField("Phrase secrète de la sauvegarde", text: $vm.bundlePassphrase)
                .textFieldStyle(.roundedBorder)

            if let e = vm.restoreError {
                Text(e)
                    .font(.eggCaption)
                    .foregroundStyle(palette.error)
            }

            Button("Restaurer") {
                if let url = pickedURL { vm.restore(from: url) }
            }
            .glassProminentButton()
            .tint(palette.primary)
            .frame(maxWidth: .infinity)
            .disabled(pickedURL == nil || vm.bundlePassphrase.isEmpty)
        }
        .fileImporter(
            isPresented: $picking,
            allowedContentTypes: [.data, .item],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls): pickedURL = urls.first
            case .failure(let err):  vm.restoreError = describe(err)
            }
        }
    }
}

// MARK: - Optional first-medication step (local field state)

private struct MedicationSetupStep: View {
    @ObservedObject var vm: OnboardingViewModel
    @Environment(\.palette) private var palette

    @State private var name = ""
    @State private var dose = ""
    @State private var unit = ""
    @State private var remind = false
    @State private var hour = 8
    @State private var minute = 0

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Un premier médicament ?")
                .font(.eggTitle)
                .foregroundStyle(palette.onSurface)
            Text("Optionnel — tu pourras en ajouter autant que tu veux plus tard.")
                .font(.eggCallout)
                .foregroundStyle(palette.onSurface.opacity(0.7))

            SectionCard {
                TextField("Nom", text: $name)
                    .textFieldStyle(.roundedBorder)
                HStack(spacing: Spacing.s) {
                    TextField("Dose", text: $dose)
                        .keyboardType(.decimalPad)
                        .textFieldStyle(.roundedBorder)
                    TextField("Unité", text: $unit)
                        .textFieldStyle(.roundedBorder)
                }
                Toggle("Me le rappeler chaque jour", isOn: $remind)
                    .tint(palette.primary)
                if remind {
                    HStack {
                        Text("Heure").font(.eggCallout).foregroundStyle(palette.onSurface)
                        Spacer()
                        TextField("Heure", value: $hour, format: .number)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 48)
                        Stepper("", value: $hour, in: 0...23).labelsHidden()
                    }
                    HStack {
                        Text("Minute").font(.eggCallout).foregroundStyle(palette.onSurface)
                        Spacer()
                        TextField("Minute", value: $minute, format: .number)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 48)
                        Stepper("", value: $minute, in: 0...59).labelsHidden()
                    }
                }
            }

            Button("Ajouter") {
                vm.addMedication(
                    name: name,
                    dose: Self.parseDose(dose),
                    unit: unit.isEmpty ? nil : unit,
                    remind: remind,
                    hour: hour,
                    minute: minute)
            }
            .glassProminentButton()
            .tint(palette.primary)
            .frame(maxWidth: .infinity)
            .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)

            Button("Plus tard") { vm.skipMedication() }
                .glassButton()
                .tint(palette.primary)
                .frame(maxWidth: .infinity)
        }
    }

    private static func parseDose(_ s: String) -> Double? {
        Double(s.replacingOccurrences(of: ",", with: "."))
    }
}
