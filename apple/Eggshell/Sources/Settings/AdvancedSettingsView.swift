import SwiftUI
import TransitionCore

@MainActor
final class AdvancedSettingsViewModel: ObservableObject {
    @Published var exporting = false
    @Published var error: String?

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
}

struct AdvancedSettingsView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var securityFlags: SecurityFlags
    @Environment(\.palette) private var palette
    @StateObject private var vm = AdvancedSettingsViewModel()

    @State private var passphrase = ""
    @State private var exportURL: URL?
    @State private var showWipeConfirm = false

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                privacySection
                backupSection
                wipeSection
                importSection
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Avancé")
    }

    // (1) Confidentialité
    private var privacySection: some View {
        SectionCard {
            Text("Confidentialité").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Toggle(isOn: $securityFlags.blockScreenshots) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Bloquer les captures d'écran").font(.eggCallout).foregroundStyle(palette.onSurface)
                    Text("Appliqué progressivement").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
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

    // (3) Effacer le coffre
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

    // Import / changement de mode
    private var importSection: some View {
        SectionCard {
            Text("Import / changement de mode").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Text("Bientôt disponible").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
        }
    }
}
