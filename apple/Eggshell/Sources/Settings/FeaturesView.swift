import SwiftUI
import TransitionCore

// Porte 1 — **Modules** (§2.4). The eight switches that decide what the app
// tracks for you.
//
// A module is a tile on Accueil, never a destination: turning one off removes its
// tile and its quick-log entry and nothing else. The hint says so explicitly,
// because « désactiver » reads like « effacer » to anyone who has lost data once.

struct FeaturesView: View {
    @EnvironmentObject private var features: FeaturesStore
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                Text("Désactiver un module retire seulement sa tuile de l'accueil. Rien n'est effacé : tout revient dès que tu le réactives.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                MicroLabel("\(features.enabledCount) ACTIVÉS SUR \(FeaturesStore.togglableCount)")

                ListGroup {
                    ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                        moduleToggle(row)
                        if index != rows.count - 1 {
                            // §4: in an iOS inset grouped list the hairline starts
                            // past the leading glyph, not at the screen margin.
                            // Shared with `ListRowView` so every settings list
                            // draws the same edge.
                            Rectangle()
                                .fill(palette.outlineVariant)
                                .frame(height: 1)
                                .padding(.leading, ListRowView.separatorInset)
                        }
                    }
                }
                Color.clear.frame(height: Spacing.s)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Modules")
        .navigationBarTitleDisplayMode(.inline)
    }

    private struct ModuleRow {
        let title: String
        let subtitle: String
        let systemImage: String
        let binding: Binding<Bool>
        /// Announced, not shipped. Drawn switched on because that is what it
        /// will be when it lands, and disabled because an enabled switch would
        /// persist a choice against a module that does not exist.
        var enabled: Bool = true
    }

    /// Catalogue order, the same one the launcher grid uses (§6.1).
    private var rows: [ModuleRow] {
        [
            ModuleRow(
                title: "Médics",
                subtitle: "Tes traitements, tes prises, la rotation des sites d'injection.",
                systemImage: "pills",
                binding: $features.medications),
            ModuleRow(
                title: "Rendez-vous",
                subtitle: "Tes consultations, les personnes qui te suivent, ce que tu veux leur demander.",
                systemImage: "calendar",
                binding: $features.appointments),
            ModuleRow(
                title: "Journal",
                subtitle: "Ton humeur, tes indicateurs, les effets que tu ressens.",
                systemImage: "heart.text.square",
                binding: $features.journal),
            ModuleRow(
                title: "Menstruations",
                subtitle: "Tes saignements et leurs symptômes, jour par jour.",
                systemImage: "drop",
                binding: $features.bleeding),
            ModuleRow(
                title: "Analyses",
                subtitle: "Tes taux hormonaux dans le temps, saisis ou importés.",
                systemImage: "chart.line.uptrend.xyaxis",
                binding: $features.hormones),
            ModuleRow(
                title: "Poids",
                subtitle: "Ta courbe de poids, à côté du reste.",
                systemImage: "scalemass",
                binding: $features.weight),
            ModuleRow(
                title: "Photos",
                subtitle: "Ta timeline datée, chiffrée sur l'appareil.",
                systemImage: "photo.on.rectangle",
                binding: $features.photos),
            ModuleRow(
                title: "Voix",
                subtitle: "Tes extraits audio datés, pour entendre l'évolution.",
                systemImage: "waveform",
                binding: $features.voice),
            // -- Famille Autres --
            ModuleRow(
                title: "Notes",
                subtitle: "Un carnet en markdown, avec images et dossiers.",
                systemImage: "doc.text",
                binding: $features.notes),
            ModuleRow(
                title: "Carnet de rêves",
                subtitle: "Tes rêves, avec tags, notes vocales et curseurs de sommeil.",
                systemImage: "moon.stars.fill",
                binding: $features.dreams),
        ]
    }

    private func moduleToggle(_ row: ModuleRow) -> some View {
        toggleBody(row)
            .disabled(!row.enabled)
            // Greys the label, the subtitle and the icon tile together, so a
            // disabled row reads as unavailable rather than merely switched off.
            .opacity(row.enabled ? 1 : 0.5)
    }

    private func toggleBody(_ row: ModuleRow) -> some View {
        Toggle(isOn: row.binding) {
            HStack(spacing: Spacing.m) {
                IconTile(size: 44) {
                    Image(systemName: row.systemImage)
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.title)
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                    Text(row.subtitle)
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .tint(palette.primary)
        .padding(.horizontal, Metrics.screenMargin)
        .padding(.vertical, Spacing.m)
        .frame(minHeight: 56)
    }
}
