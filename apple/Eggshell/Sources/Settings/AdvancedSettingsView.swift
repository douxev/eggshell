import SwiftUI
import TransitionCore
import UniformTypeIdentifiers

@MainActor
final class AdvancedSettingsViewModel: ObservableObject {
    @Published var exporting = false
    @Published var restoring = false
    @Published var changingMode = false
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
    func restore(_ app: AppState, url: URL, bundlePassphrase: String,
                 mode: SecurityMode, localPassphrase: String?) async {
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
    func changeMode(_ app: AppState, to newMode: SecurityMode,
                    currentPassphrase: String?, newPassphrase: String?) async {
        changingMode = true
        error = nil
        info = nil
        defer { changingMode = false }
        do {
            try await app.manager.changeMode(
                to: newMode,
                currentPassphrase: currentPassphrase,
                newPassphrase: newPassphrase)
            info = "Mode de sécurité mis à jour."
        } catch {
            self.error = describe(error)
        }
    }
}

struct AdvancedSettingsView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var securityFlags: SecurityFlags
    @Environment(\.palette) private var palette
    @StateObject private var vm = AdvancedSettingsViewModel()

    // Export
    @State private var passphrase = ""
    @State private var exportURL: URL?

    // Restore
    @State private var showImporter = false
    @State private var pendingBundleURL: URL?
    @State private var restoreBundlePass = ""
    @State private var restoreMode: SecurityMode = .keystoreOnly
    @State private var restoreLocalPass = ""

    // Change mode
    @State private var targetMode: SecurityMode = .keystoreBiometric
    @State private var currentPass = ""
    @State private var newPass = ""

    // Icon disguise
    @State private var iconVariant: AppIconVariant = AppIconManager.current

    // Wipe
    @State private var showWipeConfirm = false

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                privacySection
                backupSection
                restoreSection
                securityModeSection
                iconDisguiseSection
                wipeSection
                if let info = vm.info {
                    Text(info).font(.eggCaption).foregroundStyle(palette.success)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Avancé")
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

    // (1) Confidentialité
    private var privacySection: some View {
        SectionCard {
            Text("Confidentialité").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Toggle(isOn: $securityFlags.blockScreenshots) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Masquer le contenu sensible").font(.eggCallout).foregroundStyle(palette.onSurface)
                    Text("Masque l'app dans le sélecteur d'apps et pendant l'enregistrement ou le miroir d'écran.")
                        .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(palette.primary)
        }
    }

    // (2) Sauvegarde chiffrée
    private var backupSection: some View {
        SectionCard {
            Text("Sauvegarde chiffrée").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Text("Exporte tout ton coffre dans un fichier protégé par une phrase secrète.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                .frame(maxWidth: .infinity, alignment: .leading)
            SecureField("Phrase secrète", text: $passphrase)
                .textFieldStyle(.roundedBorder)
            Button {
                guard let session = app.session else { return }
                let pass = passphrase
                Task {
                    if let url = await vm.export(session, passphrase: pass) {
                        exportURL = url
                    }
                }
            } label: {
                if vm.exporting {
                    ProgressView().tint(palette.onPrimary).frame(maxWidth: .infinity)
                } else {
                    Text("Exporter").frame(maxWidth: .infinity)
                }
            }
            .glassProminentButton().tint(palette.primary)
            .disabled(passphrase.isEmpty || vm.exporting || app.session == nil)

            if let url = exportURL {
                ShareLink(item: url) {
                    Label("Partager la sauvegarde", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .glassButton().tint(palette.primary)
            }
        }
    }

    // (3) Restauration
    private var restoreSection: some View {
        SectionCard {
            Text("Restauration").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Text("Importe une sauvegarde chiffrée. Le coffre actuel sera remplacé.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                .frame(maxWidth: .infinity, alignment: .leading)

            Button {
                showImporter = true
            } label: {
                Label(pendingBundleURL == nil ? "Choisir un fichier…" : "Fichier sélectionné",
                      systemImage: "doc.badge.arrow.up")
                    .frame(maxWidth: .infinity)
            }
            .glassButton().tint(palette.primary)

            if let bundleURL = pendingBundleURL {
                Text(bundleURL.lastPathComponent)
                    .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
                    .frame(maxWidth: .infinity, alignment: .leading)

                SecureField("Phrase secrète de la sauvegarde", text: $restoreBundlePass)
                    .textFieldStyle(.roundedBorder)

                Text("Mode de sécurité après restauration").font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)
                modePicker(selection: $restoreMode)

                if restoreMode.needsPassphrase {
                    SecureField("Nouvelle phrase secrète locale", text: $restoreLocalPass)
                        .textFieldStyle(.roundedBorder)
                }

                Button {
                    let url = bundleURL
                    let pass = restoreBundlePass
                    let mode = restoreMode
                    let local = restoreLocalPass
                    Task {
                        await vm.restore(app, url: url, bundlePassphrase: pass,
                                         mode: mode, localPassphrase: local)
                    }
                } label: {
                    if vm.restoring {
                        ProgressView().tint(palette.onPrimary).frame(maxWidth: .infinity)
                    } else {
                        Text("Restaurer").frame(maxWidth: .infinity)
                    }
                }
                .glassProminentButton().tint(palette.primary)
                .disabled(restoreDisabled)
            }
        }
    }

    private var restoreDisabled: Bool {
        if vm.restoring { return true }
        if restoreBundlePass.isEmpty { return true }
        if restoreMode.needsPassphrase && restoreLocalPass.isEmpty { return true }
        return false
    }

    // (4) Mode de sécurité
    private var securityModeSection: some View {
        SectionCard {
            Text("Mode de sécurité").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Text("Change comment ton coffre est déverrouillé. Le mode paranoïaque n'est pas modifiable ici.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                .frame(maxWidth: .infinity, alignment: .leading)

            modePicker(selection: $targetMode)
            Text(targetMode.blurb)
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                .frame(maxWidth: .infinity, alignment: .leading)

            SecureField("Phrase secrète actuelle (si requise)", text: $currentPass)
                .textFieldStyle(.roundedBorder)
            if targetMode.needsPassphrase {
                SecureField("Nouvelle phrase secrète", text: $newPass)
                    .textFieldStyle(.roundedBorder)
            }

            Button {
                let mode = targetMode
                let cur = currentPass.isEmpty ? nil : currentPass
                let nw = newPass.isEmpty ? nil : newPass
                Task {
                    await vm.changeMode(app, to: mode, currentPassphrase: cur, newPassphrase: nw)
                }
            } label: {
                if vm.changingMode {
                    ProgressView().tint(palette.onPrimary).frame(maxWidth: .infinity)
                } else {
                    Text("Appliquer le mode").frame(maxWidth: .infinity)
                }
            }
            .glassProminentButton().tint(palette.primary)
            .disabled(vm.changingMode || (targetMode.needsPassphrase && newPass.isEmpty))
        }
    }

    // (5) Déguisement d'icône
    private var iconDisguiseSection: some View {
        SectionCard {
            Text("Déguisement d'icône").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            if AppIconManager.available {
                Text("Affiche l'app sous une autre identité sur l'écran d'accueil.")
                    .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)
                ForEach(AppIconVariant.allCases) { variant in
                    Button {
                        iconVariant = variant
                        Task { try? await AppIconManager.set(variant) }
                    } label: {
                        HStack(spacing: Spacing.m) {
                            Image(systemName: variant.systemImage)
                                .frame(width: 24)
                            Text(variant.label).font(.eggCallout)
                            Spacer()
                            if iconVariant == variant {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(palette.primary)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(palette.onSurface)
                }
            } else {
                Text("Les icônes alternatives ne sont pas encore intégrées à cette version de l'app.")
                    .font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    // (6) Effacer le coffre
    private var wipeSection: some View {
        SectionCard {
            Text("Effacer le coffre").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Text("Supprime définitivement toutes les données de l'app sur cet appareil.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(role: .destructive) {
                showWipeConfirm = true
            } label: {
                Label("Tout effacer", systemImage: "trash").frame(maxWidth: .infinity)
            }
            .glassButton().tint(palette.error)
            .confirmationDialog(
                "Effacer définitivement le coffre ?",
                isPresented: $showWipeConfirm,
                titleVisibility: .visible
            ) {
                Button("Tout effacer", role: .destructive) {
                    Task { await app.wipe() }
                }
                Button("Annuler", role: .cancel) {}
            } message: {
                Text("Cette action est irréversible. Toutes tes données seront perdues.")
            }
        }
    }

    // MARK: - Helpers

    /// Mode picker that excludes paranoid (unsupported for restore/change here).
    @ViewBuilder
    private func modePicker(selection: Binding<SecurityMode>) -> some View {
        Picker("Mode", selection: selection) {
            ForEach(SecurityMode.allCases.filter { $0 != .paranoid }) { mode in
                Text(mode.title).tag(mode)
            }
        }
        .pickerStyle(.menu)
        .tint(palette.primary)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
