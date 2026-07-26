import SwiftUI
import TransitionCore
import UniformTypeIdentifiers

// Porte 2 — **Sécurité** (§2.4). What it takes to open the vault, and what a look
// over your shoulder can see of it.
//
// Everything the old « Avancé » screen held is still here — lock mode, icon
// disguise, screenshot blocking, encrypted backup, restore, wipe — plus the PIN
// pair, which until now could only be set during the first run. A person whose
// situation changes has to be able to add a decoy PIN without reinstalling.

@MainActor
final class AdvancedSettingsViewModel: ObservableObject {
    @Published var exporting = false
    @Published var restoring = false
    @Published var changingMode = false
    @Published var savingPins = false
    @Published var error: String?
    @Published var info: String?

    func export(_ session: VaultService, passphrase: String) async -> URL? {
        exporting = true
        defer { exporting = false }
        do {
            let data = try await session.exportEncrypted(passphrase: passphrase)
            let url = AppPaths.cacheDir.appendingPathComponent("eggshell-backup.transition.enc")
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            self.error = describe(error)
            return nil
        }
    }

    /// Restore an encrypted bundle picked by the user. Reopens the vault under
    /// `mode` (paranoid is rejected by the core with `paranoidRequiresRekey`,
    /// which we surface as a friendly French message via `describe`).
    func restore(
        _ app: AppState,
        url: URL,
        bundlePassphrase: String,
        mode: SecurityMode,
        localPassphrase: String?
    ) async {
        restoring = true
        error = nil
        info = nil
        defer { restoring = false }
        // Picked URLs from .fileImporter are security-scoped; we must open the
        // scope before reading and close it after.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let bundle = try Data(contentsOf: url)
            let session = try await app.manager.restore(
                fromBundle: bundle,
                bundlePassphrase: bundlePassphrase,
                mode: mode,
                localPassphrase: mode.needsPassphrase ? localPassphrase : nil)
            app.unlocked(session: session)
        } catch {
            self.error = describe(error)
        }
    }

    /// Change the security mode of the already-open vault (re-wraps the master
    /// key; no DB re-key). Paranoid is rejected by the core.
    func changeMode(
        _ app: AppState,
        to newMode: SecurityMode,
        currentPassphrase: String?,
        newPassphrase: String?
    ) async {
        changingMode = true
        error = nil
        info = nil
        defer { changingMode = false }
        do {
            try await app.manager.changeMode(
                to: newMode,
                currentPassphrase: currentPassphrase,
                newPassphrase: newPassphrase)
            info = "Mode de verrouillage mis à jour."
        } catch {
            self.error = describe(error)
        }
    }

    /// Store the access / decoy pair. Argon2id runs twice, so it goes off the
    /// main actor — on an older phone this is a visible second.
    func savePins(access: String, decoy: String) async {
        savingPins = true
        error = nil
        info = nil
        defer { savingPins = false }
        do {
            try await Task.detached(priority: .userInitiated) {
                try DecoyVerifier().setPair(accessPin: access, decoyPin: decoy)
            }.value
            info = "Codes enregistrés. Le PIN de leurre ouvre un coffre vide."
        } catch {
            self.error = describe(error)
        }
    }

    func clearPins() {
        DecoyVerifier().clear()
        info = "Codes effacés. L'app s'ouvre à nouveau sans PIN."
    }
}

struct AdvancedSettingsView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var securityFlags: SecurityFlags
    @Environment(\.palette) private var palette
    @StateObject private var vm = AdvancedSettingsViewModel()

    // Backup
    @State private var passphrase = ""
    @State private var exportURL: URL?

    // Restore
    @State private var showImporter = false
    @State private var pendingBundleURL: URL?
    @State private var restoreBundlePass = ""
    @State private var restoreMode: SecurityMode = .keystoreOnly
    @State private var restoreLocalPass = ""

    // Lock mode
    @State private var targetMode: SecurityMode = .keystoreBiometric
    @State private var currentPass = ""
    @State private var newPass = ""

    // PIN pair
    @State private var accessPin = ""
    @State private var decoyPin = ""
    @State private var pinsConfigured = DecoyVerifier().isConfigured
    @State private var confirmClearPins = false

    // Icon disguise
    @State private var iconVariant: AppIconVariant = AppIconManager.current

    // Wipe
    @State private var confirmWipe = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                Text("Tout reste sur ton téléphone. Ces réglages décident de ce qu'il faut pour ouvrir le coffre — et de ce qu'un regard de côté peut en voir.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                lockModeSection
                pinSection
                maskingSection
                screenshotSection
                backupSection
                restoreSection
                wipeSection

                status
                Color.clear.frame(height: Spacing.s)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Sécurité")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: [.data, .item],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                pendingBundleURL = urls.first
            case .failure(let err):
                vm.error = describe(err)
            }
        }
    }

    /// Confirmation and failure live in the flow, at the bottom where the actions
    /// are — never a toast that can be missed (§5.3).
    @ViewBuilder
    private var status: some View {
        if let info = vm.info {
            EggCard(variant: .low, spacing: Spacing.s) {
                HStack(alignment: .top, spacing: Spacing.s) {
                    Image(systemName: "checkmark.circle")
                        .foregroundStyle(palette.success)
                    Text(info)
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        if let message = vm.error { ErrorCardView(message) }
    }

    // MARK: - Mode de verrouillage

    private var lockModeSection: some View {
        EggCard(variant: .low, spacing: Spacing.m) {
            MicroLabel("MODE DE VERROUILLAGE")
            Text("Actuel : \(currentModeLabel)")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)

            Picker("Mode", selection: $targetMode) {
                // Paranoid re-derives the DB key from the passphrase, so it cannot
                // be applied to an existing vault without a re-key.
                ForEach(SecurityMode.allCases.filter { $0 != .paranoid }) { mode in
                    Text(mode.title).tag(mode)
                }
            }
            .pickerStyle(.menu)
            .tint(palette.primary)
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(targetMode.blurb)
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)

            secure("Phrase secrète actuelle (si requise)", text: $currentPass)
            if targetMode.needsPassphrase {
                secure("Nouvelle phrase secrète", text: $newPass)
            }

            filled(vm.changingMode ? "Application…" : "Appliquer le mode",
                   enabled: !vm.changingMode && !(targetMode.needsPassphrase && newPass.isEmpty)) {
                let mode = targetMode
                let cur = currentPass.isEmpty ? nil : currentPass
                let next = newPass.isEmpty ? nil : newPass
                Task {
                    await vm.changeMode(
                        app, to: mode, currentPassphrase: cur, newPassphrase: next)
                }
            }
        }
    }

    private var currentModeLabel: String {
        let prefs = VaultPrefs()
        return prefs.modeRaw.flatMap(SecurityMode.init(rawValue:))?.title ?? "non configuré"
    }

    // MARK: - Code d'accès et PIN de leurre

    private var pinSection: some View {
        EggCard(variant: .low, spacing: Spacing.m) {
            MicroLabel("CODE D'ACCÈS ET PIN DE LEURRE")
            Text("Deux codes à quatre chiffres, obligatoirement différents. Le premier ouvre ton coffre ; le second ouvre une app de notes vide, sans jamais dire qu'elle en est une.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)

            pinField("Code d'accès", text: $accessPin)
            pinField("PIN de leurre", text: $decoyPin)

            if pinsMatch {
                Text("Les deux codes doivent être différents, sinon le leurre ne protège rien.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.error)
                    .fixedSize(horizontal: false, vertical: true)
            }

            filled(vm.savingPins ? "Enregistrement…" : "Enregistrer les codes", enabled: pinsValid) {
                let access = accessPin
                let decoy = decoyPin
                accessPin = ""
                decoyPin = ""
                Task {
                    await vm.savePins(access: access, decoy: decoy)
                    pinsConfigured = DecoyVerifier().isConfigured
                }
            }

            if pinsConfigured {
                Button(role: .destructive) { confirmClearPins = true } label: {
                    Text("Effacer les codes")
                        .font(EggFont.label)
                        .foregroundStyle(palette.error)
                        .frame(minHeight: Metrics.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .alert("Effacer les deux codes ?", isPresented: $confirmClearPins) {
                    Button("Effacer", role: .destructive) {
                        vm.clearPins()
                        pinsConfigured = DecoyVerifier().isConfigured
                    }
                    Button("Annuler", role: .cancel) {}
                } message: {
                    Text("L'app s'ouvrira sans code. Le coffre leurre reste sur l'appareil mais ne sera plus accessible.")
                }
            }
        }
    }

    private var pinsMatch: Bool {
        !accessPin.isEmpty && accessPin == decoyPin
    }

    private var pinsValid: Bool {
        accessPin.count == 4 && decoyPin.count == 4 && accessPin != decoyPin && !vm.savingPins
    }

    // MARK: - Icône et nom de l'app

    private var maskingSection: some View {
        EggCard(variant: .low, spacing: Spacing.m) {
            MicroLabel("ICÔNE ET NOM DE L'APP")
            if AppIconManager.available {
                Text("Ce que ton écran d'accueil affiche. Le changement prend effet tout de suite.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                ForEach(AppIconVariant.allCases) { variant in
                    Button {
                        iconVariant = variant
                        Task { try? await AppIconManager.set(variant) }
                    } label: {
                        HStack(spacing: Spacing.m) {
                            Image(systemName: variant.systemImage)
                                .font(.system(size: 17))
                                .foregroundStyle(palette.onSurfaceVariant)
                                .frame(width: 24)
                            Text(variant.label)
                                .font(.eggBody)
                                .foregroundStyle(palette.onSurface)
                            Spacer()
                            if iconVariant == variant {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(palette.primary)
                            }
                        }
                        .frame(minHeight: Metrics.touchTarget)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(variant.label)
                    .accessibilityAddTraits(iconVariant == variant ? [.isSelected] : [])
                }
            } else {
                Text("Les icônes alternatives ne sont pas encore intégrées à cette version de l'app.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: - Captures d'écran

    private var screenshotSection: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            Toggle(isOn: $securityFlags.blockScreenshots) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Masquer le contenu sensible")
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurface)
                    Text("Masque l'app dans le sélecteur d'apps et pendant l'enregistrement ou le miroir d'écran.")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(palette.primary)
        }
    }

    // MARK: - Sauvegarde chiffrée

    private var backupSection: some View {
        EggCard(variant: .low, spacing: Spacing.m) {
            MicroLabel("SAUVEGARDE CHIFFRÉE")
            Text("Exporte tout ton coffre dans un fichier protégé par une phrase secrète.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            secure("Phrase secrète", text: $passphrase)
            Text("Cette phrase secrète est la seule clé du fichier. Personne ne peut te la redonner.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.error)
                .fixedSize(horizontal: false, vertical: true)

            filled(vm.exporting ? "Export…" : "Exporter",
                   enabled: !passphrase.isEmpty && !vm.exporting && app.session != nil) {
                guard let session = app.session else { return }
                let pass = passphrase
                Task { exportURL = await vm.export(session, passphrase: pass) }
            }

            if let url = exportURL {
                ShareLink(item: url) {
                    Label("Partager la sauvegarde", systemImage: "square.and.arrow.up")
                        .font(EggFont.label)
                        .foregroundStyle(palette.primary)
                }
                .frame(minHeight: Metrics.touchTarget, alignment: .leading)
            }
        }
    }

    // MARK: - Restauration

    private var restoreSection: some View {
        EggCard(variant: .low, spacing: Spacing.m) {
            MicroLabel("RESTAURATION")
            Text("Importe une sauvegarde chiffrée. Le coffre actuel sera remplacé.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)

            outlined(
                pendingBundleURL == nil ? "Choisir un fichier…" : "Fichier sélectionné",
                systemImage: "doc.badge.arrow.up"
            ) {
                showImporter = true
            }

            if let bundleURL = pendingBundleURL {
                Text(bundleURL.lastPathComponent)
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)

                secure("Phrase secrète de la sauvegarde", text: $restoreBundlePass)

                Text("Mode de verrouillage après restauration")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                Picker("Mode", selection: $restoreMode) {
                    ForEach(SecurityMode.allCases.filter { $0 != .paranoid }) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.menu)
                .tint(palette.primary)
                .frame(maxWidth: .infinity, alignment: .leading)

                if restoreMode.needsPassphrase {
                    secure("Nouvelle phrase secrète locale", text: $restoreLocalPass)
                }

                filled(vm.restoring ? "Restauration…" : "Restaurer", enabled: !restoreDisabled) {
                    let url = bundleURL
                    let pass = restoreBundlePass
                    let mode = restoreMode
                    let local = restoreLocalPass
                    Task {
                        await vm.restore(
                            app, url: url, bundlePassphrase: pass,
                            mode: mode, localPassphrase: local)
                    }
                }
            }
        }
    }

    private var restoreDisabled: Bool {
        if vm.restoring { return true }
        if restoreBundlePass.isEmpty { return true }
        if restoreMode.needsPassphrase && restoreLocalPass.isEmpty { return true }
        return false
    }

    // MARK: - Effacer le coffre

    private var wipeSection: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            MicroLabel("EFFACER LE COFFRE")
            Text("Supprime définitivement toutes les données de l'app sur cet appareil.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            Button(role: .destructive) { confirmWipe = true } label: {
                Label("Tout effacer", systemImage: "trash")
                    .font(EggFont.label)
                    .foregroundStyle(palette.error)
                    .frame(minHeight: Metrics.touchTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .alert("Effacer définitivement le coffre ?", isPresented: $confirmWipe) {
                Button("Tout effacer", role: .destructive) { Task { await app.wipe() } }
                Button("Annuler", role: .cancel) {}
            } message: {
                Text("Cette action est irréversible. Toutes tes données seront perdues.")
            }
        }
    }

    // MARK: - Small controls

    private func secure(_ label: String, text: Binding<String>) -> some View {
        SecureField(label, text: text)
            .font(.eggBody)
            .foregroundStyle(palette.onSurface)
            .padding(.horizontal, Spacing.m)
            .padding(.vertical, 10)
            .frame(minHeight: Metrics.touchTarget)
            .background(
                palette.surfaceContainerHigh,
                in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
    }

    private func pinField(_ label: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
            SecureField("••••", text: text)
                .font(.eggBody)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .foregroundStyle(palette.onSurface)
                .padding(.horizontal, Spacing.m)
                .padding(.vertical, 10)
                .frame(minHeight: Metrics.touchTarget)
                .background(
                    palette.surfaceContainerHigh,
                    in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                // Four digits, nothing else: the unlock keypad cannot type more.
                .onChange(of: text.wrappedValue) { _, value in
                    let digits = String(value.filter { $0.isNumber }.prefix(4))
                    if digits != value { text.wrappedValue = digits }
                }
        }
    }

    private func filled(
        _ label: String,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 15.5, weight: .semibold))
                .foregroundStyle(palette.onPrimary)
                .frame(maxWidth: .infinity)
                .frame(height: 46)
                .background(palette.primary.opacity(enabled ? 1 : 0.4), in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    private func outlined(
        _ label: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(label, systemImage: systemImage)
                .font(.system(size: 15.5, weight: .semibold))
                .foregroundStyle(palette.primary)
                .frame(maxWidth: .infinity)
                .frame(height: 46)
                .overlay(Capsule().stroke(palette.outline, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}
