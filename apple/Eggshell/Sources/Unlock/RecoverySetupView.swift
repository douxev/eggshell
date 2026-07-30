import LocalAuthentication
import SwiftUI

/// The screen that will not let you past until you have a second way in.
///
/// Shown to biometric-mode users who have no recovery secret. It is deliberately
/// impossible to skip: not because a modal is pleasant, but because the failure
/// it prevents is silent and total. Enrolling a new fingerprint or a new face
/// invalidates the Keychain key that wraps the vault, and with nothing else
/// wrapping it the data is unrecoverable — no support, no reset, no export. The
/// person finds out weeks later, at the worst moment, and the app is the thing
/// that let them walk into it.
///
/// Mirrors android's RecoverySetupScreen, including the length advice: the copy
/// recommends a passphrase of several words and the field accepts eight
/// characters, because a rule people work around is worse than a rule they meet
/// halfway.
struct RecoverySetupView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette

    /// When true the screen is a normal, dismissible settings page rather than
    /// the gate. Same form either way.
    var dismissible: Bool = false
    var onDone: (() -> Void)? = nil

    @State private var secret = ""
    @State private var confirmation = ""
    @State private var working = false
    @State private var error: String?

    private static let recommended = 12
    private static let minimum = 8

    private var tooShort: Bool { secret.count < Self.minimum }
    private var mismatch: Bool { !confirmation.isEmpty && confirmation != secret }
    private var canSave: Bool { !tooShort && secret == confirmation && !working }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                header
                explanation
                fields
                if let error { ErrorCardView(error) }
                saveButton
                Color.clear.frame(height: Spacing.xl)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.l)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle(dismissible ? "Clé de récupération" : "")
        .interactiveDismissDisabled(!dismissible)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Image(systemName: "key.horizontal.fill")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(palette.primary)
            Text("Une seconde façon d'ouvrir ton coffre")
                .font(EggFont.screenTitle)
                .foregroundStyle(palette.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.bottom, Spacing.xs)
    }

    private var explanation: some View {
        EggCard(variant: .low) {
            Text("Aujourd'hui, seul Face ID ou Touch ID ouvre ce coffre.")
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            Text("""
                 Si tu ajoutes une empreinte ou un visage dans les réglages de \
                 ton téléphone, iOS considère que la clé de l'app n'est plus \
                 fiable et la détruit. Tes données restent chiffrées sur \
                 l'appareil, mais plus rien ne peut les déchiffrer. Il n'y a ni \
                 réinitialisation, ni support, ni récupération possible.
                 """)
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            Text("""
                 La clé de récupération que tu choisis ici est enregistrée à \
                 part, sans passer par le matériel du téléphone. C'est ce qui \
                 lui permet de survivre à ça.
                 """)
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SecureField("Clé de récupération", text: $secret)
                .textContentType(.newPassword)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(Spacing.m)
                .background(RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                    .fill(palette.surfaceContainerLow))

            SecureField("Répète-la", text: $confirmation)
                .textContentType(.newPassword)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(Spacing.m)
                .background(RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                    .fill(palette.surfaceContainerLow))

            Text(advice)
                .font(EggFont.bodyS)
                .foregroundStyle(mismatch || (tooShort && !secret.isEmpty)
                                 ? palette.error : palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)

            Text("""
                 Note-la quelque part de sûr, hors du téléphone. Personne ne \
                 peut te la redonner.
                 """)
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// One line that says what is wrong, or what good looks like. The minimum
    /// is 8 and the advice is « plusieurs mots » — length is worth far more
    /// than any character-class rule, and a rule people fight gets defeated
    /// with « Motdepasse1! ».
    private var advice: String {
        if mismatch { return "Les deux saisies ne correspondent pas." }
        if secret.isEmpty { return "Trois ou quatre mots que toi seul·e peux retrouver. \(Self.recommended) caractères ou plus, c'est l'idéal." }
        if tooShort { return "Encore \(Self.minimum - secret.count) caractère(s) minimum." }
        if secret.count < Self.recommended { return "Ça passe. Plus long serait nettement plus solide." }
        return "Bonne longueur."
    }

    private var saveButton: some View {
        Button {
            Task { await save() }
        } label: {
            HStack {
                Spacer()
                if working { ProgressView().tint(palette.onPrimary) }
                Text(working ? "Enregistrement…" : "Enregistrer la clé")
                    .font(EggFont.label)
                Spacer()
            }
            .frame(height: 52)
            .background(RoundedRectangle(cornerRadius: Radius.pill, style: .continuous)
                .fill(canSave ? palette.primary : palette.surfaceContainerHighest))
            .foregroundStyle(canSave ? palette.onPrimary : palette.onSurfaceVariant)
        }
        .buttonStyle(.plain)
        .disabled(!canSave)
    }

    private func save() async {
        working = true
        error = nil
        defer { working = false }

        // One more Face ID prompt: the raw master key is not kept in memory
        // after unlock, so minting a second wrap means proving again that you
        // are the person who can already open this vault.
        let context = LAContext()
        context.localizedReason = "Confirmer pour créer la clé de récupération"
        do {
            try await app.manager.setRecoverySecret(secret, biometricContext: context)
            secret = ""
            confirmation = ""
            if let onDone { onDone() } else { app.recoverySetupFinished() }
        } catch {
            self.error = "On n'a pas réussi à enregistrer cette clé. Réessaie."
        }
    }
}
