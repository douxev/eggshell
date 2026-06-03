import SwiftUI
import LocalAuthentication

@MainActor
final class OnboardingViewModel: ObservableObject {
    enum Step { case welcome, features, mode, passphrase, decoy, creating }

    @Published var step: Step = .welcome
    @Published var mode: SecurityMode = .keystoreBiometric
    @Published var passphrase = ""
    @Published var passphraseConfirm = ""
    @Published var decoyEnabled = false
    @Published var accessPin = ""
    @Published var decoyPin = ""
    @Published var error: String?

    private weak var app: AppState?
    func bind(_ app: AppState) { self.app = app }

    var passphraseValid: Bool {
        passphrase.count >= 8 && passphrase == passphraseConfirm
    }
    var decoyValid: Bool {
        !decoyEnabled || (accessPin.count == 4 && decoyPin.count == 4 && accessPin != decoyPin)
    }

    func advanceFromMode() {
        error = nil
        if mode.needsPassphrase { step = .passphrase }
        else { step = .decoy }
    }

    func create() async {
        guard let app else { return }
        error = nil
        step = .creating
        do {
            var ctx: LAContext?
            if mode.needsBiometric {
                ctx = try await Biometric.authenticate(reason: "Activer le déverrouillage biométrique")
            }
            let session = try await app.manager.create(
                mode: mode,
                passphrase: mode.needsPassphrase ? passphrase : nil,
                biometricContext: ctx
            )
            if decoyEnabled {
                try DecoyVerifier().setPair(accessPin: accessPin, decoyPin: decoyPin)
            }
            app.completeOnboarding(session: session)
        } catch {
            self.error = describe(error)
            step = .mode
        }
    }
}

struct OnboardingView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var features: FeaturesStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = OnboardingViewModel()

    var body: some View {
        ZStack {
            LinearGradient(colors: [palette.surface, palette.primaryContainer.opacity(0.5)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.xl) {
                    step
                    if let e = vm.error { ErrorBanner(message: e) }
                }
                .padding(Spacing.xl)
            }
        }
        .task { vm.bind(app) }
    }

    @ViewBuilder
    private var step: some View {
        switch vm.step {
        case .welcome:    welcome
        case .features:   featuresStep
        case .mode:       modeStep
        case .passphrase: passphraseStep
        case .decoy:      decoyStep
        case .creating:   ProgressView("Création du coffre…").tint(palette.primary)
        }
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            Image(systemName: "heart.fill").font(.system(size: 56)).foregroundStyle(palette.primary)
            Text("Bienvenue").font(.eggDisplay).foregroundStyle(palette.onSurface)
            Text("Un suivi privé et chiffré de ta transition. Toutes tes données restent sur ton appareil.")
                .font(.eggBody).foregroundStyle(palette.onSurface.opacity(0.8))
            Button("Commencer") { vm.step = .features }
                .glassProminentButton().tint(palette.primary)
        }
    }

    private var featuresStep: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Quelles fonctions utilises-tu ?").font(.eggTitle).foregroundStyle(palette.onSurface)
            Toggle("Médicaments", isOn: $features.medications)
            Toggle("Journal", isOn: $features.journal)
            Toggle("Hormones", isOn: $features.hormones)
            Toggle("Poids", isOn: $features.weight)
            Toggle("Photos", isOn: $features.photos)
            Toggle("Voix", isOn: $features.voice)
            Button("Continuer") { vm.step = .mode }
                .glassProminentButton().tint(palette.primary)
        }
        .tint(palette.primary)
    }

    private var modeStep: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Sécurité").font(.eggTitle).foregroundStyle(palette.onSurface)
            ForEach(SecurityMode.allCases) { m in
                Button { vm.mode = m } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(m.title).font(.eggHeadline).foregroundStyle(palette.onSurface)
                        Text(m.blurb).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(Spacing.m)
                    .background(vm.mode == m ? palette.primaryContainer : palette.surfaceContainerHigh,
                               in: RoundedRectangle(cornerRadius: Corner.medium, style: .continuous))
                }
                .buttonStyle(.plain)
            }
            Button("Continuer") { vm.advanceFromMode() }
                .glassProminentButton().tint(palette.primary)
        }
    }

    private var passphraseStep: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Phrase secrète").font(.eggTitle).foregroundStyle(palette.onSurface)
            Text("Au moins 8 caractères. Elle ne peut pas être récupérée si tu l'oublies.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
            SecureField("Phrase secrète", text: $vm.passphrase).textFieldStyle(.roundedBorder)
            SecureField("Confirmer", text: $vm.passphraseConfirm).textFieldStyle(.roundedBorder)
            Button("Continuer") { vm.step = .decoy }
                .glassProminentButton().tint(palette.primary)
                .disabled(!vm.passphraseValid)
        }
    }

    private var decoyStep: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Code décoy (optionnel)").font(.eggTitle).foregroundStyle(palette.onSurface)
            Text("Un code d'accès ouvre ton coffre ; un code décoy ouvre une fausse app de notes.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
            Toggle("Activer un code décoy", isOn: $vm.decoyEnabled).tint(palette.primary)
            if vm.decoyEnabled {
                SecureField("Code d'accès (4 chiffres)", text: $vm.accessPin)
                    .textFieldStyle(.roundedBorder).keyboardType(.numberPad)
                SecureField("Code décoy (4 chiffres)", text: $vm.decoyPin)
                    .textFieldStyle(.roundedBorder).keyboardType(.numberPad)
            }
            Button("Créer mon coffre") { Task { await vm.create() } }
                .glassProminentButton().tint(palette.primary)
                .disabled(!vm.decoyValid)
        }
    }
}
